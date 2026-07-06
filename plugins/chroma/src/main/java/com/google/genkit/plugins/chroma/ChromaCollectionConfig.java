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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a single Chroma collection managed by {@link ChromaPlugin}.
 *
 * <p>Each config registers a retriever and indexer named {@code chroma/<collectionName>}.
 */
public final class ChromaCollectionConfig {

  /** Distance function used by the Chroma HNSW index. */
  public enum Distance {
    COSINE("cosine"),
    L2("l2"),
    INNER_PRODUCT("ip");

    private final String value;

    Distance(String value) {
      this.value = value;
    }

    /**
     * Returns the Chroma {@code hnsw:space} value.
     *
     * @return the space name
     */
    public String getValue() {
      return value;
    }
  }

  private final String collectionName;
  private final String embedderName;
  private final Distance distance;
  private final boolean createCollectionIfNotExists;
  private final Map<String, Object> additionalMetadata;

  private ChromaCollectionConfig(Builder builder) {
    this.collectionName = builder.collectionName;
    this.embedderName = builder.embedderName;
    this.distance = builder.distance;
    this.createCollectionIfNotExists = builder.createCollectionIfNotExists;
    this.additionalMetadata = new HashMap<>(builder.additionalMetadata);
  }

  /**
   * Returns the Chroma collection name.
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
   * Returns the distance function (default {@link Distance#COSINE}).
   *
   * @return the distance function
   */
  public Distance getDistance() {
    return distance;
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

  /** Builder for {@link ChromaCollectionConfig}. */
  public static final class Builder {
    private String collectionName;
    private String embedderName;
    private Distance distance = Distance.COSINE;
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
     * Sets the distance function.
     *
     * @param distance the distance function
     * @return this builder
     */
    public Builder distance(Distance distance) {
      this.distance = distance;
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
     * Builds a new {@code ChromaCollectionConfig}.
     *
     * @return a new config instance
     */
    public ChromaCollectionConfig build() {
      if (collectionName == null || collectionName.isBlank()) {
        throw new IllegalArgumentException("collectionName must be non-empty");
      }
      if (embedderName == null || embedderName.isBlank()) {
        throw new IllegalArgumentException("embedderName must be non-empty");
      }
      if (distance == null) {
        throw new IllegalArgumentException("distance must be non-null");
      }
      return new ChromaCollectionConfig(this);
    }
  }
}
