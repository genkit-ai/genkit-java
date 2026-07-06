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

package com.google.genkit.plugins.chroma;

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
 * Chroma vector store backed by the Chroma v2 REST API.
 *
 * <p>Indexes documents (id + text + embedding + metadata) into a Chroma collection and retrieves
 * the nearest neighbors of a query embedding.
 */
public final class ChromaVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(ChromaVectorStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http = HttpClient.newHttpClient();
  private final String baseUrl;
  private final String tenant;
  private final String database;
  private final ChromaCollectionConfig config;
  private final Embedder embedder;

  private volatile String collectionId;

  /**
   * Creates a new store.
   *
   * @param baseUrl the Chroma server base URL (e.g. {@code http://localhost:8000})
   * @param tenant the Chroma tenant
   * @param database the Chroma database
   * @param config the collection configuration
   * @param embedder the embedder used to vectorize documents and queries
   */
  public ChromaVectorStore(
      String baseUrl,
      String tenant,
      String database,
      ChromaCollectionConfig config,
      Embedder embedder) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.tenant = tenant;
    this.database = database;
    this.config = config;
    this.embedder = embedder;
  }

  /** Creates the retriever action registered by the plugin. */
  Retriever createRetriever() {
    String name = ChromaPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Retriever.builder().name(name).handler(this::retrieve).build();
  }

  /** Creates the indexer action registered by the plugin. */
  Indexer createIndexer() {
    String name = ChromaPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Indexer.builder().name(name).handler(this::index).build();
  }

  private String collectionsPath() {
    return "/api/v2/tenants/" + tenant + "/databases/" + database + "/collections";
  }

  private synchronized String ensureCollection() {
    if (collectionId != null) {
      return collectionId;
    }
    ObjectNode body = MAPPER.createObjectNode();
    body.put("name", config.getCollectionName());
    body.put("get_or_create", config.isCreateCollectionIfNotExists());
    ObjectNode metadata = body.putObject("metadata");
    metadata.put("hnsw:space", config.getDistance().getValue());

    JsonNode resp = send("POST", collectionsPath(), body);
    JsonNode id = resp.get("id");
    if (id == null || id.isNull()) {
      throw new RuntimeException(
          "Chroma did not return a collection id for " + config.getCollectionName());
    }
    collectionId = id.asText();
    logger.info("Using Chroma collection {} ({})", config.getCollectionName(), collectionId);
    return collectionId;
  }

  /**
   * Retrieves documents similar to the query.
   *
   * @param context the action context
   * @param request the retriever request
   * @return the retriever response with matching documents
   */
  public RetrieverResponse retrieve(ActionContext context, RetrieverRequest request) {
    Document queryDoc = request.getQuery();
    if (queryDoc == null || queryDoc.text() == null || queryDoc.text().isBlank()) {
      throw new RuntimeException("Query document has no text content");
    }
    int topK =
        request.getOptions() != null && request.getOptions().getK() != null
            ? request.getOptions().getK()
            : 10;
    List<Float> queryEmbedding = generateEmbedding(context, queryDoc.text());
    String id = ensureCollection();

    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode queryEmbeddings = body.putArray("query_embeddings");
    queryEmbeddings.add(floatsToArray(queryEmbedding));
    body.put("n_results", topK);
    ArrayNode include = body.putArray("include");
    include.add("documents");
    include.add("metadatas");
    include.add("distances");

    JsonNode resp = send("POST", collectionsPath() + "/" + id + "/query", body);
    List<Document> documents = new ArrayList<>();
    JsonNode idsOuter = resp.get("ids");
    if (idsOuter == null || !idsOuter.isArray() || idsOuter.isEmpty()) {
      return new RetrieverResponse(documents);
    }
    JsonNode ids = idsOuter.get(0);
    JsonNode docs = firstRow(resp.get("documents"));
    JsonNode metadatas = firstRow(resp.get("metadatas"));
    JsonNode distances = firstRow(resp.get("distances"));
    for (int i = 0; i < ids.size(); i++) {
      String content =
          docs != null && docs.get(i) != null && !docs.get(i).isNull() ? docs.get(i).asText() : "";
      Map<String, Object> metadata = new HashMap<>();
      if (metadatas != null && metadatas.get(i) != null && metadatas.get(i).isObject()) {
        metadata.putAll(MAPPER.convertValue(metadatas.get(i), Map.class));
      }
      metadata.put("id", ids.get(i).asText());
      if (distances != null && distances.get(i) != null && distances.get(i).isNumber()) {
        double distance = distances.get(i).asDouble();
        metadata.put("distance", distance);
        metadata.put("score", 1.0 - distance);
      }
      Document doc = new Document(content);
      doc.setMetadata(metadata);
      documents.add(doc);
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
    List<Document> documents = request.getDocuments();
    if (documents == null || documents.isEmpty()) {
      logger.warn("No documents to index");
      return new IndexerResponse();
    }
    String id = ensureCollection();

    // Batch-generate embeddings for all documents in a single embedder call.
    EmbedResponse embedResponse = embedder.run(context, new EmbedRequest(documents));
    if (embedResponse.getEmbeddings() == null
        || embedResponse.getEmbeddings().size() != documents.size()) {
      throw new RuntimeException("Failed to generate embeddings: mismatched output size");
    }

    ObjectNode body = MAPPER.createObjectNode();
    ArrayNode ids = body.putArray("ids");
    ArrayNode embeddings = body.putArray("embeddings");
    ArrayNode contents = body.putArray("documents");
    ArrayNode metadatas = body.putArray("metadatas");

    for (int i = 0; i < documents.size(); i++) {
      Document doc = documents.get(i);
      String content = doc.text() != null ? doc.text() : "";
      float[] values = embedResponse.getEmbeddings().get(i).getValues();
      List<Float> embedding = new ArrayList<>(values.length);
      for (float v : values) {
        embedding.add(v);
      }
      ids.add(getOrGenerateId(doc));
      embeddings.add(floatsToArray(embedding));
      contents.add(content);

      Map<String, Object> metadata = new HashMap<>();
      if (doc.getMetadata() != null) {
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
          if (!"id".equals(entry.getKey())) {
            metadata.put(entry.getKey(), entry.getValue());
          }
        }
      }
      metadata.putAll(config.getAdditionalMetadata());
      // Chroma rejects empty metadata objects; send null when there is nothing to store.
      if (metadata.isEmpty()) {
        metadatas.addNull();
      } else {
        metadatas.add(MAPPER.valueToTree(metadata));
      }
    }

    send("POST", collectionsPath() + "/" + id + "/add", body);
    logger.info(
        "Indexed {} documents into collection {}", documents.size(), config.getCollectionName());
    return new IndexerResponse();
  }

  private static JsonNode firstRow(JsonNode outer) {
    return (outer != null && outer.isArray() && !outer.isEmpty()) ? outer.get(0) : null;
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
      return doc.getMetadata().get("id").toString();
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
      if (response.statusCode() / 100 != 2) {
        throw new RuntimeException(
            "Chroma request "
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
          "Chroma request " + method + " " + path + " failed: " + e.getMessage(), e);
    }
  }
}
