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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a single Milvus collection managed by {@link MilvusPlugin}.
 *
 * <p>Each config registers a retriever and indexer named {@code milvus/<collectionName>}. The
 * collection is created in Milvus "quick setup" mode (auto id primary key, a {@code vector} field,
 * and dynamic fields) storing the document text under {@code text} and its metadata as a JSON
 * string under {@code metadata}.
 */
public final class MilvusCollectionConfig {

  /** Vector similarity metric used by the Milvus index. */
  public enum Metric {
    COSINE("COSINE"),
    L2("L2"),
    INNER_PRODUCT("IP");

    private final String value;

    Metric(String value) {
      this.value = value;
    }

    /**
     * Returns the Milvus metric type name.
     *
     * @return the metric type name
     */
    public String getValue() {
      return value;
    }
  }

  private final String collectionName;
  private final String embedderName;
  private final int dimension;
  private final Metric metric;
  private final boolean createCollectionIfNotExists;
  private final Map<String, Object> additionalMetadata;

  private MilvusCollectionConfig(Builder builder) {
    this.collectionName = builder.collectionName;
    this.embedderName = builder.embedderName;
    this.dimension = builder.dimension;
    this.metric = builder.metric;
    this.createCollectionIfNotExists = builder.createCollectionIfNotExists;
    this.additionalMetadata = new HashMap<>(builder.additionalMetadata);
  }

  /**
   * Returns the Milvus collection name.
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
   * Returns the embedding dimension (default {@code 768}).
   *
   * @return the embedding dimension
   */
  public int getDimension() {
    return dimension;
  }

  /**
   * Returns the vector similarity metric (default {@link Metric#COSINE}).
   *
   * @return the metric
   */
  public Metric getMetric() {
    return metric;
  }

  /**
   * Returns whether to create the collection on first use if it does not exist (default {@code
   * true}).
   *
   * @return {@code true} if the collection should be created when missing
   */
  public boolean isCreateCollectionIfNotExists() {
    return createCollectionIfNotExists;
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

  /** Builder for {@link MilvusCollectionConfig}. */
  public static final class Builder {
    private String collectionName;
    private String embedderName;
    private int dimension = 768;
    private Metric metric = Metric.COSINE;
    private boolean createCollectionIfNotExists = true;
    private final Map<String, Object> additionalMetadata = new HashMap<>();

    private Builder() {}

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
     * Sets the vector similarity metric.
     *
     * @param metric the metric
     * @return this builder
     */
    public Builder metric(Metric metric) {
      this.metric = metric;
      return this;
    }

    /**
     * Sets whether to create the collection on first use if it does not exist.
     *
     * @param createCollectionIfNotExists whether to create the collection when missing
     * @return this builder
     */
    public Builder createCollectionIfNotExists(boolean createCollectionIfNotExists) {
      this.createCollectionIfNotExists = createCollectionIfNotExists;
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
     * Builds a new {@code MilvusCollectionConfig}.
     *
     * @return a new config instance
     */
    public MilvusCollectionConfig build() {
      if (collectionName == null || collectionName.isBlank()) {
        throw new IllegalArgumentException("collectionName must be non-empty");
      }
      if (embedderName == null || embedderName.isBlank()) {
        throw new IllegalArgumentException("embedderName must be non-empty");
      }
      if (dimension < 1) {
        throw new IllegalArgumentException("dimension must be >= 1");
      }
      if (metric == null) {
        throw new IllegalArgumentException("metric must be non-null");
      }
      return new MilvusCollectionConfig(this);
    }
  }
}
