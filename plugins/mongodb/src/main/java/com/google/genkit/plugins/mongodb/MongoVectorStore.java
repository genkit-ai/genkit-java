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

package com.google.genkit.plugins.mongodb;

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
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.SearchIndexType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB Atlas Vector Search-backed vector store.
 *
 * <p>Indexes documents into a collection with an embedding field and retrieves them with the {@code
 * $vectorSearch} aggregation stage. Requires an Atlas Vector Search index; use the {@code
 * mongodb/mongodb-atlas-local} Docker image for local development or a MongoDB Atlas cluster.
 */
public final class MongoVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(MongoVectorStore.class);
  private static final String SCORE_FIELD = "__score";
  private static final int INDEX_READY_TIMEOUT_SECONDS = 120;

  private final MongoCollection<org.bson.Document> collection;
  private final MongoVectorStoreConfig config;
  private final Embedder embedder;
  private final MongoDatabase database;
  private boolean initialized = false;

  /**
   * Creates a new store.
   *
   * @param client the MongoDB client
   * @param config the collection configuration
   * @param embedder the embedder used to vectorize documents and queries
   */
  public MongoVectorStore(MongoClient client, MongoVectorStoreConfig config, Embedder embedder) {
    this.config = config;
    this.embedder = embedder;
    this.database = client.getDatabase(config.getDatabaseName());
    this.collection = database.getCollection(config.getCollectionName());
  }

  /** Creates the retriever action registered by the plugin. */
  Retriever createRetriever() {
    String name = MongoPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Retriever.builder().name(name).handler(this::retrieve).build();
  }

  /** Creates the indexer action registered by the plugin. */
  Indexer createIndexer() {
    String name = MongoPlugin.PLUGIN_NAME + "/" + config.getCollectionName();
    return Indexer.builder().name(name).handler(this::index).build();
  }

  private synchronized void ensureInitialized() {
    if (initialized) {
      return;
    }
    if (config.isCreateIndexIfNotExists()) {
      ensureCollection();
      ensureVectorIndex();
    }
    initialized = true;
    logger.info("MongoDB vector store initialized for collection: {}", config.getCollectionName());
  }

  private void ensureCollection() {
    boolean exists = false;
    for (String name : database.listCollectionNames()) {
      if (name.equals(config.getCollectionName())) {
        exists = true;
        break;
      }
    }
    if (!exists) {
      database.createCollection(config.getCollectionName());
    }
  }

  private void ensureVectorIndex() {
    if (!searchIndexExists()) {
      Bson definition =
          new org.bson.Document(
              "fields",
              List.of(
                  new org.bson.Document("type", "vector")
                      .append("path", config.getEmbeddingField())
                      .append("numDimensions", resolveDimension())
                      .append("similarity", config.getSimilarity().getValue())));
      createSearchIndexWithRetry(definition);
      logger.info("Creating Atlas Vector Search index: {}", config.getIndexName());
    }
    waitForIndexReady();
  }

  /**
   * Resolves the embedding dimension by probing the embedder, falling back to the configured
   * dimension if the probe fails. This keeps the created index in sync with whatever embedding
   * model is wired in.
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

  /**
   * Returns whether the configured vector index already exists, retrying while the Atlas Search
   * service ({@code mongot}) is still starting up (error 125). The service can lag behind {@code
   * mongod} readiness, especially with the local Atlas image.
   */
  private boolean searchIndexExists() {
    for (org.bson.Document idx : listSearchIndexesWithRetry()) {
      if (config.getIndexName().equals(idx.getString("name"))) {
        return true;
      }
    }
    return false;
  }

  private void createSearchIndexWithRetry(Bson definition) {
    long deadline = System.currentTimeMillis() + INDEX_READY_TIMEOUT_SECONDS * 1000L;
    while (true) {
      try {
        collection.createSearchIndexes(
            List.of(
                new SearchIndexModel(
                    config.getIndexName(), definition, SearchIndexType.vectorSearch())));
        return;
      } catch (MongoCommandException e) {
        if (isSearchServiceUnavailable(e) && System.currentTimeMillis() < deadline) {
          logger.info("Waiting for Atlas Search service before creating the vector index...");
          sleep(2000);
          continue;
        }
        throw e;
      }
    }
  }

  private List<org.bson.Document> listSearchIndexesWithRetry() {
    long deadline = System.currentTimeMillis() + INDEX_READY_TIMEOUT_SECONDS * 1000L;
    while (true) {
      try {
        List<org.bson.Document> out = new java.util.ArrayList<>();
        for (org.bson.Document idx : collection.listSearchIndexes()) {
          out.add(idx);
        }
        return out;
      } catch (MongoCommandException e) {
        if (isSearchServiceUnavailable(e) && System.currentTimeMillis() < deadline) {
          logger.info("Waiting for Atlas Search service to become available...");
          sleep(2000);
          continue;
        }
        throw e;
      }
    }
  }

  private static boolean isSearchServiceUnavailable(MongoCommandException e) {
    return e.getErrorCode() == 125
        || (e.getErrorMessage() != null
            && e.getErrorMessage().contains("Search Index Management service"));
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for the Atlas Search service", e);
    }
  }

  private void waitForIndexReady() {
    long deadline = System.currentTimeMillis() + INDEX_READY_TIMEOUT_SECONDS * 1000L;
    while (System.currentTimeMillis() < deadline) {
      for (org.bson.Document idx : listSearchIndexesWithRetry()) {
        if (config.getIndexName().equals(idx.getString("name"))
            && Boolean.TRUE.equals(idx.getBoolean("queryable"))) {
          logger.info("Atlas Vector Search index {} is queryable", config.getIndexName());
          return;
        }
      }
      sleep(2000);
    }
    throw new RuntimeException(
        "Timed out waiting for Atlas Vector Search index " + config.getIndexName());
  }

  /**
   * Retrieves documents similar to the query using the {@code $vectorSearch} aggregation stage.
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
    List<Double> queryVector = generateEmbedding(context, queryDoc.text());
    int numCandidates = Math.max(config.getNumCandidates(), topK * 10);

    List<Bson> pipeline =
        List.of(
            new org.bson.Document(
                "$vectorSearch",
                new org.bson.Document("index", config.getIndexName())
                    .append("path", config.getEmbeddingField())
                    .append("queryVector", queryVector)
                    .append("numCandidates", numCandidates)
                    .append("limit", topK)),
            new org.bson.Document(
                "$addFields",
                new org.bson.Document(
                    SCORE_FIELD, new org.bson.Document("$meta", "vectorSearchScore"))));

    List<Document> documents = new ArrayList<>();
    for (org.bson.Document result : collection.aggregate(pipeline)) {
      documents.add(toDocument(result));
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
    int indexed = 0;
    for (Document doc : documents) {
      String content = doc.text() != null ? doc.text() : "";
      List<Double> embedding = generateEmbedding(context, content);
      String id = getOrGenerateId(doc);

      org.bson.Document stored = new org.bson.Document("_id", id);
      stored.put(config.getTextField(), content);
      stored.put(config.getEmbeddingField(), embedding);
      if (doc.getMetadata() != null) {
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
          if (!"id".equals(entry.getKey())) {
            stored.put(entry.getKey(), entry.getValue());
          }
        }
      }
      for (Map.Entry<String, Object> entry : config.getAdditionalMetadata().entrySet()) {
        stored.put(entry.getKey(), entry.getValue());
      }
      collection.replaceOne(Filters.eq("_id", id), stored, new ReplaceOptions().upsert(true));
      indexed++;
    }
    logger.info("Indexed {} documents into collection {}", indexed, config.getCollectionName());
    return new IndexerResponse();
  }

  private Document toDocument(org.bson.Document result) {
    Map<String, Object> metadata = new HashMap<>();
    String content = "";
    for (Map.Entry<String, Object> entry : result.entrySet()) {
      String key = entry.getKey();
      if (key.equals(config.getTextField())) {
        content = entry.getValue() != null ? entry.getValue().toString() : "";
      } else if (key.equals(config.getEmbeddingField()) || key.equals(SCORE_FIELD)) {
        continue;
      } else if (key.equals("_id")) {
        metadata.put("id", entry.getValue() != null ? entry.getValue().toString() : null);
      } else {
        metadata.put(key, entry.getValue());
      }
    }
    Object score = result.get(SCORE_FIELD);
    if (score instanceof Number number) {
      metadata.put("score", number.doubleValue());
    }
    Document doc = new Document(content);
    doc.setMetadata(metadata);
    return doc;
  }

  private List<Double> generateEmbedding(ActionContext ctx, String text) {
    EmbedResponse response = embedder.run(ctx, new EmbedRequest(List.of(new Document(text))));
    if (response.getEmbeddings() == null || response.getEmbeddings().isEmpty()) {
      throw new RuntimeException("Failed to generate embedding for text");
    }
    float[] values = response.getEmbeddings().get(0).getValues();
    List<Double> out = new ArrayList<>(values.length);
    for (float v : values) {
      out.add((double) v);
    }
    return out;
  }

  private String getOrGenerateId(Document doc) {
    if (doc.getMetadata() != null && doc.getMetadata().get("id") != null) {
      return doc.getMetadata().get("id").toString();
    }
    return UUID.randomUUID().toString();
  }
}
