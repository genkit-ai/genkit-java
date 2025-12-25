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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.ai.Embedder;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;

import io.weaviate.client.Config;
import io.weaviate.client.WeaviateAuthClient;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.v1.auth.exception.AuthException;

/**
 * Weaviate plugin for Genkit providing vector database integration.
 *
 * <p>
 * This plugin provides:
 * <ul>
 * <li>Weaviate vector similarity search for retrieval</li>
 * <li>Document indexing with automatic embedding generation</li>
 * <li>Support for both local and Weaviate Cloud instances</li>
 * <li>Configurable distance measures (cosine, L2, dot product)</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * Genkit genkit = Genkit.builder().plugin(GoogleGenAIPlugin.create(apiKey))
 * 		.plugin(WeaviatePlugin.builder().host("localhost").port(8080).addCollection(WeaviateCollectionConfig
 * 				.builder().name("documents").embedderName("googleai/text-embedding-004").build()).build())
 * 		.build();
 *
 * // Index documents
 * genkit.index("weaviate/documents", documents);
 *
 * // Retrieve similar documents
 * List<Document> results = genkit.retrieve("weaviate/documents", "search query");
 * }</pre>
 */
public class WeaviatePlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(WeaviatePlugin.class);
  public static final String PROVIDER = "weaviate";

  private final String host;
  private final int port;
  private final int grpcPort;
  private final boolean secure;
  private final String apiKey;
  private final String scheme;
  private final List<WeaviateCollectionConfig> collections;

  private WeaviateClient client;

  private WeaviatePlugin(Builder builder) {
    this.host = builder.host;
    this.port = builder.port;
    this.grpcPort = builder.grpcPort;
    this.secure = builder.secure;
    this.apiKey = builder.apiKey;
    this.scheme = builder.secure ? "https" : "http";
    this.collections = new ArrayList<>(builder.collections);
  }

  /**
   * Creates a builder for WeaviatePlugin.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a simple WeaviatePlugin for local development.
   *
   * @param host
   *            the Weaviate host
   * @return a new WeaviatePlugin
   */
  public static Builder local(String host) {
    return builder().host(host).port(8080).secure(false);
  }

  /**
   * Creates a simple WeaviatePlugin for local development on default port.
   *
   * @return a new WeaviatePlugin builder
   */
  public static Builder local() {
    return local("localhost");
  }

  @Override
  public String getName() {
    return PROVIDER;
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    return initializePlugin(null);
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    return initializePlugin(registry);
  }

  private List<Action<?, ?, ?>> initializePlugin(Registry registry) {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    try {
      // Initialize Weaviate client
      initializeClient();

      // Initialize collections
      for (WeaviateCollectionConfig collectionConfig : collections) {
        actions.addAll(initializeCollection(registry, collectionConfig));
      }

      logger.info("Weaviate plugin initialized successfully for {}://{}:{}", scheme, host, port);

    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize Weaviate plugin: " + e.getMessage(), e);
    }

    return actions;
  }

  /**
   * Initializes the Weaviate client.
   */
  private void initializeClient() throws AuthException {
    String hostUrl = scheme + "://" + host;
    if (port > 0) {
      hostUrl += ":" + port;
    }

    Config config = new Config(scheme, host + ":" + port);

    if (apiKey != null && !apiKey.isEmpty()) {
      client = WeaviateAuthClient.apiKey(config, apiKey);
      logger.info("Weaviate client initialized with API key authentication");
    } else {
      client = new WeaviateClient(config);
      logger.info("Weaviate client initialized without authentication");
    }
  }

  /**
   * Initializes a collection with retriever and indexer.
   */
  private List<Action<?, ?, ?>> initializeCollection(Registry registry, WeaviateCollectionConfig config) {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Resolve embedder
    Embedder embedder = resolveEmbedder(registry, config);

    // Create vector store
    WeaviateVectorStore vectorStore = new WeaviateVectorStore(client, config, embedder);

    // Create retriever and indexer
    actions.add(vectorStore.createRetriever());
    actions.add(vectorStore.createIndexer());

    logger.info("Initialized Weaviate collection: {} with embedder: {}", config.getName(),
        config.getEmbedderName() != null ? config.getEmbedderName() : "direct");

    return actions;
  }

  /**
   * Resolves the embedder from config or registry.
   */
  private Embedder resolveEmbedder(Registry registry, WeaviateCollectionConfig config) {
    if (config.getEmbedder() != null) {
      return config.getEmbedder();
    }

    if (registry != null && config.getEmbedderName() != null) {
      @SuppressWarnings("unchecked")
      Action<?, ?, ?> action = registry.lookupAction(ActionType.EMBEDDER, config.getEmbedderName());
      if (action instanceof Embedder) {
        return (Embedder) action;
      }
    }

    throw new RuntimeException("Could not resolve embedder for collection: " + config.getName()
        + ". Either provide an embedder directly or ensure the embedder '" + config.getEmbedderName()
        + "' is registered.");
  }

  /**
   * Builder for WeaviatePlugin.
   */
  public static class Builder {
    private String host = "localhost";
    private int port = 8080;
    private int grpcPort = 50051;
    private boolean secure = false;
    private String apiKey;
    private final List<WeaviateCollectionConfig> collections = new ArrayList<>();

    /**
     * Sets the Weaviate host.
     *
     * @param host
     *            the host (without protocol)
     * @return this builder
     */
    public Builder host(String host) {
      this.host = host;
      return this;
    }

    /**
     * Sets the HTTP port.
     *
     * @param port
     *            the port
     * @return this builder
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Sets the gRPC port.
     *
     * @param grpcPort
     *            the gRPC port
     * @return this builder
     */
    public Builder grpcPort(int grpcPort) {
      this.grpcPort = grpcPort;
      return this;
    }

    /**
     * Sets whether to use secure connection (HTTPS).
     *
     * @param secure
     *            true for HTTPS
     * @return this builder
     */
    public Builder secure(boolean secure) {
      this.secure = secure;
      return this;
    }

    /**
     * Sets the API key for Weaviate Cloud authentication.
     *
     * @param apiKey
     *            the API key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Adds a collection configuration.
     *
     * @param config
     *            the collection config
     * @return this builder
     */
    public Builder addCollection(WeaviateCollectionConfig config) {
      this.collections.add(config);
      return this;
    }

    /**
     * Builds the WeaviatePlugin.
     *
     * @return the plugin
     */
    public WeaviatePlugin build() {
      if (collections.isEmpty()) {
        throw new IllegalStateException("At least one collection must be configured");
      }
      return new WeaviatePlugin(this);
    }
  }
}
