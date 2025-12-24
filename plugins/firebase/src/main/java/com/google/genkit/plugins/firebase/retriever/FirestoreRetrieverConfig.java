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

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.genkit.ai.Embedder;
import com.google.genkit.ai.Part;

/**
 * Configuration for a Firestore vector search retriever.
 * 
 * <p>
 * Example:
 * 
 * <pre>{@code
 * FirestoreRetrieverConfig config = FirestoreRetrieverConfig.builder().name("my-docs").collection("documents")
 * 		.embedderName("googleai/text-embedding-004").vectorField("embedding").contentField("content")
 * 		.distanceMeasure(DistanceMeasure.COSINE).build();
 * }</pre>
 */
public class FirestoreRetrieverConfig {

  /**
   * Distance measure options for vector similarity search.
   */
  public enum DistanceMeasure {
    /** Cosine similarity (default). */
    COSINE,
    /** Euclidean distance. */
    EUCLIDEAN,
    /** Dot product. */
    DOT_PRODUCT
  }

  private final String name;
  private final String label;
  private final String collection;
  private final String vectorField;
  private final String contentField;
  private final Function<QueryDocumentSnapshot, List<Part>> contentExtractor;
  private final DistanceMeasure distanceMeasure;
  private final Double distanceThreshold;
  private final String distanceResultField;
  private final Embedder embedder;
  private final String embedderName;
  private final List<String> metadataFields;
  private final Function<QueryDocumentSnapshot, Map<String, Object>> metadataExtractor;
  private final int defaultLimit;
  private final boolean createDatabaseIfNotExists;
  private final String databaseId;
  private final boolean createVectorIndexIfNotExists;
  private final int embedderDimension;

  private FirestoreRetrieverConfig(Builder builder) {
    this.name = builder.name;
    this.label = builder.label;
    this.collection = builder.collection;
    this.vectorField = builder.vectorField;
    this.contentField = builder.contentField;
    this.contentExtractor = builder.contentExtractor;
    this.distanceMeasure = builder.distanceMeasure;
    this.distanceThreshold = builder.distanceThreshold;
    this.distanceResultField = builder.distanceResultField;
    this.embedder = builder.embedder;
    this.embedderName = builder.embedderName;
    this.metadataFields = builder.metadataFields;
    this.metadataExtractor = builder.metadataExtractor;
    this.defaultLimit = builder.defaultLimit;
    this.createDatabaseIfNotExists = builder.createDatabaseIfNotExists;
    this.databaseId = builder.databaseId;
    this.createVectorIndexIfNotExists = builder.createVectorIndexIfNotExists;
    this.embedderDimension = builder.embedderDimension;
  }

  /**
   * Creates a builder for FirestoreRetrieverConfig.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  public String getName() {
    return name;
  }

  public String getLabel() {
    return label;
  }

  public String getCollection() {
    return collection;
  }

  public String getVectorField() {
    return vectorField;
  }

  public String getContentField() {
    return contentField;
  }

  public Function<QueryDocumentSnapshot, List<Part>> getContentExtractor() {
    return contentExtractor;
  }

  public DistanceMeasure getDistanceMeasure() {
    return distanceMeasure;
  }

  public Double getDistanceThreshold() {
    return distanceThreshold;
  }

  public String getDistanceResultField() {
    return distanceResultField;
  }

  public Embedder getEmbedder() {
    return embedder;
  }

  public String getEmbedderName() {
    return embedderName;
  }

  public List<String> getMetadataFields() {
    return metadataFields;
  }

  public Function<QueryDocumentSnapshot, Map<String, Object>> getMetadataExtractor() {
    return metadataExtractor;
  }

  public int getDefaultLimit() {
    return defaultLimit;
  }

  /**
   * Returns whether to create the database if it doesn't exist.
   *
   * @return true if the database should be created automatically
   */
  public boolean isCreateDatabaseIfNotExists() {
    return createDatabaseIfNotExists;
  }

  /**
   * Returns the Firestore database ID.
   *
   * @return the database ID, or "(default)" if not specified
   */
  public String getDatabaseId() {
    return databaseId != null ? databaseId : "(default)";
  }

  /**
   * Returns whether to create the vector index if it doesn't exist.
   *
   * @return true if the vector index should be created automatically
   */
  public boolean isCreateVectorIndexIfNotExists() {
    return createVectorIndexIfNotExists;
  }

  /**
   * Returns the embedder dimension for vector index creation.
   *
   * @return the embedder dimension, defaults to 768 if not specified
   */
  public int getEmbedderDimension() {
    return embedderDimension > 0 ? embedderDimension : 768;
  }

  /**
   * Builder for FirestoreRetrieverConfig.
   */
  public static class Builder {
    private String name;
    private String label;
    private String collection;
    private String vectorField;
    private String contentField;
    private Function<QueryDocumentSnapshot, List<Part>> contentExtractor;
    private DistanceMeasure distanceMeasure = DistanceMeasure.COSINE;
    private Double distanceThreshold;
    private String distanceResultField;
    private Embedder embedder;
    private String embedderName;
    private List<String> metadataFields;
    private Function<QueryDocumentSnapshot, Map<String, Object>> metadataExtractor;
    private int defaultLimit = 10;
    private boolean createDatabaseIfNotExists = false;
    private String databaseId;
    private boolean createVectorIndexIfNotExists = false;
    private int embedderDimension = 768;

    /**
     * Sets the name of the retriever (required).
     *
     * @param name
     *            the retriever name
     * @return this builder
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the display label for the Developer UI.
     *
     * @param label
     *            the display label
     * @return this builder
     */
    public Builder label(String label) {
      this.label = label;
      return this;
    }

    /**
     * Sets the Firestore collection name.
     *
     * @param collection
     *            the collection name
     * @return this builder
     */
    public Builder collection(String collection) {
      this.collection = collection;
      return this;
    }

    /**
     * Sets the field name containing vector embeddings.
     *
     * @param vectorField
     *            the vector field name
     * @return this builder
     */
    public Builder vectorField(String vectorField) {
      this.vectorField = vectorField;
      return this;
    }

    /**
     * Sets the field name containing document content.
     *
     * @param contentField
     *            the content field name
     * @return this builder
     */
    public Builder contentField(String contentField) {
      this.contentField = contentField;
      return this;
    }

    /**
     * Sets a custom content extractor function.
     *
     * @param contentExtractor
     *            the content extractor function
     * @return this builder
     */
    public Builder contentExtractor(Function<QueryDocumentSnapshot, List<Part>> contentExtractor) {
      this.contentExtractor = contentExtractor;
      return this;
    }

    /**
     * Sets the distance measure for vector similarity.
     *
     * @param distanceMeasure
     *            the distance measure
     * @return this builder
     */
    public Builder distanceMeasure(DistanceMeasure distanceMeasure) {
      this.distanceMeasure = distanceMeasure;
      return this;
    }

    /**
     * Sets the distance threshold for filtering results.
     *
     * @param distanceThreshold
     *            the distance threshold
     * @return this builder
     */
    public Builder distanceThreshold(Double distanceThreshold) {
      this.distanceThreshold = distanceThreshold;
      return this;
    }

    /**
     * Sets the field name to store the distance in result metadata.
     *
     * @param distanceResultField
     *            the distance result field name
     * @return this builder
     */
    public Builder distanceResultField(String distanceResultField) {
      this.distanceResultField = distanceResultField;
      return this;
    }

    /**
     * Sets the embedder instance to use.
     *
     * @param embedder
     *            the embedder
     * @return this builder
     */
    public Builder embedder(Embedder embedder) {
      this.embedder = embedder;
      return this;
    }

    /**
     * Sets the embedder name for resolution from registry.
     *
     * @param embedderName
     *            the embedder name (e.g., "googleai/text-embedding-004")
     * @return this builder
     */
    public Builder embedderName(String embedderName) {
      this.embedderName = embedderName;
      return this;
    }

    /**
     * Sets the metadata fields to include in results.
     *
     * @param metadataFields
     *            the list of metadata field names
     * @return this builder
     */
    public Builder metadataFields(List<String> metadataFields) {
      this.metadataFields = metadataFields;
      return this;
    }

    /**
     * Sets a custom metadata extractor function.
     *
     * @param metadataExtractor
     *            the metadata extractor function
     * @return this builder
     */
    public Builder metadataExtractor(Function<QueryDocumentSnapshot, Map<String, Object>> metadataExtractor) {
      this.metadataExtractor = metadataExtractor;
      return this;
    }

    /**
     * Sets the default limit for retrieval results.
     *
     * @param defaultLimit
     *            the default limit
     * @return this builder
     */
    public Builder defaultLimit(int defaultLimit) {
      this.defaultLimit = defaultLimit;
      return this;
    }

    /**
     * Sets whether to create the Firestore database if it doesn't exist.
     * 
     * <p>
     * When enabled, the plugin will automatically create the database on first use
     * if it doesn't exist. If the database already exists, this flag has no effect.
     *
     * @param createDatabaseIfNotExists
     *            true to create database automatically
     * @return this builder
     */
    public Builder createDatabaseIfNotExists(boolean createDatabaseIfNotExists) {
      this.createDatabaseIfNotExists = createDatabaseIfNotExists;
      return this;
    }

    /**
     * Sets the Firestore database ID to use.
     * 
     * <p>
     * Defaults to "(default)" if not specified.
     *
     * @param databaseId
     *            the database ID
     * @return this builder
     */
    public Builder databaseId(String databaseId) {
      this.databaseId = databaseId;
      return this;
    }

    /**
     * Sets whether to create the Firestore vector index if it doesn't exist.
     * 
     * <p>
     * When enabled, the plugin will automatically create the vector index on the
     * configured collection and vector field if it doesn't exist. This uses a flat
     * index type suitable for smaller collections.
     *
     * @param createVectorIndexIfNotExists
     *            true to create vector index automatically
     * @return this builder
     */
    public Builder createVectorIndexIfNotExists(boolean createVectorIndexIfNotExists) {
      this.createVectorIndexIfNotExists = createVectorIndexIfNotExists;
      return this;
    }

    /**
     * Sets the dimension of the embeddings for vector index creation.
     * 
     * <p>
     * This should match the output dimension of your embedder. Common values: 768
     * (text-embedding-004), 1536 (OpenAI ada-002). Defaults to 768 if not
     * specified.
     *
     * @param embedderDimension
     *            the embedding dimension
     * @return this builder
     */
    public Builder embedderDimension(int embedderDimension) {
      this.embedderDimension = embedderDimension;
      return this;
    }

    /**
     * Builds the configuration.
     *
     * @return the configured FirestoreRetrieverConfig
     * @throws IllegalStateException
     *             if required fields are missing
     */
    public FirestoreRetrieverConfig build() {
      if (name == null || name.isEmpty()) {
        throw new IllegalStateException("Retriever name is required");
      }
      if (vectorField == null || vectorField.isEmpty()) {
        throw new IllegalStateException("Vector field is required");
      }
      if (contentField == null && contentExtractor == null) {
        throw new IllegalStateException("Either contentField or contentExtractor is required");
      }
      if (embedder == null && embedderName == null) {
        throw new IllegalStateException("Either embedder or embedderName is required");
      }
      return new FirestoreRetrieverConfig(this);
    }
  }
}
