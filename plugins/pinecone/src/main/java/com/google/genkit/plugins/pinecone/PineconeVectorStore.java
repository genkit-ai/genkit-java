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

package com.google.genkit.plugins.pinecone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.openapitools.db_control.client.model.IndexModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.ai.*;
import com.google.genkit.core.ActionContext;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.VectorWithUnsignedIndices;

/**
 * Pinecone vector store implementation.
 *
 * <p>
 * This class provides indexing and retrieval of documents using Pinecone vector
 * database for similarity search.
 */
public class PineconeVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(PineconeVectorStore.class);

  private final Pinecone pinecone;
  private final PineconeIndexConfig config;
  private final Embedder embedder;
  private Index index;
  private boolean initialized = false;

  /**
   * Creates a new PineconeVectorStore.
   *
   * @param pinecone
   *            the Pinecone client
   * @param config
   *            the index configuration
   * @param embedder
   *            the embedder for generating vectors
   */
  public PineconeVectorStore(Pinecone pinecone, PineconeIndexConfig config, Embedder embedder) {
    this.pinecone = pinecone;
    this.config = config;
    this.embedder = embedder;
  }

  /**
   * Initializes the vector store by connecting to or creating the index.
   */
  public synchronized void initialize() {
    if (initialized) {
      return;
    }

    try {
      // Check if index exists
      IndexModel existingIndex = null;
      try {
        existingIndex = pinecone.describeIndex(config.getIndexName());
      } catch (Exception e) {
        logger.debug("Index {} does not exist: {}", config.getIndexName(), e.getMessage());
      }

      if (existingIndex == null && config.isCreateIndexIfNotExists()) {
        // Create serverless index
        logger.info("Creating Pinecone index: {}", config.getIndexName());
        pinecone.createServerlessIndex(config.getIndexName(), config.getMetric().getValue(),
            config.getDimension(), config.getCloud().getValue(), config.getRegion(), "disabled", // deletionProtection
            null // tags
        );

        // Wait for index to be ready
        waitForIndexReady();
      } else if (existingIndex == null) {
        throw new IllegalStateException(
            "Index " + config.getIndexName() + " does not exist and createIndexIfNotExists is false");
      }

      // Get index connection
      index = pinecone.getIndexConnection(config.getIndexName());

      initialized = true;
      logger.info("Pinecone vector store initialized for index: {}", config.getIndexName());

    } catch (Exception e) {
      logger.error("Error initializing Pinecone vector store: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to initialize Pinecone vector store", e);
    }
  }

  private void waitForIndexReady() throws InterruptedException {
    int maxAttempts = 60; // 5 minutes max
    for (int i = 0; i < maxAttempts; i++) {
      try {
        IndexModel indexModel = pinecone.describeIndex(config.getIndexName());
        if (indexModel.getStatus() != null && indexModel.getStatus().getReady()) {
          logger.info("Index {} is ready", config.getIndexName());
          return;
        }
      } catch (Exception e) {
        logger.debug("Waiting for index to be ready: {}", e.getMessage());
      }
      Thread.sleep(5000); // Wait 5 seconds between checks
    }
    throw new RuntimeException("Timeout waiting for index to be ready");
  }

  /**
   * Retrieves documents similar to the query.
   *
   * @param context
   *            the action context
   * @param request
   *            the retriever request
   * @return the retriever response with matching documents
   */
  public RetrieverResponse retrieve(ActionContext context, RetrieverRequest request) {
    try {
      ensureInitialized();

      // Get query document
      Document queryDoc = request.getQuery();
      if (queryDoc == null) {
        throw new RuntimeException("Query document is required");
      }
      String queryText = queryDoc.text();
      if (queryText == null || queryText.trim().isEmpty()) {
        throw new RuntimeException("Query document has no text content");
      }

      // Generate embedding for query
      List<Float> queryEmbedding = generateEmbedding(context, queryText);

      int topK = request.getOptions() != null && request.getOptions().getK() != null
          ? request.getOptions().getK()
          : 10;

      // Query Pinecone
      QueryResponseWithUnsignedIndices response = index.queryByVector(topK, queryEmbedding, config.getNamespace(),
          null, // filter
          true, // includeValues
          true // includeMetadata
      );

      List<Document> documents = new ArrayList<>();
      if (response.getMatchesList() != null) {
        for (ScoredVectorWithUnsignedIndices match : response.getMatchesList()) {
          Document doc = matchToDocument(match);
          documents.add(doc);
        }
      }

      logger.debug("Retrieved {} documents from index {}", documents.size(), config.getIndexName());

      return new RetrieverResponse(documents);

    } catch (Exception e) {
      logger.error("Error retrieving documents: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to retrieve documents from Pinecone", e);
    }
  }

  /**
   * Indexes documents into the vector store.
   *
   * @param context
   *            the action context
   * @param request
   *            the indexer request
   * @return the indexer response
   */
  public IndexerResponse index(ActionContext context, IndexerRequest request) {
    try {
      ensureInitialized();

      List<Document> documents = request.getDocuments();
      if (documents == null || documents.isEmpty()) {
        logger.warn("No documents to index");
        return new IndexerResponse();
      }

      // Build upsert request
      List<String> ids = new ArrayList<>();
      List<List<Float>> embeddings = new ArrayList<>();
      List<Struct> metadataList = new ArrayList<>();

      for (Document doc : documents) {
        String content = extractContent(doc);
        List<Float> embedding = generateEmbedding(context, content);
        String docId = getOrGenerateId(doc);
        Struct metadata = buildMetadata(doc, content);

        ids.add(docId);
        embeddings.add(embedding);
        metadataList.add(metadata);
      }

      // Batch upsert (Pinecone supports up to 100 vectors per upsert)
      int batchSize = 100;
      int indexed = 0;
      for (int i = 0; i < ids.size(); i += batchSize) {
        int endIdx = Math.min(i + batchSize, ids.size());

        List<VectorWithUnsignedIndices> vectors = new ArrayList<>();
        for (int j = i; j < endIdx; j++) {
          VectorWithUnsignedIndices vector = new VectorWithUnsignedIndices(ids.get(j), embeddings.get(j),
              metadataList.get(j), null // sparseValues
          );
          vectors.add(vector);
        }

        index.upsert(vectors, config.getNamespace());

        indexed += vectors.size();
        logger.debug("Indexed {} documents so far", indexed);
      }

      logger.info("Indexed {} documents into index {}", indexed, config.getIndexName());
      return new IndexerResponse();

    } catch (Exception e) {
      logger.error("Error indexing documents: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to index documents to Pinecone", e);
    }
  }

  private void ensureInitialized() {
    if (!initialized) {
      initialize();
    }
  }

  private List<Float> generateEmbedding(ActionContext ctx, String text) {
    Document doc = new Document(text);
    EmbedRequest embedRequest = new EmbedRequest(List.of(doc));

    EmbedResponse response = embedder.run(ctx, embedRequest);
    if (response.getEmbeddings() == null || response.getEmbeddings().isEmpty()) {
      throw new RuntimeException("Failed to generate embedding for text");
    }

    float[] embedding = response.getEmbeddings().get(0).getValues();
    return floatArrayToList(embedding);
  }

  private List<Float> floatArrayToList(float[] arr) {
    List<Float> list = new ArrayList<>(arr.length);
    for (float f : arr) {
      list.add(f);
    }
    return list;
  }

  private Document matchToDocument(ScoredVectorWithUnsignedIndices match) {
    Map<String, Object> metadata = new HashMap<>();
    String content = "";

    if (match.getMetadata() != null) {
      Struct struct = match.getMetadata();
      for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
        String key = entry.getKey();
        Value value = entry.getValue();
        Object converted = protobufValueToObject(value);

        if (key.equals(config.getTextField()) && converted != null) {
          content = converted.toString();
        } else {
          metadata.put(key, converted);
        }
      }
    }

    // Add score to metadata
    metadata.put("score", match.getScore());
    metadata.put("id", match.getId());

    Document doc = new Document(content);
    doc.setMetadata(metadata);
    return doc;
  }

  private Object protobufValueToObject(Value value) {
    return switch (value.getKindCase()) {
      case STRING_VALUE -> value.getStringValue();
      case NUMBER_VALUE -> value.getNumberValue();
      case BOOL_VALUE -> value.getBoolValue();
      case NULL_VALUE -> null;
      case LIST_VALUE -> value.getListValue().getValuesList().stream().map(this::protobufValueToObject).toList();
      case STRUCT_VALUE -> {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Value> entry : value.getStructValue().getFieldsMap().entrySet()) {
          map.put(entry.getKey(), protobufValueToObject(entry.getValue()));
        }
        yield map;
      }
      default -> value.toString();
    };
  }

  private String extractContent(Document doc) {
    String text = doc.text();
    return text != null ? text : "";
  }

  private Struct buildMetadata(Document doc, String content) {
    Struct.Builder builder = Struct.newBuilder();

    // Add text content
    builder.putFields(config.getTextField(), Value.newBuilder().setStringValue(content).build());

    // Add document metadata
    if (doc.getMetadata() != null) {
      for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
        String key = entry.getKey();
        // Skip 'id' as it's used for the vector ID
        if (!"id".equals(key)) {
          Value value = objectToProtobufValue(entry.getValue());
          if (value != null) {
            builder.putFields(key, value);
          }
        }
      }
    }

    // Add config-level additional metadata
    for (Map.Entry<String, Object> entry : config.getAdditionalMetadata().entrySet()) {
      Value value = objectToProtobufValue(entry.getValue());
      if (value != null) {
        builder.putFields(entry.getKey(), value);
      }
    }

    return builder.build();
  }

  private Value objectToProtobufValue(Object obj) {
    if (obj == null) {
      return Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build();
    } else if (obj instanceof String) {
      return Value.newBuilder().setStringValue((String) obj).build();
    } else if (obj instanceof Number) {
      return Value.newBuilder().setNumberValue(((Number) obj).doubleValue()).build();
    } else if (obj instanceof Boolean) {
      return Value.newBuilder().setBoolValue((Boolean) obj).build();
    } else if (obj instanceof List) {
      com.google.protobuf.ListValue.Builder listBuilder = com.google.protobuf.ListValue.newBuilder();
      for (Object item : (List<?>) obj) {
        Value itemValue = objectToProtobufValue(item);
        if (itemValue != null) {
          listBuilder.addValues(itemValue);
        }
      }
      return Value.newBuilder().setListValue(listBuilder.build()).build();
    } else if (obj instanceof Map) {
      Struct.Builder structBuilder = Struct.newBuilder();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
        String key = entry.getKey().toString();
        Value value = objectToProtobufValue(entry.getValue());
        if (value != null) {
          structBuilder.putFields(key, value);
        }
      }
      return Value.newBuilder().setStructValue(structBuilder.build()).build();
    }
    return Value.newBuilder().setStringValue(obj.toString()).build();
  }

  private String getOrGenerateId(Document doc) {
    if (doc.getMetadata() != null && doc.getMetadata().containsKey("id")) {
      Object id = doc.getMetadata().get("id");
      if (id != null) {
        return id.toString();
      }
    }
    return UUID.randomUUID().toString();
  }

  /**
   * Deletes documents by their IDs.
   *
   * @param ids
   *            the document IDs to delete
   */
  public void deleteByIds(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }

    ensureInitialized();

    index.deleteByIds(ids, config.getNamespace());
    logger.info("Deleted {} documents from index {}", ids.size(), config.getIndexName());
  }

  /**
   * Deletes all documents in the namespace.
   */
  public void deleteAll() {
    ensureInitialized();

    index.deleteAll(config.getNamespace());
    logger.info("Deleted all documents from index {} namespace {}", config.getIndexName(), config.getNamespace());
  }

  /**
   * Gets the index configuration.
   */
  public PineconeIndexConfig getConfig() {
    return config;
  }

  /**
   * Gets the Pinecone index connection.
   */
  public Index getIndex() {
    ensureInitialized();
    return index;
  }
}
