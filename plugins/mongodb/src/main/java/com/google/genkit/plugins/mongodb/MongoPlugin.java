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

import com.google.genkit.ai.Embedder;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB Atlas Vector Search plugin for Genkit.
 *
 * <p>Registers a retriever and indexer named {@code mongodb/<collectionName>} for each configured
 * collection, backed by an Atlas Vector Search index. Requires MongoDB Atlas or the {@code
 * mongodb/mongodb-atlas-local} Docker image (a plain MongoDB server does not support {@code
 * $vectorSearch}).
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(GoogleGenAIPlugin.create(apiKey))
 *     .plugin(
 *         MongoPlugin.builder()
 *             .connectionString("mongodb://localhost:27017/?directConnection=true")
 *             .addCollection(
 *                 MongoVectorStoreConfig.builder()
 *                     .collectionName("films")
 *                     .embedderName("googleai/gemini-embedding-001")
 *                     .dimension(768)
 *                     .createIndexIfNotExists(true)
 *                     .build())
 *             .build())
 *     .build();
 * }</pre>
 */
public final class MongoPlugin implements Plugin {

  /** The plugin name; used as the {@code mongodb/...} action prefix. */
  public static final String PLUGIN_NAME = "mongodb";

  private static final Logger logger = LoggerFactory.getLogger(MongoPlugin.class);

  private final String connectionString;
  private final MongoClient externalClient;
  private final List<MongoVectorStoreConfig> collectionConfigs;
  private MongoClient client;

  private MongoPlugin(Builder builder) {
    this.connectionString = builder.connectionString;
    this.externalClient = builder.externalClient;
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
        "MongoPlugin requires a Registry to resolve embedders. Use init(registry) instead.");
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    client = externalClient != null ? externalClient : MongoClients.create(connectionString);

    List<Action<?, ?, ?>> actions = new ArrayList<>();
    for (MongoVectorStoreConfig config : collectionConfigs) {
      String embedderKey = ActionType.EMBEDDER.keyFromName(config.getEmbedderName());
      Action<?, ?, ?> embedderAction = registry.lookupAction(embedderKey);
      if (embedderAction == null) {
        throw new IllegalStateException(
            "Embedder not found: "
                + config.getEmbedderName()
                + ". Make sure the embedder plugin is registered before MongoPlugin.");
      }
      if (!(embedderAction instanceof Embedder embedder)) {
        throw new IllegalStateException(
            "Action " + config.getEmbedderName() + " is not an Embedder");
      }

      MongoVectorStore store = new MongoVectorStore(client, config, embedder);
      actions.add(store.createRetriever());
      actions.add(store.createIndexer());
      logger.info(
          "Registered MongoDB vector store: {}/{}", PLUGIN_NAME, config.getCollectionName());
    }
    return actions;
  }

  /** Builder for {@link MongoPlugin}. */
  public static final class Builder {
    private String connectionString;
    private MongoClient externalClient;
    private final List<MongoVectorStoreConfig> collectionConfigs = new ArrayList<>();

    private Builder() {}

    /**
     * Sets the MongoDB connection string (required unless an external client is provided).
     *
     * @param connectionString the connection string
     * @return this builder
     */
    public Builder connectionString(String connectionString) {
      this.connectionString = connectionString;
      return this;
    }

    /**
     * Sets an external MongoDB client to use instead of creating one from a connection string.
     *
     * @param client the client
     * @return this builder
     */
    public Builder client(MongoClient client) {
      this.externalClient = client;
      return this;
    }

    /**
     * Adds a collection configuration.
     *
     * @param config the collection configuration
     * @return this builder
     */
    public Builder addCollection(MongoVectorStoreConfig config) {
      this.collectionConfigs.add(config);
      return this;
    }

    /**
     * Builds the plugin.
     *
     * @return a new {@code MongoPlugin}
     */
    public MongoPlugin build() {
      if (externalClient == null && (connectionString == null || connectionString.isBlank())) {
        throw new IllegalStateException(
            "connectionString is required when not providing an external MongoClient");
      }
      if (collectionConfigs.isEmpty()) {
        throw new IllegalStateException("At least one collection configuration is required");
      }
      return new MongoPlugin(this);
    }
  }
}
