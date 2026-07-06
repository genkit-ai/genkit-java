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

package com.google.genkit.plugins.milvus;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milvus vector store backed by the Milvus v2 REST API.
 *
 * <p>Indexes documents into a Milvus collection (quick-setup mode: auto id, a {@code vector} field,
 * dynamic {@code text} and {@code metadata} fields) and retrieves the nearest neighbors of a query
 * embedding.
 */
public final class MilvusVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TEXT_FIELD = "text";
  private static final String METADATA_FIELD = "metadata";
  private static final String VECTOR_FIELD = "vector";

  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;
  private final String token;
  private final MilvusCollectionConfig config;
  private final Embedder embedder;

  private volatile boolean initialized = false;

  /**
   * Creates a new store.
   *
   * @param baseUrl the Milvus server base URL (e.g. {@code http://localhost:19530})
   * @param token the Milvus auth token, or {@code null} when the server requires none
   * @param config the collection configuration
   * @param embedder the embedder used to vectorize documents and queries
   */
  public MilvusVectorStore(
      String baseUrl, String token, MilvusCollectionConfig config, Embedder embedder) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.token = token;
    this.config = config;
    this.embedder = embedder;
  }

  /** Creates the retriever action registered by the plugin. */
  Retriever createRetriever() {
    String name = MilvusPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Retriever.builder().name(name).handler(this::retrieve).build();
  }

  /** Creates the indexer action registered by the plugin. */
  Indexer createIndexer() {
    String name = MilvusPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Indexer.builder().name(name).handler(this::index).build();
  }

  private synchronized void ensureInitialized() {
    if (initialized) {
      return;
    }
    if (config.isCreateCollectionIfNotExists() && !collectionExists()) {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("collectionName", config.getCollectionName());
      body.put("dimension", resolveDimension());
      body.put("metricType", config.getMetric().getValue());
      // Let Milvus generate the Int64 primary key so callers don't have to supply one.
      body.put("autoID", true);
      send("/v2/vectordb/collections/create", body);
      logger.info("Created Milvus collection {}", config.getCollectionName());
    }
    initialized = true;
  }

  private boolean collectionExists() {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("collectionName", config.getCollectionName());
    JsonNode data = send("/v2/vectordb/collections/has", body).get("data");
    return data != null && data.has("has") && data.get("has").asBoolean();
  }

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
    body.put("collectionName", config.getCollectionName());
    body.put("annsField", VECTOR_FIELD);
    body.put("limit", topK);
    ArrayNode data = body.putArray("data");
    data.add(floatsToArray(queryEmbedding));
    ArrayNode outputFields = body.putArray("outputFields");
    outputFields.add(TEXT_FIELD);
    outputFields.add(METADATA_FIELD);

    JsonNode resp = send("/v2/vectordb/entities/search", body);
    List<Document> documents = new ArrayList<>();
    JsonNode results = resp.get("data");
    if (results != null && results.isArray()) {
      for (JsonNode hit : results) {
        documents.add(toDocument(hit));
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

    // Batch-generate embeddings for all documents in a single embedder call.
    EmbedResponse embedResponse = embedder.run(context, new EmbedRequest(documents));
    if (embedResponse.getEmbeddings() == null
        || embedResponse.getEmbeddings().size() != documents.size()) {
      throw new RuntimeException("Failed to generate embeddings: mismatched output size");
    }

    ObjectNode body = MAPPER.createObjectNode();
    body.put("collectionName", config.getCollectionName());
    ArrayNode data = body.putArray("data");
    for (int i = 0; i < documents.size(); i++) {
      Document doc = documents.get(i);
      String content = doc.text() != null ? doc.text() : "";
      float[] values = embedResponse.getEmbeddings().get(i).getValues();
      List<Float> embedding = new ArrayList<>(values.length);
      for (float v : values) {
        embedding.add(v);
      }

      ObjectNode entity = data.addObject();
      entity.set(VECTOR_FIELD, floatsToArray(embedding));
      entity.put(TEXT_FIELD, content);

      Map<String, Object> metadata = new HashMap<>();
      if (doc.getMetadata() != null) {
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
          if (!"id".equals(entry.getKey())) {
            metadata.put(entry.getKey(), entry.getValue());
          }
        }
      }
      metadata.putAll(config.getAdditionalMetadata());
      entity.put(METADATA_FIELD, writeJson(metadata));
    }

    send("/v2/vectordb/entities/insert", body);
    logger.info(
        "Indexed {} documents into collection {}", documents.size(), config.getCollectionName());
    return new IndexerResponse();
  }

  private Document toDocument(JsonNode hit) {
    Map<String, Object> metadata = new HashMap<>();
    String content = "";
    JsonNode textNode = hit.get(TEXT_FIELD);
    if (textNode != null && !textNode.isNull()) {
      content = textNode.asText();
    }
    JsonNode metadataNode = hit.get(METADATA_FIELD);
    if (metadataNode != null && !metadataNode.isNull()) {
      try {
        String raw = metadataNode.isTextual() ? metadataNode.asText() : metadataNode.toString();
        if (raw != null && !raw.isBlank()) {
          @SuppressWarnings("unchecked")
          Map<String, Object> parsed = MAPPER.readValue(raw, Map.class);
          metadata.putAll(parsed);
        }
      } catch (Exception e) {
        logger.debug("Failed to parse Milvus metadata: {}", e.getMessage());
      }
    }
    if (hit.get("id") != null) {
      metadata.put("id", hit.get("id").asText());
    }
    if (hit.get("distance") != null && hit.get("distance").isNumber()) {
      double distance = hit.get("distance").asDouble();
      metadata.put("distance", distance);
      metadata.put("score", distance);
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

  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize metadata: " + e.getMessage(), e);
    }
  }

  /** Posts a JSON request to the Milvus REST API and validates both the HTTP and Milvus codes. */
  private JsonNode send(String path, JsonNode body) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + path))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json");
      if (token != null && !token.isBlank()) {
        builder.header("Authorization", "Bearer " + token);
      }
      builder.POST(
          HttpRequest.BodyPublishers.ofString(
              MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new RuntimeException(
            "Milvus request "
                + path
                + " failed ("
                + response.statusCode()
                + "): "
                + response.body());
      }
      JsonNode node = MAPPER.readTree(response.body());
      JsonNode code = node.get("code");
      if (code != null && code.asInt() != 0) {
        throw new RuntimeException(
            "Milvus request " + path + " failed (code " + code.asInt() + "): " + node.toString());
      }
      return node;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Milvus request " + path + " failed: " + e.getMessage(), e);
    }
  }
}
