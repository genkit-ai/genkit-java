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

import com.google.genkit.ai.*;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import io.pinecone.clients.Pinecone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pinecone plugin for Genkit providing vector database functionality.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(GoogleGenAIPlugin.create(apiKey))
 *     .plugin(
 *         PineconePlugin.builder()
 *             .apiKey(System.getenv("PINECONE_API_KEY"))
 *             .addIndex(
 *                 PineconeIndexConfig.builder()
 *                     .indexName("my-index")
 *                     .embedderName("googleai/text-embedding-004")
 *                     .build())
 *             .build())
 *     .build();
 * }</pre>
 */
public class PineconePlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(PineconePlugin.class);
  private static final String PLUGIN_NAME = "pinecone";

  private final String apiKey;
  private final List<PineconeIndexConfig> indexConfigs;
  private final Pinecone externalClient;

  private Pinecone client;
  private final Map<String, PineconeVectorStore> vectorStores = new HashMap<>();

  private PineconePlugin(Builder builder) {
    this.apiKey = builder.apiKey;
    this.indexConfigs = new ArrayList<>(builder.indexConfigs);
    this.externalClient = builder.externalClient;
  }

  /** Creates a new builder for PineconePlugin. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a PineconePlugin with the specified API key.
   *
   * @param apiKey the Pinecone API key
   * @return a builder for further configuration
   */
  public static Builder create(String apiKey) {
    return new Builder().apiKey(apiKey);
  }

  @Override
  public String getName() {
    return PLUGIN_NAME;
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    throw new IllegalStateException(
        "PineconePlugin requires a Registry to resolve embedders. Use init(registry) instead.");
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Create or use provided Pinecone client
    client = externalClient != null ? externalClient : new Pinecone.Builder(apiKey).build();

    // Create vector stores and actions for each index config
    for (PineconeIndexConfig indexConfig : indexConfigs) {
      // Resolve embedder
      String embedderKey = ActionType.EMBEDDER.keyFromName(indexConfig.getEmbedderName());
      Action<?, ?, ?> embedderAction = registry.lookupAction(embedderKey);

      if (embedderAction == null) {
        throw new IllegalStateException(
            "Embedder not found: "
                + indexConfig.getEmbedderName()
                + ". Make sure the embedder plugin is registered before PineconePlugin.");
      }

      if (!(embedderAction instanceof Embedder)) {
        throw new IllegalStateException(
            "Action " + indexConfig.getEmbedderName() + " is not an Embedder");
      }

      Embedder embedder = (Embedder) embedderAction;

      // Create vector store
      PineconeVectorStore vectorStore = new PineconeVectorStore(client, indexConfig, embedder);

      // Generate unique key for this index+namespace combination
      String storeKey = indexConfig.getIndexName();
      if (indexConfig.getNamespace() != null && !indexConfig.getNamespace().isEmpty()) {
        storeKey += "/" + indexConfig.getNamespace();
      }
      vectorStores.put(storeKey, vectorStore);

      // Create retriever action
      String retrieverName = PLUGIN_NAME + "/" + storeKey;
      Retriever retriever =
          Retriever.builder().name(retrieverName).handler(vectorStore::retrieve).build();
      actions.add(retriever);
      logger.info("Registered Pinecone retriever: {}", retrieverName);

      // Create indexer action
      String indexerName = PLUGIN_NAME + "/" + storeKey;
      Indexer indexer = Indexer.builder().name(indexerName).handler(vectorStore::index).build();
      actions.add(indexer);
      logger.info("Registered Pinecone indexer: {}", indexerName);
    }

    return actions;
  }

  /**
   * Gets a vector store by index name.
   *
   * @param indexName the index name
   * @return the vector store, or null if not found
   */
  public PineconeVectorStore getVectorStore(String indexName) {
    return vectorStores.get(indexName);
  }

  /**
   * Gets a vector store by index name and namespace.
   *
   * @param indexName the index name
   * @param namespace the namespace
   * @return the vector store, or null if not found
   */
  public PineconeVectorStore getVectorStore(String indexName, String namespace) {
    String key = indexName;
    if (namespace != null && !namespace.isEmpty()) {
      key += "/" + namespace;
    }
    return vectorStores.get(key);
  }

  /** Gets the Pinecone client. */
  public Pinecone getClient() {
    return client;
  }

  /** Builder for PineconePlugin. */
  public static class Builder {
    private String apiKey;
    private final List<PineconeIndexConfig> indexConfigs = new ArrayList<>();
    private Pinecone externalClient;

    /** Sets the Pinecone API key (required unless externalClient is provided). */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /** Adds an index configuration. */
    public Builder addIndex(PineconeIndexConfig indexConfig) {
      this.indexConfigs.add(indexConfig);
      return this;
    }

    /** Sets an external Pinecone client to use instead of creating one. */
    public Builder client(Pinecone client) {
      this.externalClient = client;
      return this;
    }

    /**
     * Builds the PineconePlugin.
     *
     * @throws IllegalStateException if required configuration is missing
     */
    public PineconePlugin build() {
      if (externalClient == null) {
        if (apiKey == null || apiKey.isBlank()) {
          throw new IllegalStateException(
              "apiKey is required when not providing an external Pinecone client");
        }
      }
      if (indexConfigs.isEmpty()) {
        throw new IllegalStateException("At least one index configuration is required");
      }
      return new PineconePlugin(this);
    }
  }
}
