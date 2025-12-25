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

import com.google.genkit.ai.Embedder;

/**
 * Configuration for a Weaviate collection (class) in the plugin.
 *
 * <p>
 * Example:
 * 
 * <pre>{@code
 * WeaviateCollectionConfig config = WeaviateCollectionConfig.builder().name("documents")
 * 		.embedderName("googleai/text-embedding-004").distanceMeasure(DistanceMeasure.COSINE)
 * 		.createCollectionIfMissing(true).build();
 * }</pre>
 */
public class WeaviateCollectionConfig {

  /**
   * Distance measure options for vector similarity search.
   */
  public enum DistanceMeasure {
    /** Cosine similarity (default). */
    COSINE,
    /** L2 (Euclidean) distance. */
    L2_SQUARED,
    /** Dot product. */
    DOT
  }

  private final String name;
  private final String label;
  private final String contentField;
  private final String metadataField;
  private final DistanceMeasure distanceMeasure;
  private final Embedder embedder;
  private final String embedderName;
  private final int defaultLimit;
  private final boolean createCollectionIfMissing;
  private final int vectorDimension;

  private WeaviateCollectionConfig(Builder builder) {
    this.name = builder.name;
    this.label = builder.label;
    this.contentField = builder.contentField;
    this.metadataField = builder.metadataField;
    this.distanceMeasure = builder.distanceMeasure;
    this.embedder = builder.embedder;
    this.embedderName = builder.embedderName;
    this.defaultLimit = builder.defaultLimit;
    this.createCollectionIfMissing = builder.createCollectionIfMissing;
    this.vectorDimension = builder.vectorDimension;
  }

  /**
   * Creates a builder for WeaviateCollectionConfig.
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

  public String getContentField() {
    return contentField != null ? contentField : "content";
  }

  public String getMetadataField() {
    return metadataField != null ? metadataField : "metadata";
  }

  public DistanceMeasure getDistanceMeasure() {
    return distanceMeasure != null ? distanceMeasure : DistanceMeasure.COSINE;
  }

  public Embedder getEmbedder() {
    return embedder;
  }

  public String getEmbedderName() {
    return embedderName;
  }

  public int getDefaultLimit() {
    return defaultLimit > 0 ? defaultLimit : 10;
  }

  public boolean isCreateCollectionIfMissing() {
    return createCollectionIfMissing;
  }

  public int getVectorDimension() {
    return vectorDimension > 0 ? vectorDimension : 768;
  }

  /**
   * Builder for WeaviateCollectionConfig.
   */
  public static class Builder {
    private String name;
    private String label;
    private String contentField;
    private String metadataField;
    private DistanceMeasure distanceMeasure;
    private Embedder embedder;
    private String embedderName;
    private int defaultLimit = 10;
    private boolean createCollectionIfMissing = true;
    private int vectorDimension = 768;

    /**
     * Sets the collection name.
     *
     * @param name
     *            the collection name
     * @return this builder
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the collection label for display.
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
     * Sets the field name for document content.
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
     * Sets the field name for document metadata.
     *
     * @param metadataField
     *            the metadata field name
     * @return this builder
     */
    public Builder metadataField(String metadataField) {
      this.metadataField = metadataField;
      return this;
    }

    /**
     * Sets the distance measure for similarity search.
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
     * Sets the embedder instance directly.
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
     * Sets the embedder name to resolve from registry.
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
     * Sets the default limit for retrieval.
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
     * Sets whether to create the collection if it doesn't exist.
     *
     * @param createCollectionIfMissing
     *            true to create if missing
     * @return this builder
     */
    public Builder createCollectionIfMissing(boolean createCollectionIfMissing) {
      this.createCollectionIfMissing = createCollectionIfMissing;
      return this;
    }

    /**
     * Sets the vector dimension for the collection.
     *
     * @param vectorDimension
     *            the vector dimension
     * @return this builder
     */
    public Builder vectorDimension(int vectorDimension) {
      this.vectorDimension = vectorDimension;
      return this;
    }

    /**
     * Builds the WeaviateCollectionConfig.
     *
     * @return the config
     */
    public WeaviateCollectionConfig build() {
      if (name == null || name.isEmpty()) {
        throw new IllegalStateException("Collection name is required");
      }
      if (embedder == null && (embedderName == null || embedderName.isEmpty())) {
        throw new IllegalStateException("Either embedder or embedderName is required");
      }
      return new WeaviateCollectionConfig(this);
    }
  }
}
