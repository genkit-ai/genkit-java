/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.genkit.plugins.qdrant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.Document;
import com.google.genkit.ai.EmbedRequest;
import com.google.genkit.ai.EmbedResponse;
import com.google.genkit.ai.Embedder;
import com.google.genkit.ai.Indexer;
import com.google.genkit.ai.IndexerRequest;
import com.google.genkit.ai.IndexerResponse;
import com.google.genkit.ai.Retriever;
import com.google.genkit.ai.RetrieverRequest;
import com.google.genkit.ai.RetrieverResponse;
import com.google.genkit.core.ActionContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qdrant vector store backed by the Qdrant REST API.
 *
 * <p>Indexes documents (embedding + payload holding the text and metadata) into a Qdrant collection
 * and retrieves the nearest neighbors of a query embedding.
 */
public final class QdrantVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(QdrantVectorStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;
  private final String apiKey;
  private final QdrantCollectionConfig config;
  private final Embedder embedder;

  private volatile boolean initialized = false;

  /**
   * Creates a new store.
   *
   * @param baseUrl the Qdrant server base URL (e.g. {@code http://localhost:6333})
   * @param apiKey the Qdrant API key, or {@code null} when the server requires none
   * @param config the collection configuration
   * @param embedder the embedder used to vectorize documents and queries
   */
  public QdrantVectorStore(
      String baseUrl, String apiKey, QdrantCollectionConfig config, Embedder embedder) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.config = config;
    this.embedder = embedder;
  }

  /** Creates the retriever action registered by the plugin. */
  Retriever createRetriever() {
    String name = QdrantPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Retriever.builder().name(name).handler(this::retrieve).build();
  }

  /** Creates the indexer action registered by the plugin. */
  Indexer createIndexer() {
    String name = QdrantPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Indexer.builder().name(name).handler(this::index).build();
  }

  private synchronized void ensureInitialized() {
    if (initialized) {
      return;
    }
    if (config.isCreateCollectionIfNotExists() && !collectionExists()) {
      ObjectNode body = MAPPER.createObjectNode();
      ObjectNode vectors = body.putObject("vectors");
      vectors.put("size", resolveDimension());
      vectors.put("distance", config.getDistance().getValue());
      send("PUT", "/collections/" + config.getCollectionName(), body);
      logger.info("Created Qdrant collection {}", config.getCollectionName());
    }
    initialized = true;
  }

  /**
   * Resolves the vector dimension by probing the embedder, falling back to the configured dimension
   * if the probe fails. This keeps the created collection in sync with whatever embedding model is
   * wired in.
   */
  private int resolveDimension() {
    try {
      return generateEmbedding(null, "genkit dimension probe").size();
    } catch (RuntimeException e) {
      logger.debug(
          "Embedding probe failed; using configured dimension {}: {}",
          config.getDimension(),
          e.getMessage());
      return config.getDimension();
    }
  }

  private boolean collectionExists() {
    try {
      send("GET", "/collections/" + config.getCollectionName(), null);
      return true;
    } catch (NotFoundException e) {
      return false;
    }
  }

  /**
   * Retrieves documents similar to the query.
   *
   * @param context the action context
   * @param request the retriever request
   * @return the retriever response with matching documents
   */
  public RetrieverResponse retrieve(ActionContext context, RetrieverRequest request) {
    ensureInitialized();
    Document queryDoc = request.getQuery();
    if (queryDoc == null || queryDoc.text() == null || queryDoc.text().isBlank()) {
      throw new RuntimeException("Query document has no text content");
    }
    int topK =
        request.getOptions() != null && request.getOptions().getK() != null
            ? request.getOptions().getK()
            : 10;
    List<Float> queryEmbedding = generateEmbedding(context, queryDoc.text());

    ObjectNode body = MAPPER.createObjectNode();
    body.set("vector", floatsToArray(queryEmbedding));
    body.put("limit", topK);
    body.put("with_payload", true);

    JsonNode resp =
        send("POST", "/collections/" + config.getCollectionName() + "/points/search", body);
    List<Document> documents = new ArrayList<>();
    JsonNode result = resp.get("result");
    if (result != null && result.isArray()) {
      for (JsonNode point : result) {
        documents.add(toDocument(point));
      }
    }
    logger.debug(
        "Retrieved {} documents from collection {}", documents.size(), config.getCollectionName());
    return new RetrieverResponse(documents);
  }

  /**
   * Indexes documents into the collection, generating an embedding for each.
   *
   * @param context the action context
   * @param request the indexer request
   * @return the indexer response
   */
  public IndexerResponse index(ActionContext context, IndexerRequest request) {
    ensureInitialized();
    List<Document> documents = request.getDocuments();
    if (documents == null || documents.isEmpty()) {
      logger.warn("No documents to index");
      return new IndexerResponse();
    }

    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode points = body.putArray("points");
    for (Document doc : documents) {
      String content = doc.text() != null ? doc.text() : "";
      List<Float> embedding = generateEmbedding(context, content);

      ObjectNode point = points.addObject();
      point.put("id", getOrGenerateId(doc));
      point.set("vector", floatsToArray(embedding));

      ObjectNode payload = point.putObject("payload");
      payload.put(config.getTextPayloadKey(), content);
      if (doc.getMetadata() != null) {
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
          if (!"id".equals(entry.getKey())) {
            payload.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
          }
        }
      }
      for (Map.Entry<String, Object> entry : config.getAdditionalMetadata().entrySet()) {
        payload.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
      }
    }

    send("PUT", "/collections/" + config.getCollectionName() + "/points?wait=true", body);
    logger.info(
        "Indexed {} documents into collection {}", documents.size(), config.getCollectionName());
    return new IndexerResponse();
  }

  private Document toDocument(JsonNode point) {
    Map<String, Object> metadata = new HashMap<>();
    String content = "";
    JsonNode payload = point.get("payload");
    if (payload != null && payload.isObject()) {
      Map<String, Object> payloadMap = MAPPER.convertValue(payload, Map.class);
      for (Map.Entry<String, Object> entry : payloadMap.entrySet()) {
        if (entry.getKey().equals(config.getTextPayloadKey())) {
          content = entry.getValue() != null ? entry.getValue().toString() : "";
        } else {
          metadata.put(entry.getKey(), entry.getValue());
        }
      }
    }
    if (point.get("id") != null) {
      metadata.put("id", point.get("id").asText());
    }
    if (point.get("score") != null && point.get("score").isNumber()) {
      metadata.put("score", point.get("score").asDouble());
    }
    Document doc = new Document(content);
    doc.setMetadata(metadata);
    return doc;
  }

  private ArrayNode floatsToArray(List<Float> values) {
    ArrayNode arr = MAPPER.createArrayNode();
    for (float v : values) {
      arr.add(v);
    }
    return arr;
  }

  private List<Float> generateEmbedding(ActionContext ctx, String text) {
    EmbedResponse response = embedder.run(ctx, new EmbedRequest(List.of(new Document(text))));
    if (response.getEmbeddings() == null || response.getEmbeddings().isEmpty()) {
      throw new RuntimeException("Failed to generate embedding for text");
    }
    float[] values = response.getEmbeddings().get(0).getValues();
    List<Float> out = new ArrayList<>(values.length);
    for (float v : values) {
      out.add(v);
    }
    return out;
  }

  private String getOrGenerateId(Document doc) {
    if (doc.getMetadata() != null && doc.getMetadata().get("id") != null) {
      String id = doc.getMetadata().get("id").toString();
      // Qdrant point ids must be an unsigned integer or a UUID; derive a stable UUID otherwise.
      try {
        UUID.fromString(id);
        return id;
      } catch (IllegalArgumentException e) {
        return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
      }
    }
    return UUID.randomUUID().toString();
  }

  private JsonNode send(String method, String path, JsonNode body) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + path))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json");
      if (apiKey != null && !apiKey.isBlank()) {
        builder.header("api-key", apiKey);
      }
      if (body != null) {
        builder.method(
            method,
            HttpRequest.BodyPublishers.ofString(
                MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
      } else {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        throw new NotFoundException(path);
      }
      if (response.statusCode() / 100 != 2) {
        throw new RuntimeException(
            "Qdrant request "
                + method
                + " "
                + path
                + " failed ("
                + response.statusCode()
                + "): "
                + response.body());
      }
      String responseBody = response.body();
      if (responseBody == null || responseBody.isBlank()) {
        return MAPPER.createObjectNode();
      }
      return MAPPER.readTree(responseBody);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(
          "Qdrant request " + method + " " + path + " failed: " + e.getMessage(), e);
    }
  }

  /** Signals a 404 from the Qdrant API (used to detect a missing collection). */
  private static final class NotFoundException extends RuntimeException {
    NotFoundException(String path) {
      super("Not found: " + path);
    }
  }
}
