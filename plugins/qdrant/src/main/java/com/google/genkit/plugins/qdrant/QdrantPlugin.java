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

import com.google.genkit.ai.Embedder;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qdrant vector database plugin for Genkit.
 *
 * <p>Registers a retriever and indexer named {@code qdrant/<collectionName>} for each configured
 * collection, talking to a Qdrant server over its REST API.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(GoogleGenAIPlugin.create(apiKey))
 *     .plugin(
 *         QdrantPlugin.builder()
 *             .url("http://localhost:6333")
 *             .addCollection(
 *                 QdrantCollectionConfig.builder()
 *                     .collectionName("films")
 *                     .embedderName("googleai/gemini-embedding-001")
 *                     .dimension(768)
 *                     .build())
 *             .build())
 *     .build();
 * }</pre>
 */
public final class QdrantPlugin implements Plugin {

  /** The plugin name; used as the {@code qdrant/...} action prefix. */
  public static final String PLUGIN_NAME = "qdrant";

  /** Default Qdrant server URL. */
  public static final String DEFAULT_URL = "http://localhost:6333";

  private static final Logger logger = LoggerFactory.getLogger(QdrantPlugin.class);

  private final String url;
  private final String apiKey;
  private final List<QdrantCollectionConfig> collectionConfigs;

  private QdrantPlugin(Builder builder) {
    this.url = builder.url;
    this.apiKey = builder.apiKey;
    this.collectionConfigs = new ArrayList<>(builder.collectionConfigs);
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String getName() {
    return PLUGIN_NAME;
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    throw new IllegalStateException(
        "QdrantPlugin requires a Registry to resolve embedders. Use init(registry) instead.");
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    List<Action<?, ?, ?>> actions = new ArrayList<>();
    for (QdrantCollectionConfig config : collectionConfigs) {
      String embedderKey = ActionType.EMBEDDER.keyFromName(config.getEmbedderName());
      Action<?, ?, ?> embedderAction = registry.lookupAction(embedderKey);
      if (embedderAction == null) {
        throw new IllegalStateException(
            "Embedder not found: "
                + config.getEmbedderName()
                + ". Make sure the embedder plugin is registered before QdrantPlugin.");
      }
      if (!(embedderAction instanceof Embedder embedder)) {
        throw new IllegalStateException(
            "Action " + config.getEmbedderName() + " is not an Embedder");
      }

      QdrantVectorStore store = new QdrantVectorStore(url, apiKey, config, embedder);
      actions.add(store.createRetriever());
      actions.add(store.createIndexer());
      logger.info("Registered Qdrant vector store: {}/{}", PLUGIN_NAME, config.getCollectionName());
    }
    return actions;
  }

  /** Builder for {@link QdrantPlugin}. */
  public static final class Builder {
    private String url = DEFAULT_URL;
    private String apiKey;
    private final List<QdrantCollectionConfig> collectionConfigs = new ArrayList<>();

    private Builder() {}

    /**
     * Sets the Qdrant server URL (default {@value #DEFAULT_URL}).
     *
     * @param url the server URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the Qdrant API key (optional; required for Qdrant Cloud).
     *
     * @param apiKey the API key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Adds a collection configuration.
     *
     * @param config the collection configuration
     * @return this builder
     */
    public Builder addCollection(QdrantCollectionConfig config) {
      this.collectionConfigs.add(config);
      return this;
    }

    /**
     * Builds the plugin.
     *
     * @return a new {@code QdrantPlugin}
     */
    public QdrantPlugin build() {
      if (url == null || url.isBlank()) {
        throw new IllegalStateException("url is required");
      }
      if (collectionConfigs.isEmpty()) {
        throw new IllegalStateException("At least one collection configuration is required");
      }
      return new QdrantPlugin(this);
    }
  }
}
