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

package com.google.genkit.plugins.firebase.retriever;

import java.util.*;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.firestore.*;
import com.google.cloud.firestore.v1.FirestoreAdminClient;
import com.google.firestore.admin.v1.CreateDatabaseRequest;
import com.google.firestore.admin.v1.CreateIndexRequest;
import com.google.firestore.admin.v1.Database;
import com.google.firestore.admin.v1.GetDatabaseRequest;
import com.google.firestore.admin.v1.Index;
import com.google.firestore.admin.v1.ListIndexesRequest;
import com.google.firestore.admin.v1.ProjectName;
import com.google.genkit.ai.*;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import com.google.genkit.plugins.firebase.FirebasePlugin;

/**
 * Firestore vector store implementation for RAG workflows.
 * 
 * <p>
 * Provides vector similarity search using Cloud Firestore's native vector
 * search capabilities. Supports COSINE, EUCLIDEAN, and DOT_PRODUCT distance
 * measures.
 * 
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * // Retrieve documents
 * RetrieverResponse response = genkit.retrieve("firebase/my-docs", Document.fromText("What is the meaning of life?"),
 * 		Map.of("limit", 5));
 * 
 * // Index documents
 * genkit.index("firebase/my-docs",
 * 		List.of(Document.fromText("The meaning of life is 42"), Document.fromText("Life is what you make of it")));
 * }</pre>
 */
public class FirestoreVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(FirestoreVectorStore.class);

  private final Firestore firestore;
  private final FirestoreRetrieverConfig config;
  private final Embedder embedder;
  private final String projectId;
  private volatile boolean databaseInitialized = false;
  private volatile boolean vectorIndexInitialized = false;

  /**
   * Creates a new FirestoreVectorStore.
   *
   * @param firestore
   *            the Firestore instance
   * @param config
   *            the retriever configuration
   * @param embedder
   *            the embedder to use
   */
  public FirestoreVectorStore(Firestore firestore, FirestoreRetrieverConfig config, Embedder embedder) {
    this.firestore = firestore;
    this.config = config;
    this.embedder = embedder;
    this.projectId = extractProjectId(firestore);
  }

  /**
   * Creates a new FirestoreVectorStore with explicit project ID.
   *
   * @param firestore
   *            the Firestore instance
   * @param config
   *            the retriever configuration
   * @param embedder
   *            the embedder to use
   * @param projectId
   *            the Google Cloud project ID
   */
  public FirestoreVectorStore(Firestore firestore, FirestoreRetrieverConfig config, Embedder embedder,
      String projectId) {
    this.firestore = firestore;
    this.config = config;
    this.embedder = embedder;
    this.projectId = projectId;
  }

  /**
   * Extracts project ID from Firestore instance options.
   */
  private String extractProjectId(Firestore firestore) {
    try {
      FirestoreOptions options = firestore.getOptions();
      return options.getProjectId();
    } catch (Exception e) {
      logger.warn("Could not extract project ID from Firestore options: {}", e.getMessage());
      return System.getenv("GCLOUD_PROJECT");
    }
  }

  /**
   * Ensures the Firestore database exists, creating it if configured to do so.
   *
   * @throws GenkitException
   *             if database creation fails
   */
  public void ensureDatabaseExists() throws GenkitException {
    if (databaseInitialized || !config.isCreateDatabaseIfNotExists()) {
      return;
    }

    synchronized (this) {
      if (databaseInitialized) {
        return;
      }

      String databaseId = config.getDatabaseId();

      try (FirestoreAdminClient adminClient = FirestoreAdminClient.create()) {
        // Check if database exists
        String databaseName = String.format("projects/%s/databases/%s", projectId, databaseId);

        try {
          GetDatabaseRequest getRequest = GetDatabaseRequest.newBuilder().setName(databaseName).build();
          Database existingDb = adminClient.getDatabase(getRequest);
          logger.info("Firestore database '{}' already exists", databaseId);
          databaseInitialized = true;
          return;
        } catch (NotFoundException e) {
          logger.info("Firestore database '{}' not found, creating...", databaseId);
        }

        // Create the database
        CreateDatabaseRequest createRequest = CreateDatabaseRequest.newBuilder()
            .setParent(ProjectName.of(projectId).toString()).setDatabaseId(databaseId)
            .setDatabase(Database.newBuilder().setType(Database.DatabaseType.FIRESTORE_NATIVE)
                .setLocationId("nam5") // Multi-region US
                .build())
            .build();

        try {
          Database database = adminClient.createDatabaseAsync(createRequest).get();
          logger.info("Created Firestore database: {}", database.getName());
          databaseInitialized = true;
        } catch (AlreadyExistsException e) {
          // Another thread or process created the database
          logger.info("Firestore database '{}' was created concurrently", databaseId);
          databaseInitialized = true;
        }

      } catch (Exception e) {
        throw new GenkitException("Failed to ensure Firestore database exists: " + e.getMessage(), e);
      }
    }
  }

  /**
   * Ensures the Firestore vector index exists, creating it if configured to do
   * so.
   *
   * @throws GenkitException
   *             if index creation fails
   */
  public void ensureVectorIndexExists() throws GenkitException {
    if (vectorIndexInitialized || !config.isCreateVectorIndexIfNotExists()) {
      return;
    }

    synchronized (this) {
      if (vectorIndexInitialized) {
        return;
      }

      String collectionName = config.getCollection();
      String vectorField = config.getVectorField();
      String databaseId = config.getDatabaseId();
      int dimension = config.getEmbedderDimension();

      if (collectionName == null || collectionName.isEmpty()) {
        logger.warn("Collection name not configured, skipping vector index creation");
        vectorIndexInitialized = true;
        return;
      }

      try (FirestoreAdminClient adminClient = FirestoreAdminClient.create()) {
        String parent = String.format("projects/%s/databases/%s/collectionGroups/%s", projectId, databaseId,
            collectionName);

        // Check if a vector index already exists for this field
        boolean indexExists = false;
        try {
          ListIndexesRequest listRequest = ListIndexesRequest.newBuilder().setParent(parent).build();

          for (Index existingIndex : adminClient.listIndexes(listRequest).iterateAll()) {
            for (Index.IndexField field : existingIndex.getFieldsList()) {
              if (field.getFieldPath().equals(vectorField) && field.hasVectorConfig()) {
                logger.info("Vector index already exists for field '{}' in collection '{}'",
                    vectorField, collectionName);
                indexExists = true;
                break;
              }
            }
            if (indexExists)
              break;
          }
        } catch (NotFoundException e) {
          // Collection group doesn't exist yet, which is fine
          logger.debug("Collection group '{}' not found, index will be created on first write",
              collectionName);
        }

        if (!indexExists) {
          // Create the vector index
          logger.info("Creating vector index for field '{}' in collection '{}' with dimension {}",
              vectorField, collectionName, dimension);

          Index.IndexField vectorIndexField = Index.IndexField.newBuilder().setFieldPath(vectorField)
              .setVectorConfig(Index.IndexField.VectorConfig.newBuilder().setDimension(dimension)
                  .setFlat(Index.IndexField.VectorConfig.FlatIndex.getDefaultInstance()).build())
              .build();

          Index index = Index.newBuilder().setQueryScope(Index.QueryScope.COLLECTION)
              .addFields(vectorIndexField).build();

          CreateIndexRequest createRequest = CreateIndexRequest.newBuilder().setParent(parent).setIndex(index)
              .build();

          try {
            Index createdIndex = adminClient.createIndexAsync(createRequest).get();
            logger.info("Created vector index: {}", createdIndex.getName());
          } catch (AlreadyExistsException e) {
            logger.info("Vector index was created concurrently");
          } catch (Exception e) {
            // Index creation might fail if collection doesn't exist yet
            // Log warning but don't fail - index can be created later
            logger.warn("Could not create vector index: {}. You may need to create it manually: "
                + "gcloud firestore indexes composite create --project={} --collection-group={} "
                + "--query-scope=COLLECTION --field-config=vector-config='{{\"dimension\":\"{}\",\"flat\": \"{{}}\"}}',field-path={}",
                e.getMessage(), projectId, collectionName, dimension, vectorField);
          }
        }

        vectorIndexInitialized = true;

      } catch (Exception e) {
        // Don't fail on index creation errors - just log warning
        logger.warn("Failed to ensure vector index exists: {}. You may need to create it manually.",
            e.getMessage());
        vectorIndexInitialized = true; // Mark as initialized to avoid repeated attempts
      }
    }
  }

  /**
   * Creates a Retriever action for this vector store.
   *
   * @return the Retriever action
   */
  public Retriever createRetriever() {
    String name = FirebasePlugin.PROVIDER + "/" + config.getName();

    return Retriever.builder().name(name).handler(this::retrieve).build();
  }

  /**
   * Creates an Indexer action for this vector store.
   *
   * @return the Indexer action
   */
  public Indexer createIndexer() {
    String name = FirebasePlugin.PROVIDER + "/" + config.getName();

    return Indexer.builder().name(name).handler(this::index).build();
  }

  /**
   * Retrieves documents from Firestore using vector similarity search.
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
    // Ensure database and vector index exist before first operation
    ensureDatabaseExists();
    ensureVectorIndexExists();

    try {
      // Validate query
      Document queryDoc = request.getQuery();
      if (queryDoc == null) {
        throw new GenkitException("Query document is required");
      }

      String queryText = queryDoc.text();
      logger.debug("Query document text: '{}', content: {}", queryText, queryDoc.getContent());

      if (queryText == null || queryText.trim().isEmpty()) {
        throw new GenkitException("Query document has no text content. Please provide a non-empty query.");
      }

      // Get embedding for the query
      EmbedRequest embedRequest = new EmbedRequest(List.of(queryDoc));
      logger.debug("Calling embedder with document: {}", queryText);
      EmbedResponse embedResponse = embedder.run(ctx, embedRequest);
      logger.debug("Embedder response: embeddings={}",
          embedResponse.getEmbeddings() != null ? embedResponse.getEmbeddings().size() : "null");

      if (embedResponse.getEmbeddings() == null || embedResponse.getEmbeddings().isEmpty()) {
        throw new GenkitException("Embedder returned no embeddings");
      }

      double[] queryEmbedding = toDoubleArray(embedResponse.getEmbeddings().get(0).getValues());

      // Parse options
      RetrieveOptions options = parseRetrieveOptions(request.getOptions());

      // Build the query
      String collectionName = options.collection != null ? options.collection : config.getCollection();
      if (collectionName == null) {
        throw new GenkitException("Collection name is required");
      }

      CollectionReference collection = firestore.collection(collectionName);

      // Convert distance measure
      VectorQuery.DistanceMeasure distanceMeasure = toFirestoreDistanceMeasure(
          options.distanceMeasure != null ? options.distanceMeasure : config.getDistanceMeasure());

      // Create vector query
      VectorQuerySnapshot querySnapshot = executeVectorQuery(collection, queryEmbedding, options.limit,
          distanceMeasure, options.where, options.distanceThreshold);

      // Convert results to documents
      List<Document> documents = new ArrayList<>();
      for (QueryDocumentSnapshot doc : querySnapshot.getDocuments()) {
        Document resultDoc = convertToDocument(doc, querySnapshot);
        documents.add(resultDoc);
      }

      logger.debug("Retrieved {} documents from collection: {}", documents.size(), collectionName);
      return new RetrieverResponse(documents);

    } catch (Exception e) {
      throw new GenkitException("Failed to retrieve documents: " + e.getMessage(), e);
    }
  }

  /**
   * Indexes documents into Firestore with their embeddings.
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
    // Ensure database and vector index exist before first operation
    ensureDatabaseExists();
    ensureVectorIndexExists();

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

      // Get collection
      String collectionName = config.getCollection();
      if (collectionName == null) {
        throw new GenkitException("Collection name is required for indexing");
      }

      // Index each document with its embedding
      WriteBatch batch = firestore.batch();
      List<String> indexedIds = new ArrayList<>();

      for (int i = 0; i < documents.size(); i++) {
        Document doc = documents.get(i);
        double[] embedding = toDoubleArray(embeddings.get(i).getValues());

        // Create document data
        Map<String, Object> data = new HashMap<>();

        // Add content
        data.put(config.getContentField(), doc.text());

        // Add embedding as vector
        data.put(config.getVectorField(), FieldValue.vector(embedding));

        // Add metadata if present
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
          data.putAll(doc.getMetadata());
        }

        // Create or update document
        DocumentReference docRef = firestore.collection(collectionName).document();
        batch.set(docRef, data);
        indexedIds.add(docRef.getId());
      }

      // Commit batch
      batch.commit().get();

      logger.info("Indexed {} documents to collection: {}", documents.size(), collectionName);
      return new IndexerResponse();

    } catch (Exception e) {
      throw new GenkitException("Failed to index documents: " + e.getMessage(), e);
    }
  }

  /**
   * Executes a vector similarity query.
   */
  private VectorQuerySnapshot executeVectorQuery(CollectionReference collection, double[] embedding, int limit,
      VectorQuery.DistanceMeasure distanceMeasure, Map<String, Object> whereFilters, Double distanceThreshold)
      throws ExecutionException, InterruptedException {

    // Start building the query
    Query query = collection;

    // Apply where filters
    if (whereFilters != null && !whereFilters.isEmpty()) {
      for (Map.Entry<String, Object> entry : whereFilters.entrySet()) {
        query = query.whereEqualTo(entry.getKey(), entry.getValue());
      }
    }

    // Build vector query using findNearest
    VectorQuery vectorQuery = query.findNearest(config.getVectorField(), embedding, limit, distanceMeasure);

    ApiFuture<VectorQuerySnapshot> future = vectorQuery.get();
    return future.get();
  }

  /**
   * Converts a Firestore document to a Genkit Document.
   */
  private Document convertToDocument(QueryDocumentSnapshot firestoreDoc, VectorQuerySnapshot snapshot) {
    // Extract content
    List<Part> content;
    if (config.getContentExtractor() != null) {
      content = config.getContentExtractor().apply(firestoreDoc);
    } else {
      String text = firestoreDoc.getString(config.getContentField());
      content = text != null ? List.of(Part.text(text)) : List.of();
    }

    // Extract metadata
    Map<String, Object> metadata = new HashMap<>();

    if (config.getMetadataExtractor() != null) {
      metadata = config.getMetadataExtractor().apply(firestoreDoc);
    } else if (config.getMetadataFields() != null) {
      for (String field : config.getMetadataFields()) {
        Object value = firestoreDoc.get(field);
        if (value != null) {
          metadata.put(field, value);
        }
      }
    } else {
      // Include all fields except vector field
      for (Map.Entry<String, Object> entry : firestoreDoc.getData().entrySet()) {
        if (!entry.getKey().equals(config.getVectorField())) {
          metadata.put(entry.getKey(), entry.getValue());
        }
      }
    }

    // Add document ID to metadata
    metadata.put("id", firestoreDoc.getId());

    // Add distance if configured
    if (config.getDistanceResultField() != null) {
      // Note: Getting distance from Firestore vector query requires accessing
      // the distance value from the result, which may vary by SDK version
      // This is a placeholder - actual implementation depends on SDK support
    }

    Document doc = new Document(content);
    doc.setMetadata(metadata);
    return doc;
  }

  /**
   * Parses retrieve options from the request.
   */
  private RetrieveOptions parseRetrieveOptions(Object options) {
    RetrieveOptions result = new RetrieveOptions();
    result.limit = config.getDefaultLimit();
    result.distanceThreshold = config.getDistanceThreshold();
    result.distanceMeasure = config.getDistanceMeasure();

    if (options == null) {
      return result;
    }

    if (options instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> optionsMap = (Map<String, Object>) options;

      if (optionsMap.containsKey("limit")) {
        result.limit = ((Number) optionsMap.get("limit")).intValue();
      }
      if (optionsMap.containsKey("k")) {
        result.limit = ((Number) optionsMap.get("k")).intValue();
      }
      if (optionsMap.containsKey("where")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> where = (Map<String, Object>) optionsMap.get("where");
        result.where = where;
      }
      if (optionsMap.containsKey("collection")) {
        result.collection = (String) optionsMap.get("collection");
      }
      if (optionsMap.containsKey("distanceMeasure")) {
        String measure = (String) optionsMap.get("distanceMeasure");
        result.distanceMeasure = FirestoreRetrieverConfig.DistanceMeasure.valueOf(measure);
      }
      if (optionsMap.containsKey("distanceThreshold")) {
        result.distanceThreshold = ((Number) optionsMap.get("distanceThreshold")).doubleValue();
      }
    }

    return result;
  }

  /**
   * Converts config distance measure to Firestore distance measure.
   */
  private VectorQuery.DistanceMeasure toFirestoreDistanceMeasure(FirestoreRetrieverConfig.DistanceMeasure measure) {
    if (measure == null) {
      return VectorQuery.DistanceMeasure.COSINE;
    }
    return switch (measure) {
      case COSINE -> VectorQuery.DistanceMeasure.COSINE;
      case EUCLIDEAN -> VectorQuery.DistanceMeasure.EUCLIDEAN;
      case DOT_PRODUCT -> VectorQuery.DistanceMeasure.DOT_PRODUCT;
    };
  }

  /**
   * Converts float array to double array.
   */
  private double[] toDoubleArray(float[] floats) {
    double[] doubles = new double[floats.length];
    for (int i = 0; i < floats.length; i++) {
      doubles[i] = floats[i];
    }
    return doubles;
  }

  /**
   * Internal class to hold parsed retrieve options.
   */
  private static class RetrieveOptions {
    int limit;
    Map<String, Object> where;
    String collection;
    FirestoreRetrieverConfig.DistanceMeasure distanceMeasure;
    Double distanceThreshold;
  }
}
