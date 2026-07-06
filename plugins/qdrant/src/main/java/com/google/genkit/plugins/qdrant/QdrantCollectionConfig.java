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

package com.google.genkit.plugins.qdrant;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a single Qdrant collection managed by {@link QdrantPlugin}.
 *
 * <p>Each config registers a retriever and indexer named {@code qdrant/<collectionName>}.
 */
public final class QdrantCollectionConfig {

  /** Distance function used by the Qdrant collection. */
  public enum Distance {
    COSINE("Cosine"),
    EUCLIDEAN("Euclid"),
    DOT_PRODUCT("Dot");

    private final String value;

    Distance(String value) {
      this.value = value;
    }

    /**
     * Returns the Qdrant distance name.
     *
     * @return the distance name
     */
    public String getValue() {
      return value;
    }
  }

  private final String collectionName;
  private final String embedderName;
  private final int dimension;
  private final Distance distance;
  private final String textPayloadKey;
  private final boolean createCollectionIfNotExists;
  private final Map<String, Object> additionalMetadata;

  private QdrantCollectionConfig(Builder builder) {
    this.collectionName = builder.collectionName;
    this.embedderName = builder.embedderName;
    this.dimension = builder.dimension;
    this.distance = builder.distance;
    this.textPayloadKey = builder.textPayloadKey;
    this.createCollectionIfNotExists = builder.createCollectionIfNotExists;
    this.additionalMetadata = new HashMap<>(builder.additionalMetadata);
  }

  /**
   * Returns the Qdrant collection name.
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
   * Returns the distance function (default {@link Distance#COSINE}).
   *
   * @return the distance function
   */
  public Distance getDistance() {
    return distance;
  }

  /**
   * Returns the payload key that stores the document text (default {@code text}).
   *
   * @return the text payload key
   */
  public String getTextPayloadKey() {
    return textPayloadKey;
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
   * Returns additional metadata merged into every indexed document's payload.
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

  /** Builder for {@link QdrantCollectionConfig}. */
  public static final class Builder {
    private String collectionName;
    private String embedderName;
    private int dimension = 768;
    private Distance distance = Distance.COSINE;
    private String textPayloadKey = "text";
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
     * Sets the payload key that stores the document text.
     *
     * @param textPayloadKey the text payload key
     * @return this builder
     */
    public Builder textPayloadKey(String textPayloadKey) {
      this.textPayloadKey = textPayloadKey;
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
     * Adds a metadata entry merged into every indexed document's payload.
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
     * Builds a new {@code QdrantCollectionConfig}.
     *
     * @return a new config instance
     */
    public QdrantCollectionConfig build() {
      if (collectionName == null || collectionName.isBlank()) {
        throw new IllegalArgumentException("collectionName must be non-empty");
      }
      if (embedderName == null || embedderName.isBlank()) {
        throw new IllegalArgumentException("embedderName must be non-empty");
      }
      if (dimension < 1) {
        throw new IllegalArgumentException("dimension must be >= 1");
      }
      if (distance == null) {
        throw new IllegalArgumentException("distance must be non-null");
      }
      if (textPayloadKey == null || textPayloadKey.isBlank()) {
        throw new IllegalArgumentException("textPayloadKey must be non-empty");
      }
      return new QdrantCollectionConfig(this);
    }
  }
}
