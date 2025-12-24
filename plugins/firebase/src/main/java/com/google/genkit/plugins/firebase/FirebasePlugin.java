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

package com.google.genkit.plugins.firebase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.genkit.ai.*;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import com.google.genkit.plugins.firebase.retriever.FirestoreRetrieverConfig;
import com.google.genkit.plugins.firebase.retriever.FirestoreVectorStore;
import com.google.genkit.plugins.firebase.telemetry.FirebaseTelemetry;

/**
 * Firebase plugin for Genkit providing integration with Firebase services.
 * 
 * <p>
 * This plugin provides:
 * <ul>
 * <li>Firestore vector search for RAG (Retrieval Augmented Generation)</li>
 * <li>Firebase Cloud Functions integration for deploying flows</li>
 * <li>Firebase telemetry for Google Cloud observability</li>
 * </ul>
 * 
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * Genkit genkit = Genkit.builder().plugin(GoogleGenAIPlugin.create(apiKey))
 * 		.plugin(FirebasePlugin.builder().projectId("my-project").enableTelemetry(true)
 * 				.addRetriever(FirestoreRetrieverConfig.builder().name("my-docs").collection("documents")
 * 						.embedderName("googleai/text-embedding-004").vectorField("embedding").contentField("content")
 * 						.build())
 * 				.build())
 * 		.build();
 * }</pre>
 */
public class FirebasePlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(FirebasePlugin.class);
  public static final String PROVIDER = "firebase";

  private final FirebasePluginConfig config;
  private FirebaseApp firebaseApp;
  private Firestore firestore;
  private FirebaseTelemetry telemetry;

  private FirebasePlugin(FirebasePluginConfig config) {
    this.config = config;
  }

  /**
   * Creates a builder for FirebasePlugin.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a simple FirebasePlugin with just the project ID.
   *
   * @param projectId
   *            the Firebase project ID
   * @return a new FirebasePlugin
   */
  public static FirebasePlugin create(String projectId) {
    return builder().projectId(projectId).build();
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
      // Initialize Firebase only if needed (for Firestore retrievers)
      boolean needsFirestore = !config.getRetrieverConfigs().isEmpty();
      if (needsFirestore) {
        initializeFirebase();
      } else {
        logger.info("Firebase app initialized for project: {}", config.getProjectId());
      }

      // Initialize telemetry if enabled
      if (config.isEnableTelemetry()) {
        initializeTelemetry();
      }

      // Initialize Firestore retrievers and indexers
      for (FirestoreRetrieverConfig retrieverConfig : config.getRetrieverConfigs()) {
        actions.addAll(initializeRetriever(registry, retrieverConfig));
      }

      logger.info("Firebase plugin initialized successfully for project: {}", config.getProjectId());

    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize Firebase plugin: " + e.getMessage(), e);
    }

    return actions;
  }

  /**
   * Initializes the Firebase Admin SDK.
   */
  private void initializeFirebase() throws IOException {
    // Check if Firebase is already initialized
    try {
      firebaseApp = FirebaseApp.getInstance();
      logger.debug("Using existing Firebase app");
    } catch (IllegalStateException e) {
      // Firebase not initialized, create new instance
      FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder();

      if (config.getCredentials() != null) {
        optionsBuilder.setCredentials(config.getCredentials());
      } else {
        // Use application default credentials
        optionsBuilder.setCredentials(GoogleCredentials.getApplicationDefault());
      }

      if (config.getProjectId() != null) {
        optionsBuilder.setProjectId(config.getProjectId());
      }

      if (config.getDatabaseUrl() != null) {
        optionsBuilder.setDatabaseUrl(config.getDatabaseUrl());
      }

      firebaseApp = FirebaseApp.initializeApp(optionsBuilder.build());
      logger.info("Firebase app initialized for project: {}", config.getProjectId());
    }

    // Get Firestore instance
    firestore = FirestoreClient.getFirestore(firebaseApp);
  }

  /**
   * Initializes Firebase telemetry for Google Cloud observability.
   */
  private void initializeTelemetry() {
    telemetry = FirebaseTelemetry.builder().projectId(config.getProjectId())
        .forceDevExport(config.isForceDevExport())
        .metricExportIntervalMillis(config.getMetricExportIntervalMillis()).build();

    telemetry.enable();
    logger.info("Firebase telemetry enabled for project: {}", config.getProjectId());
  }

  /**
   * Initializes a Firestore retriever and indexer.
   */
  private List<Action<?, ?, ?>> initializeRetriever(Registry registry, FirestoreRetrieverConfig retrieverConfig) {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Resolve embedder
    Embedder embedder = resolveEmbedder(registry, retrieverConfig);

    // Create vector store
    FirestoreVectorStore vectorStore = new FirestoreVectorStore(firestore, retrieverConfig, embedder);

    // Create and add retriever
    Retriever retriever = vectorStore.createRetriever();
    actions.add(retriever);

    // Create and add indexer
    Indexer indexer = vectorStore.createIndexer();
    actions.add(indexer);

    logger.info("Registered Firestore retriever and indexer: {}", retrieverConfig.getName());

    return actions;
  }

  /**
   * Resolves an embedder by name or uses the provided embedder.
   */
  private Embedder resolveEmbedder(Registry registry, FirestoreRetrieverConfig config) {
    if (config.getEmbedder() != null) {
      return config.getEmbedder();
    }

    if (config.getEmbedderName() == null) {
      throw new IllegalStateException(
          "Either embedder or embedderName must be specified for Firestore retriever: " + config.getName());
    }

    if (registry == null) {
      throw new IllegalStateException(
          "Registry is required to resolve embedder by name: " + config.getEmbedderName());
    }

    String embedderKey = ActionType.EMBEDDER.keyFromName(config.getEmbedderName());
    Action<?, ?, ?> embedderAction = registry.lookupAction(embedderKey);

    if (embedderAction == null) {
      throw new IllegalStateException("Embedder not found: " + config.getEmbedderName()
          + ". Make sure the embedder plugin is registered before FirebasePlugin.");
    }

    if (!(embedderAction instanceof Embedder)) {
      throw new IllegalStateException("Action " + config.getEmbedderName() + " is not an Embedder");
    }

    logger.debug("Resolved embedder: {} for retriever: {}", config.getEmbedderName(), config.getName());
    return (Embedder) embedderAction;
  }

  /**
   * Gets the Firestore instance.
   *
   * @return the Firestore instance
   */
  public Firestore getFirestore() {
    return firestore;
  }

  /**
   * Gets the FirebaseApp instance.
   *
   * @return the FirebaseApp instance
   */
  public FirebaseApp getFirebaseApp() {
    return firebaseApp;
  }

  /**
   * Gets the Firebase telemetry instance.
   *
   * @return the telemetry instance, or null if not enabled
   */
  public FirebaseTelemetry getTelemetry() {
    return telemetry;
  }

  /**
   * Builder for FirebasePlugin.
   */
  public static class Builder {
    private final FirebasePluginConfig.Builder configBuilder = FirebasePluginConfig.builder();

    /**
     * Sets the Firebase project ID.
     *
     * @param projectId
     *            the project ID
     * @return this builder
     */
    public Builder projectId(String projectId) {
      configBuilder.projectId(projectId);
      return this;
    }

    /**
     * Sets the Google credentials.
     *
     * @param credentials
     *            the credentials
     * @return this builder
     */
    public Builder credentials(GoogleCredentials credentials) {
      configBuilder.credentials(credentials);
      return this;
    }

    /**
     * Sets the Firebase Realtime Database URL.
     *
     * @param databaseUrl
     *            the database URL
     * @return this builder
     */
    public Builder databaseUrl(String databaseUrl) {
      configBuilder.databaseUrl(databaseUrl);
      return this;
    }

    /**
     * Enables or disables Firebase telemetry.
     *
     * @param enable
     *            true to enable telemetry
     * @return this builder
     */
    public Builder enableTelemetry(boolean enable) {
      configBuilder.enableTelemetry(enable);
      return this;
    }

    /**
     * Forces telemetry export in development mode.
     *
     * @param forceDevExport
     *            true to force export in dev mode
     * @return this builder
     */
    public Builder forceDevExport(boolean forceDevExport) {
      configBuilder.forceDevExport(forceDevExport);
      return this;
    }

    /**
     * Sets the metric export interval in milliseconds.
     *
     * @param intervalMillis
     *            the interval in milliseconds
     * @return this builder
     */
    public Builder metricExportIntervalMillis(long intervalMillis) {
      configBuilder.metricExportIntervalMillis(intervalMillis);
      return this;
    }

    /**
     * Adds a Firestore retriever configuration.
     *
     * @param config
     *            the retriever configuration
     * @return this builder
     */
    public Builder addRetriever(FirestoreRetrieverConfig config) {
      configBuilder.addRetrieverConfig(config);
      return this;
    }

    /**
     * Builds the FirebasePlugin.
     *
     * @return the configured plugin
     */
    public FirebasePlugin build() {
      return new FirebasePlugin(configBuilder.build());
    }
  }
}
