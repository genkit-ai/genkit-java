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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a single MongoDB Atlas Vector Search collection managed by {@link MongoPlugin}.
 *
 * <p>Each config registers a retriever and indexer named {@code mongodb/<collectionName>} backed by
 * an Atlas Vector Search index over the {@link #getEmbeddingField() embedding field}.
 */
public final class MongoVectorStoreConfig {

  /** Vector similarity function supported by Atlas Vector Search. */
  public enum Similarity {
    COSINE("cosine"),
    EUCLIDEAN("euclidean"),
    DOT_PRODUCT("dotProduct");

    private final String value;

    Similarity(String value) {
      this.value = value;
    }

    /**
     * Returns the Atlas Vector Search similarity name.
     *
     * @return the similarity name
     */
    public String getValue() {
      return value;
    }
  }

  private final String databaseName;
  private final String collectionName;
  private final String embedderName;
  private final String indexName;
  private final int dimension;
  private final Similarity similarity;
  private final String textField;
  private final String embeddingField;
  private final int numCandidates;
  private final boolean createIndexIfNotExists;
  private final Map<String, Object> additionalMetadata;

  private MongoVectorStoreConfig(Builder builder) {
    this.databaseName = builder.databaseName;
    this.collectionName = builder.collectionName;
    this.embedderName = builder.embedderName;
    this.indexName = builder.indexName;
    this.dimension = builder.dimension;
    this.similarity = builder.similarity;
    this.textField = builder.textField;
    this.embeddingField = builder.embeddingField;
    this.numCandidates = builder.numCandidates;
    this.createIndexIfNotExists = builder.createIndexIfNotExists;
    this.additionalMetadata = new HashMap<>(builder.additionalMetadata);
  }

  /**
   * Returns the database name (default {@code genkit}).
   *
   * @return the database name
   */
  public String getDatabaseName() {
    return databaseName;
  }

  /**
   * Returns the collection name.
   *
   * @return the collection name
   */
  public String getCollectionName() {
    return collectionName;
  }

  /**
   * Returns the name of the embedder used to vectorize documents and queries.
   *
   * @return the embedder name
   */
  public String getEmbedderName() {
    return embedderName;
  }

  /**
   * Returns the Atlas Vector Search index name (default {@code genkit_vector_index}).
   *
   * @return the index name
   */
  public String getIndexName() {
    return indexName;
  }

  /**
   * Returns the embedding dimension (default {@code 768}).
   *
   * @return the embedding dimension
   */
  public int getDimension() {
    return dimension;
  }

  /**
   * Returns the vector similarity function (default {@link Similarity#COSINE}).
   *
   * @return the similarity
   */
  public Similarity getSimilarity() {
    return similarity;
  }

  /**
   * Returns the field that stores the document text (default {@code text}).
   *
   * @return the text field name
   */
  public String getTextField() {
    return textField;
  }

  /**
   * Returns the field that stores the embedding vector (default {@code embedding}).
   *
   * @return the embedding field name
   */
  public String getEmbeddingField() {
    return embeddingField;
  }

  /**
   * Returns the number of nearest neighbors to consider during the vector search (default {@code
   * 100}). Atlas recommends a value at least 10&times; the requested result count.
   *
   * @return the number of candidates
   */
  public int getNumCandidates() {
    return numCandidates;
  }

  /**
   * Returns whether to create the Atlas Vector Search index on first use if it does not exist
   * (default {@code false}).
   *
   * @return {@code true} if the index should be created when missing
   */
  public boolean isCreateIndexIfNotExists() {
    return createIndexIfNotExists;
  }

  /**
   * Returns additional metadata merged into every indexed document.
   *
   * @return the additional metadata
   */
  public Map<String, Object> getAdditionalMetadata() {
    return additionalMetadata;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link MongoVectorStoreConfig}. */
  public static final class Builder {
    private String databaseName = "genkit";
    private String collectionName;
    private String embedderName;
    private String indexName = "genkit_vector_index";
    private int dimension = 768;
    private Similarity similarity = Similarity.COSINE;
    private String textField = "text";
    private String embeddingField = "embedding";
    private int numCandidates = 100;
    private boolean createIndexIfNotExists = false;
    private final Map<String, Object> additionalMetadata = new HashMap<>();

    private Builder() {}

    /**
     * Sets the database name.
     *
     * @param databaseName the database name
     * @return this builder
     */
    public Builder databaseName(String databaseName) {
      this.databaseName = databaseName;
      return this;
    }

    /**
     * Sets the collection name.
     *
     * @param collectionName the collection name
     * @return this builder
     */
    public Builder collectionName(String collectionName) {
      this.collectionName = collectionName;
      return this;
    }

    /**
     * Sets the embedder name.
     *
     * @param embedderName the embedder name
     * @return this builder
     */
    public Builder embedderName(String embedderName) {
      this.embedderName = embedderName;
      return this;
    }

    /**
     * Sets the Atlas Vector Search index name.
     *
     * @param indexName the index name
     * @return this builder
     */
    public Builder indexName(String indexName) {
      this.indexName = indexName;
      return this;
    }

    /**
     * Sets the embedding dimension.
     *
     * @param dimension the embedding dimension (must be {@code >= 1})
     * @return this builder
     */
    public Builder dimension(int dimension) {
      this.dimension = dimension;
      return this;
    }

    /**
     * Sets the vector similarity function.
     *
     * @param similarity the similarity
     * @return this builder
     */
    public Builder similarity(Similarity similarity) {
      this.similarity = similarity;
      return this;
    }

    /**
     * Sets the text field name.
     *
     * @param textField the text field name
     * @return this builder
     */
    public Builder textField(String textField) {
      this.textField = textField;
      return this;
    }

    /**
     * Sets the embedding field name.
     *
     * @param embeddingField the embedding field name
     * @return this builder
     */
    public Builder embeddingField(String embeddingField) {
      this.embeddingField = embeddingField;
      return this;
    }

    /**
     * Sets the number of nearest neighbors to consider during the vector search.
     *
     * @param numCandidates the number of candidates (must be {@code >= 1})
     * @return this builder
     */
    public Builder numCandidates(int numCandidates) {
      this.numCandidates = numCandidates;
      return this;
    }

    /**
     * Sets whether to create the Atlas Vector Search index on first use if it does not exist.
     *
     * @param createIndexIfNotExists whether to create the index when missing
     * @return this builder
     */
    public Builder createIndexIfNotExists(boolean createIndexIfNotExists) {
      this.createIndexIfNotExists = createIndexIfNotExists;
      return this;
    }

    /**
     * Adds a metadata entry merged into every indexed document.
     *
     * @param key the metadata key
     * @param value the metadata value
     * @return this builder
     */
    public Builder addAdditionalMetadata(String key, Object value) {
      this.additionalMetadata.put(key, value);
      return this;
    }

    /**
     * Builds a new {@code MongoVectorStoreConfig}.
     *
     * @return a new config instance
     */
    public MongoVectorStoreConfig build() {
      if (databaseName == null || databaseName.isBlank()) {
        throw new IllegalArgumentException("databaseName must be non-empty");
      }
      if (collectionName == null || collectionName.isBlank()) {
        throw new IllegalArgumentException("collectionName must be non-empty");
      }
      if (embedderName == null || embedderName.isBlank()) {
        throw new IllegalArgumentException("embedderName must be non-empty");
      }
      if (indexName == null || indexName.isBlank()) {
        throw new IllegalArgumentException("indexName must be non-empty");
      }
      if (dimension < 1) {
        throw new IllegalArgumentException("dimension must be >= 1");
      }
      if (numCandidates < 1) {
        throw new IllegalArgumentException("numCandidates must be >= 1");
      }
      return new MongoVectorStoreConfig(this);
    }
  }
}
