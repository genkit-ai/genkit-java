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

package com.google.genkit.plugins.weaviate;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.ai.*;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;

import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.batch.model.ObjectGetResponse;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.misc.model.VectorIndexConfig;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.WeaviateClass;

/**
 * Weaviate vector store implementation for RAG workflows.
 *
 * <p>
 * Provides vector similarity search using Weaviate's native vector search
 * capabilities. Supports COSINE, L2_SQUARED, and DOT distance measures.
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * // Retrieve documents
 * RetrieverResponse response = genkit.retrieve("weaviate/my-collection", Document.fromText("What is AI?"),
 * 		Map.of("limit", 5));
 *
 * // Index documents
 * genkit.index("weaviate/my-collection", List.of(Document.fromText("AI is artificial intelligence")));
 * }</pre>
 */
public class WeaviateVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(WeaviateVectorStore.class);

  private final WeaviateClient client;
  private final WeaviateCollectionConfig config;
  private final Embedder embedder;
  private volatile boolean collectionInitialized = false;

  /**
   * Creates a new WeaviateVectorStore.
   *
   * @param client
   *            the Weaviate client
   * @param config
   *            the collection configuration
   * @param embedder
   *            the embedder to use
   */
  public WeaviateVectorStore(WeaviateClient client, WeaviateCollectionConfig config, Embedder embedder) {
    this.client = client;
    this.config = config;
    this.embedder = embedder;
  }

  /**
   * Ensures the Weaviate collection (class) exists, creating it if configured to
   * do so.
   *
   * @throws GenkitException
   *             if collection creation fails
   */
  public void ensureCollectionExists() throws GenkitException {
    if (collectionInitialized || !config.isCreateCollectionIfMissing()) {
      return;
    }

    synchronized (this) {
      if (collectionInitialized) {
        return;
      }

      String className = toClassName(config.getName());

      try {
        // Check if class exists
        Result<Boolean> existsResult = client.schema().exists().withClassName(className).run();

        if (existsResult.hasErrors()) {
          throw new GenkitException(
              "Failed to check if Weaviate class exists: " + existsResult.getError().toString());
        }

        if (Boolean.TRUE.equals(existsResult.getResult())) {
          logger.info("Weaviate class '{}' already exists", className);
          collectionInitialized = true;
          return;
        }

        // Create the class
        logger.info("Creating Weaviate class '{}' with distance measure: {}", className,
            config.getDistanceMeasure());

        // Build properties
        List<Property> properties = new ArrayList<>();
        properties.add(Property.builder().name(config.getContentField()).dataType(List.of("text"))
            .description("Document content").build());
        properties.add(Property.builder().name(config.getMetadataField()).dataType(List.of("text"))
            .description("Document metadata as JSON").build());
        properties.add(Property.builder().name("contentType").dataType(List.of("text"))
            .description("Content type").build());

        // Build class config with vectorizer none (we provide our own vectors)
        VectorIndexConfig vectorIndexConfig = VectorIndexConfig.builder()
            .distance(toWeaviateDistance(config.getDistanceMeasure())).build();

        WeaviateClass weaviateClass = WeaviateClass.builder().className(className)
            .description("Genkit document collection: " + config.getName()).properties(properties)
            .vectorizer("none") // We provide our own vectors
            .vectorIndexConfig(vectorIndexConfig).build();

        Result<Boolean> createResult = client.schema().classCreator().withClass(weaviateClass).run();

        if (createResult.hasErrors()) {
          throw new GenkitException("Failed to create Weaviate class: " + createResult.getError().toString());
        }

        logger.info("Created Weaviate class: {}", className);
        collectionInitialized = true;

      } catch (GenkitException e) {
        throw e;
      } catch (Exception e) {
        throw new GenkitException("Failed to ensure Weaviate collection exists: " + e.getMessage(), e);
      }
    }
  }

  /**
   * Creates a Retriever action for this vector store.
   *
   * @return the Retriever action
   */
  public Retriever createRetriever() {
    String name = WeaviatePlugin.PROVIDER + "/" + config.getName();
    return Retriever.builder().name(name).handler(this::retrieve).build();
  }

  /**
   * Creates an Indexer action for this vector store.
   *
   * @return the Indexer action
   */
  public Indexer createIndexer() {
    String name = WeaviatePlugin.PROVIDER + "/" + config.getName();
    return Indexer.builder().name(name).handler(this::index).build();
  }

  /**
   * Retrieves documents from Weaviate using vector similarity search.
   *
   * @param ctx
   *            the action context
   * @param request
   *            the retriever request
   * @return the retriever response with matched documents
   * @throws GenkitException
   *             if retrieval fails
   */
  public RetrieverResponse retrieve(ActionContext ctx, RetrieverRequest request) throws GenkitException {
    ensureCollectionExists();

    try {
      // Validate query
      Document queryDoc = request.getQuery();
      if (queryDoc == null) {
        throw new GenkitException("Query document is required");
      }

      String queryText = queryDoc.text();
      if (queryText == null || queryText.trim().isEmpty()) {
        throw new GenkitException("Query document has no text content");
      }

      // Get embedding for the query
      EmbedRequest embedRequest = new EmbedRequest(List.of(queryDoc));
      EmbedResponse embedResponse = embedder.run(ctx, embedRequest);

      if (embedResponse.getEmbeddings() == null || embedResponse.getEmbeddings().isEmpty()) {
        throw new GenkitException("Embedder returned no embeddings");
      }

      Float[] queryVector = toFloatObjectArray(embedResponse.getEmbeddings().get(0).getValues());

      // Parse options
      int limit = config.getDefaultLimit();
      Double distanceThreshold = null;
      if (request.getOptions() != null) {
        if (request.getOptions() instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> options = (Map<String, Object>) request.getOptions();
          if (options.containsKey("limit")) {
            limit = ((Number) options.get("limit")).intValue();
          }
          if (options.containsKey("k")) {
            limit = ((Number) options.get("k")).intValue();
          }
          if (options.containsKey("distance")) {
            distanceThreshold = ((Number) options.get("distance")).doubleValue();
          }
        }
      }

      // Build the query
      String className = toClassName(config.getName());

      NearVectorArgument.NearVectorArgumentBuilder nearVectorBuilder = NearVectorArgument.builder()
          .vector(queryVector);

      if (distanceThreshold != null) {
        nearVectorBuilder.distance(distanceThreshold.floatValue());
      }

      Result<GraphQLResponse> result = client.graphQL().get().withClassName(className)
          .withNearVector(nearVectorBuilder.build()).withLimit(limit)
          .withFields(Field.builder().name(config.getContentField()).build(),
              Field.builder().name(config.getMetadataField()).build(),
              Field.builder().name("contentType").build(),
              Field.builder().name("_additional").fields(Field.builder().name("id").build(),
                  Field.builder().name("distance").build()).build())
          .run();

      if (result.hasErrors()) {
        throw new GenkitException("Weaviate query failed: " + result.getError().toString());
      }

      // Parse results
      List<Document> documents = parseGraphQLResponse(result.getResult(), className);

      logger.debug("Retrieved {} documents from collection: {}", documents.size(), config.getName());
      return new RetrieverResponse(documents);

    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("Failed to retrieve documents: " + e.getMessage(), e);
    }
  }

  /**
   * Indexes documents into Weaviate with their embeddings.
   *
   * @param ctx
   *            the action context
   * @param request
   *            the indexer request
   * @return the indexer response
   * @throws GenkitException
   *             if indexing fails
   */
  public IndexerResponse index(ActionContext ctx, IndexerRequest request) throws GenkitException {
    ensureCollectionExists();

    try {
      List<Document> documents = request.getDocuments();
      if (documents == null || documents.isEmpty()) {
        return new IndexerResponse();
      }

      // Get embeddings for all documents
      EmbedRequest embedRequest = new EmbedRequest(documents);
      EmbedResponse embedResponse = embedder.run(ctx, embedRequest);

      List<EmbedResponse.Embedding> embeddings = embedResponse.getEmbeddings();
      if (embeddings.size() != documents.size()) {
        throw new GenkitException(
            "Embedding count mismatch: expected " + documents.size() + ", got " + embeddings.size());
      }

      // Prepare batch of objects
      String className = toClassName(config.getName());
      List<WeaviateObject> objects = new ArrayList<>();

      for (int i = 0; i < documents.size(); i++) {
        Document doc = documents.get(i);
        Float[] embedding = toFloatObjectArray(embeddings.get(i).getValues());

        Map<String, Object> properties = new HashMap<>();
        properties.put(config.getContentField(), doc.text());
        properties.put("contentType", "text/plain");

        // Store metadata as JSON string
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
          properties.put(config.getMetadataField(), mapToJson(doc.getMetadata()));
        } else {
          properties.put(config.getMetadataField(), "{}");
        }

        WeaviateObject obj = WeaviateObject.builder().className(className).properties(properties)
            .vector(embedding).build();

        objects.add(obj);
      }

      // Batch insert
      Result<ObjectGetResponse[]> batchResult = client.batch().objectsBatcher()
          .withObjects(objects.toArray(new WeaviateObject[0])).run();

      if (batchResult.hasErrors()) {
        throw new GenkitException("Weaviate batch insert failed: " + batchResult.getError().toString());
      }

      // Check individual object errors
      ObjectGetResponse[] responses = batchResult.getResult();
      int successCount = 0;
      for (ObjectGetResponse response : responses) {
        if (response.getResult() != null && response.getResult().getErrors() != null) {
          logger.warn("Error inserting object: {}", response.getResult().getErrors());
        } else {
          successCount++;
        }
      }

      logger.info("Indexed {} documents to collection: {}", successCount, config.getName());
      return new IndexerResponse();

    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("Failed to index documents: " + e.getMessage(), e);
    }
  }

  /**
   * Converts collection name to Weaviate class name (must start with uppercase).
   */
  private String toClassName(String name) {
    if (name == null || name.isEmpty()) {
      return "Documents";
    }
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  /**
   * Converts distance measure to Weaviate distance string.
   */
  private String toWeaviateDistance(WeaviateCollectionConfig.DistanceMeasure measure) {
    if (measure == null) {
      return "cosine";
    }
    return switch (measure) {
      case COSINE -> "cosine";
      case L2_SQUARED -> "l2-squared";
      case DOT -> "dot";
    };
  }

  /**
   * Converts float array to Float object array.
   */
  private Float[] toFloatObjectArray(float[] floats) {
    Float[] result = new Float[floats.length];
    for (int i = 0; i < floats.length; i++) {
      result[i] = floats[i];
    }
    return result;
  }

  /**
   * Converts map to JSON string.
   */
  private String mapToJson(Map<String, Object> map) {
    try {
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        if (!first)
          sb.append(",");
        sb.append("\"").append(entry.getKey()).append("\":");
        Object value = entry.getValue();
        if (value instanceof String) {
          sb.append("\"").append(value.toString().replace("\"", "\\\"")).append("\"");
        } else if (value == null) {
          sb.append("null");
        } else {
          sb.append(value.toString());
        }
        first = false;
      }
      sb.append("}");
      return sb.toString();
    } catch (Exception e) {
      return "{}";
    }
  }

  /**
   * Parses JSON string to map.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> jsonToMap(String json) {
    if (json == null || json.isEmpty() || json.equals("{}")) {
      return new HashMap<>();
    }
    try {
      // Simple JSON parser for basic objects
      Map<String, Object> result = new HashMap<>();
      json = json.trim();
      if (json.startsWith("{") && json.endsWith("}")) {
        json = json.substring(1, json.length() - 1);
        if (!json.isEmpty()) {
          // This is a simplified parser - for production use Jackson
          String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
          for (String pair : pairs) {
            String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", 2);
            if (kv.length == 2) {
              String key = kv[0].trim().replaceAll("^\"|\"$", "");
              String value = kv[1].trim();
              if (value.startsWith("\"") && value.endsWith("\"")) {
                result.put(key, value.substring(1, value.length() - 1));
              } else if (value.equals("null")) {
                result.put(key, null);
              } else {
                try {
                  result.put(key, Double.parseDouble(value));
                } catch (NumberFormatException e) {
                  result.put(key, value);
                }
              }
            }
          }
        }
      }
      return result;
    } catch (Exception e) {
      return new HashMap<>();
    }
  }

  /**
   * Parses GraphQL response to documents.
   */
  @SuppressWarnings("unchecked")
  private List<Document> parseGraphQLResponse(GraphQLResponse response, String className) {
    List<Document> documents = new ArrayList<>();

    if (response == null || response.getData() == null) {
      return documents;
    }

    Map<String, Object> data = (Map<String, Object>) response.getData();
    Map<String, Object> getResult = (Map<String, Object>) data.get("Get");

    if (getResult == null) {
      return documents;
    }

    List<Map<String, Object>> results = (List<Map<String, Object>>) getResult.get(className);

    if (results == null) {
      return documents;
    }

    for (Map<String, Object> item : results) {
      String content = (String) item.get(config.getContentField());
      String metadataJson = (String) item.get(config.getMetadataField());

      List<Part> parts = content != null ? List.of(Part.text(content)) : List.of();
      Document doc = new Document(parts);

      // Parse metadata
      Map<String, Object> metadata = jsonToMap(metadataJson);

      // Add additional info
      Map<String, Object> additional = (Map<String, Object>) item.get("_additional");
      if (additional != null) {
        if (additional.containsKey("id")) {
          metadata.put("id", additional.get("id"));
        }
        if (additional.containsKey("distance")) {
          metadata.put("_distance", additional.get("distance"));
        }
      }

      doc.setMetadata(metadata);
      documents.add(doc);
    }

    return documents;
  }
}
