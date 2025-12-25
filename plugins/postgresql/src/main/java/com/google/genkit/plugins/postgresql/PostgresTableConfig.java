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

package com.google.genkit.plugins.postgresql;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a PostgreSQL table used for vector storage.
 *
 * <p>
 * This class configures how documents are stored in a PostgreSQL table with
 * pgvector support.
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * PostgresTableConfig config = PostgresTableConfig.builder().tableName("documents")
 * 		.embedderName("googleai/text-embedding-004").vectorDimension(768).distanceStrategy(DistanceStrategy.COSINE)
 * 		.build();
 * }</pre>
 */
public class PostgresTableConfig {

  /**
   * Distance strategies for vector similarity search.
   */
  public enum DistanceStrategy {
    /**
     * Cosine distance (1 - cosine similarity). Best for normalized vectors.
     */
    COSINE("vector_cosine_ops", "<=>"),

    /**
     * L2 (Euclidean) distance. Best for comparing actual distances.
     */
    L2("vector_l2_ops", "<->"),

    /**
     * Inner product (negative dot product for distance). Best when vectors are not
     * normalized.
     */
    INNER_PRODUCT("vector_ip_ops", "<#>");

    private final String indexOpsClass;
    private final String operator;

    DistanceStrategy(String indexOpsClass, String operator) {
      this.indexOpsClass = indexOpsClass;
      this.operator = operator;
    }

    /**
     * Gets the pgvector operator class for index creation.
     */
    public String getIndexOpsClass() {
      return indexOpsClass;
    }

    /**
     * Gets the SQL operator for distance calculation.
     */
    public String getOperator() {
      return operator;
    }
  }

  private final String tableName;
  private final String embedderName;
  private final int vectorDimension;
  private final DistanceStrategy distanceStrategy;
  private final String idColumn;
  private final String contentColumn;
  private final String embeddingColumn;
  private final String metadataColumn;
  private final boolean createTableIfNotExists;
  private final boolean createIndexIfNotExists;
  private final int indexLists;
  private final Map<String, Object> additionalMetadata;

  private PostgresTableConfig(Builder builder) {
    this.tableName = builder.tableName;
    this.embedderName = builder.embedderName;
    this.vectorDimension = builder.vectorDimension;
    this.distanceStrategy = builder.distanceStrategy;
    this.idColumn = builder.idColumn;
    this.contentColumn = builder.contentColumn;
    this.embeddingColumn = builder.embeddingColumn;
    this.metadataColumn = builder.metadataColumn;
    this.createTableIfNotExists = builder.createTableIfNotExists;
    this.createIndexIfNotExists = builder.createIndexIfNotExists;
    this.indexLists = builder.indexLists;
    this.additionalMetadata = new HashMap<>(builder.additionalMetadata);
  }

  /**
   * Creates a new builder for PostgresTableConfig.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the table name.
   */
  public String getTableName() {
    return tableName;
  }

  /**
   * Gets the embedder name for generating vectors.
   */
  public String getEmbedderName() {
    return embedderName;
  }

  /**
   * Gets the vector dimension.
   */
  public int getVectorDimension() {
    return vectorDimension;
  }

  /**
   * Gets the distance strategy for similarity search.
   */
  public DistanceStrategy getDistanceStrategy() {
    return distanceStrategy;
  }

  /**
   * Gets the ID column name.
   */
  public String getIdColumn() {
    return idColumn;
  }

  /**
   * Gets the content column name.
   */
  public String getContentColumn() {
    return contentColumn;
  }

  /**
   * Gets the embedding column name.
   */
  public String getEmbeddingColumn() {
    return embeddingColumn;
  }

  /**
   * Gets the metadata column name.
   */
  public String getMetadataColumn() {
    return metadataColumn;
  }

  /**
   * Returns whether to create the table if it doesn't exist.
   */
  public boolean isCreateTableIfNotExists() {
    return createTableIfNotExists;
  }

  /**
   * Returns whether to create the index if it doesn't exist.
   */
  public boolean isCreateIndexIfNotExists() {
    return createIndexIfNotExists;
  }

  /**
   * Gets the number of lists for IVFFlat index.
   */
  public int getIndexLists() {
    return indexLists;
  }

  /**
   * Gets additional metadata to add to all indexed documents.
   */
  public Map<String, Object> getAdditionalMetadata() {
    return additionalMetadata;
  }

  /**
   * Builder for PostgresTableConfig.
   */
  public static class Builder {
    private String tableName;
    private String embedderName;
    private int vectorDimension = 768;
    private DistanceStrategy distanceStrategy = DistanceStrategy.COSINE;
    private String idColumn = "id";
    private String contentColumn = "content";
    private String embeddingColumn = "embedding";
    private String metadataColumn = "metadata";
    private boolean createTableIfNotExists = true;
    private boolean createIndexIfNotExists = true;
    private int indexLists = 100;
    private Map<String, Object> additionalMetadata = new HashMap<>();

    /**
     * Sets the table name (required).
     */
    public Builder tableName(String tableName) {
      this.tableName = tableName;
      return this;
    }

    /**
     * Sets the embedder name for generating vectors (required).
     */
    public Builder embedderName(String embedderName) {
      this.embedderName = embedderName;
      return this;
    }

    /**
     * Sets the vector dimension (default: 768).
     */
    public Builder vectorDimension(int vectorDimension) {
      this.vectorDimension = vectorDimension;
      return this;
    }

    /**
     * Sets the distance strategy (default: COSINE).
     */
    public Builder distanceStrategy(DistanceStrategy distanceStrategy) {
      this.distanceStrategy = distanceStrategy;
      return this;
    }

    /**
     * Sets the ID column name (default: "id").
     */
    public Builder idColumn(String idColumn) {
      this.idColumn = idColumn;
      return this;
    }

    /**
     * Sets the content column name (default: "content").
     */
    public Builder contentColumn(String contentColumn) {
      this.contentColumn = contentColumn;
      return this;
    }

    /**
     * Sets the embedding column name (default: "embedding").
     */
    public Builder embeddingColumn(String embeddingColumn) {
      this.embeddingColumn = embeddingColumn;
      return this;
    }

    /**
     * Sets the metadata column name (default: "metadata").
     */
    public Builder metadataColumn(String metadataColumn) {
      this.metadataColumn = metadataColumn;
      return this;
    }

    /**
     * Sets whether to create the table if it doesn't exist (default: true).
     */
    public Builder createTableIfNotExists(boolean createTableIfNotExists) {
      this.createTableIfNotExists = createTableIfNotExists;
      return this;
    }

    /**
     * Sets whether to create the index if it doesn't exist (default: true).
     */
    public Builder createIndexIfNotExists(boolean createIndexIfNotExists) {
      this.createIndexIfNotExists = createIndexIfNotExists;
      return this;
    }

    /**
     * Sets the number of lists for IVFFlat index (default: 100). Higher values give
     * better recall but slower builds.
     */
    public Builder indexLists(int indexLists) {
      this.indexLists = indexLists;
      return this;
    }

    /**
     * Adds additional metadata to include with all indexed documents.
     */
    public Builder additionalMetadata(String key, Object value) {
      this.additionalMetadata.put(key, value);
      return this;
    }

    /**
     * Sets all additional metadata to include with indexed documents.
     */
    public Builder additionalMetadata(Map<String, Object> metadata) {
      this.additionalMetadata = new HashMap<>(metadata);
      return this;
    }

    /**
     * Builds the PostgresTableConfig.
     *
     * @throws IllegalStateException
     *             if required fields are not set
     */
    public PostgresTableConfig build() {
      if (tableName == null || tableName.isBlank()) {
        throw new IllegalStateException("tableName is required");
      }
      if (embedderName == null || embedderName.isBlank()) {
        throw new IllegalStateException("embedderName is required");
      }
      if (vectorDimension <= 0) {
        throw new IllegalStateException("vectorDimension must be positive");
      }
      return new PostgresTableConfig(this);
    }
  }
}
