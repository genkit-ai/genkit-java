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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a Pinecone index used for vector storage.
 *
 * <p>This class configures how documents are stored and retrieved from a Pinecone index.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * PineconeIndexConfig config = PineconeIndexConfig.builder()
 *     .indexName("my-index")
 *     .embedderName("googleai/text-embedding-004")
 *     .namespace("production")
 *     .build();
 * }</pre>
 */
public class PineconeIndexConfig {

  /** Metric types for vector similarity search. */
  public enum Metric {
    /** Cosine similarity. */
    COSINE("cosine"),

    /** Euclidean (L2) distance. */
    EUCLIDEAN("euclidean"),

    /** Dot product similarity. */
    DOT_PRODUCT("dotproduct");

    private final String value;

    Metric(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  /** Cloud providers for serverless indexes. */
  public enum Cloud {
    AWS("aws"),
    GCP("gcp"),
    AZURE("azure");

    private final String value;

    Cloud(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  private final String indexName;
  private final String embedderName;
  private final String namespace;
  private final int dimension;
  private final Metric metric;
  private final Cloud cloud;
  private final String region;
  private final boolean createIndexIfNotExists;
  private final String textField;
  private final Map<String, Object> additionalMetadata;

  private PineconeIndexConfig(Builder builder) {
    this.indexName = builder.indexName;
    this.embedderName = builder.embedderName;
    this.namespace = builder.namespace;
    this.dimension = builder.dimension;
    this.metric = builder.metric;
    this.cloud = builder.cloud;
    this.region = builder.region;
    this.createIndexIfNotExists = builder.createIndexIfNotExists;
    this.textField = builder.textField;
    this.additionalMetadata = new HashMap<>(builder.additionalMetadata);
  }

  /** Creates a new builder for PineconeIndexConfig. */
  public static Builder builder() {
    return new Builder();
  }

  /** Gets the index name. */
  public String getIndexName() {
    return indexName;
  }

  /** Gets the embedder name for generating vectors. */
  public String getEmbedderName() {
    return embedderName;
  }

  /** Gets the namespace for this configuration. */
  public String getNamespace() {
    return namespace;
  }

  /** Gets the vector dimension. */
  public int getDimension() {
    return dimension;
  }

  /** Gets the similarity metric. */
  public Metric getMetric() {
    return metric;
  }

  /** Gets the cloud provider for serverless index. */
  public Cloud getCloud() {
    return cloud;
  }

  /** Gets the region for serverless index. */
  public String getRegion() {
    return region;
  }

  /** Returns whether to create the index if it doesn't exist. */
  public boolean isCreateIndexIfNotExists() {
    return createIndexIfNotExists;
  }

  /** Gets the metadata field name used to store document text. */
  public String getTextField() {
    return textField;
  }

  /** Gets additional metadata to add to all indexed documents. */
  public Map<String, Object> getAdditionalMetadata() {
    return additionalMetadata;
  }

  /** Builder for PineconeIndexConfig. */
  public static class Builder {
    private String indexName;
    private String embedderName;
    private String namespace = "";
    private int dimension = 768;
    private Metric metric = Metric.COSINE;
    private Cloud cloud = Cloud.AWS;
    private String region = "us-east-1";
    private boolean createIndexIfNotExists = false;
    private String textField = "text";
    private Map<String, Object> additionalMetadata = new HashMap<>();

    /** Sets the index name (required). */
    public Builder indexName(String indexName) {
      this.indexName = indexName;
      return this;
    }

    /** Sets the embedder name for generating vectors (required). */
    public Builder embedderName(String embedderName) {
      this.embedderName = embedderName;
      return this;
    }

    /** Sets the namespace (default: "" - default namespace). */
    public Builder namespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    /** Sets the vector dimension (default: 768). */
    public Builder dimension(int dimension) {
      this.dimension = dimension;
      return this;
    }

    /** Sets the similarity metric (default: COSINE). */
    public Builder metric(Metric metric) {
      this.metric = metric;
      return this;
    }

    /** Sets the cloud provider for serverless index (default: AWS). */
    public Builder cloud(Cloud cloud) {
      this.cloud = cloud;
      return this;
    }

    /** Sets the region for serverless index (default: "us-east-1"). */
    public Builder region(String region) {
      this.region = region;
      return this;
    }

    /** Sets whether to create the index if it doesn't exist (default: false). */
    public Builder createIndexIfNotExists(boolean createIndexIfNotExists) {
      this.createIndexIfNotExists = createIndexIfNotExists;
      return this;
    }

    /** Sets the metadata field name for storing document text (default: "text"). */
    public Builder textField(String textField) {
      this.textField = textField;
      return this;
    }

    /** Adds additional metadata to include with all indexed documents. */
    public Builder additionalMetadata(String key, Object value) {
      this.additionalMetadata.put(key, value);
      return this;
    }

    /** Sets all additional metadata to include with indexed documents. */
    public Builder additionalMetadata(Map<String, Object> metadata) {
      this.additionalMetadata = new HashMap<>(metadata);
      return this;
    }

    /**
     * Builds the PineconeIndexConfig.
     *
     * @throws IllegalStateException if required fields are not set
     */
    public PineconeIndexConfig build() {
      if (indexName == null || indexName.isBlank()) {
        throw new IllegalStateException("indexName is required");
      }
      if (embedderName == null || embedderName.isBlank()) {
        throw new IllegalStateException("embedderName is required");
      }
      if (dimension <= 0) {
        throw new IllegalStateException("dimension must be positive");
      }
      return new PineconeIndexConfig(this);
    }
  }
}
