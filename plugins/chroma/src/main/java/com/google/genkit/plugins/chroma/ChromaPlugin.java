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
 * Chroma vector database plugin for Genkit.
 *
 * <p>Registers a retriever and indexer named {@code chroma/<collectionName>} for each configured
 * collection, talking to a Chroma server over its v2 REST API.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(GoogleGenAIPlugin.create(apiKey))
 *     .plugin(
 *         ChromaPlugin.builder()
 *             .url("http://localhost:8000")
 *             .addCollection(
 *                 ChromaCollectionConfig.builder()
 *                     .collectionName("films")
 *                     .embedderName("googleai/gemini-embedding-001")
 *                     .build())
 *             .build())
 *     .build();
 * }</pre>
 */
public final class ChromaPlugin implements Plugin {

  /** The plugin name; used as the {@code chroma/...} action prefix. */
  public static final String PLUGIN_NAME = "chroma";

  /** Default Chroma tenant. */
  public static final String DEFAULT_TENANT = "default_tenant";

  /** Default Chroma database. */
  public static final String DEFAULT_DATABASE = "default_database";

  /** Default Chroma server URL. */
  public static final String DEFAULT_URL = "http://localhost:8000";

  private static final Logger logger = LoggerFactory.getLogger(ChromaPlugin.class);

  private final String url;
  private final String tenant;
  private final String database;
  private final List<ChromaCollectionConfig> collectionConfigs;

  private ChromaPlugin(Builder builder) {
    this.url = builder.url;
    this.tenant = builder.tenant;
    this.database = builder.database;
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
        "ChromaPlugin requires a Registry to resolve embedders. Use init(registry) instead.");
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    List<Action<?, ?, ?>> actions = new ArrayList<>();
    for (ChromaCollectionConfig config : collectionConfigs) {
      String embedderKey = ActionType.EMBEDDER.keyFromName(config.getEmbedderName());
      Action<?, ?, ?> embedderAction = registry.lookupAction(embedderKey);
      if (embedderAction == null) {
        throw new IllegalStateException(
            "Embedder not found: "
                + config.getEmbedderName()
                + ". Make sure the embedder plugin is registered before ChromaPlugin.");
      }
      if (!(embedderAction instanceof Embedder embedder)) {
        throw new IllegalStateException(
            "Action " + config.getEmbedderName() + " is not an Embedder");
      }

      ChromaVectorStore store = new ChromaVectorStore(url, tenant, database, config, embedder);
      actions.add(store.createRetriever());
      actions.add(store.createIndexer());
      logger.info("Registered Chroma vector store: {}/{}", PLUGIN_NAME, config.getCollectionName());
    }
    return actions;
  }

  /** Builder for {@link ChromaPlugin}. */
  public static final class Builder {
    private String url = DEFAULT_URL;
    private String tenant = DEFAULT_TENANT;
    private String database = DEFAULT_DATABASE;
    private final List<ChromaCollectionConfig> collectionConfigs = new ArrayList<>();

    private Builder() {}

    /**
     * Sets the Chroma server URL (default {@value #DEFAULT_URL}).
     *
     * @param url the server URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the Chroma tenant (default {@value #DEFAULT_TENANT}).
     *
     * @param tenant the tenant
     * @return this builder
     */
    public Builder tenant(String tenant) {
      this.tenant = tenant;
      return this;
    }

    /**
     * Sets the Chroma database (default {@value #DEFAULT_DATABASE}).
     *
     * @param database the database
     * @return this builder
     */
    public Builder database(String database) {
      this.database = database;
      return this;
    }

    /**
     * Adds a collection configuration.
     *
     * @param config the collection configuration
     * @return this builder
     */
    public Builder addCollection(ChromaCollectionConfig config) {
      this.collectionConfigs.add(config);
      return this;
    }

    /**
     * Builds the plugin.
     *
     * @return a new {@code ChromaPlugin}
     */
    public ChromaPlugin build() {
      if (url == null || url.isBlank()) {
        throw new IllegalStateException("url is required");
      }
      if (tenant == null || tenant.isBlank()) {
        throw new IllegalStateException("tenant is required");
      }
      if (database == null || database.isBlank()) {
        throw new IllegalStateException("database is required");
      }
      if (collectionConfigs.isEmpty()) {
        throw new IllegalStateException("At least one collection configuration is required");
      }
      return new ChromaPlugin(this);
    }
  }
}
