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

package com.google.genkit.plugins.postgresql;

import com.google.genkit.ai.*;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL plugin for Genkit providing vector database functionality using pgvector.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(GoogleGenAIPlugin.create(apiKey))
 *     .plugin(
 *         PostgresPlugin.builder()
 *             .connectionString("jdbc:postgresql://localhost:5432/mydb")
 *             .username("user")
 *             .password("pass")
 *             .addTable(
 *                 PostgresTableConfig.builder()
 *                     .tableName("documents")
 *                     .embedderName("googleai/text-embedding-004")
 *                     .build())
 *             .build())
 *     .build();
 * }</pre>
 */
public class PostgresPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(PostgresPlugin.class);
  private static final String PLUGIN_NAME = "postgresql";

  private final String connectionString;
  private final String username;
  private final String password;
  private final List<PostgresTableConfig> tableConfigs;
  private final Map<String, Object> hikariProperties;
  private final DataSource externalDataSource;

  private HikariDataSource managedDataSource;
  private final Map<String, PostgresVectorStore> vectorStores = new HashMap<>();

  private PostgresPlugin(Builder builder) {
    this.connectionString = builder.connectionString;
    this.username = builder.username;
    this.password = builder.password;
    this.tableConfigs = new ArrayList<>(builder.tableConfigs);
    this.hikariProperties = new HashMap<>(builder.hikariProperties);
    this.externalDataSource = builder.externalDataSource;
  }

  /** Creates a new builder for PostgresPlugin. */
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
        "PostgresPlugin requires a Registry to resolve embedders. Use init(registry) instead.");
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Create or use provided data source
    DataSource dataSource = externalDataSource != null ? externalDataSource : createDataSource();

    // Create vector stores and actions for each table config
    for (PostgresTableConfig tableConfig : tableConfigs) {
      // Resolve embedder
      String embedderKey = ActionType.EMBEDDER.keyFromName(tableConfig.getEmbedderName());
      Action<?, ?, ?> embedderAction = registry.lookupAction(embedderKey);

      if (embedderAction == null) {
        throw new IllegalStateException(
            "Embedder not found: "
                + tableConfig.getEmbedderName()
                + ". Make sure the embedder plugin is registered before PostgresPlugin.");
      }

      if (!(embedderAction instanceof Embedder)) {
        throw new IllegalStateException(
            "Action " + tableConfig.getEmbedderName() + " is not an Embedder");
      }

      Embedder embedder = (Embedder) embedderAction;

      // Create vector store
      PostgresVectorStore vectorStore = new PostgresVectorStore(dataSource, tableConfig, embedder);
      vectorStores.put(tableConfig.getTableName(), vectorStore);

      // Create retriever action
      String retrieverName = PLUGIN_NAME + "/" + tableConfig.getTableName();
      Retriever retriever =
          Retriever.builder().name(retrieverName).handler(vectorStore::retrieve).build();
      actions.add(retriever);
      logger.info("Registered PostgreSQL retriever: {}", retrieverName);

      // Create indexer action
      String indexerName = PLUGIN_NAME + "/" + tableConfig.getTableName();
      Indexer indexer = Indexer.builder().name(indexerName).handler(vectorStore::index).build();
      actions.add(indexer);
      logger.info("Registered PostgreSQL indexer: {}", indexerName);
    }

    return actions;
  }

  private DataSource createDataSource() {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(connectionString);

    if (username != null) {
      hikariConfig.setUsername(username);
    }
    if (password != null) {
      hikariConfig.setPassword(password);
    }

    // Apply custom properties
    hikariProperties.forEach(
        (key, value) -> {
          hikariConfig.addDataSourceProperty(key, value);
        });

    // Set sensible defaults
    if (!hikariProperties.containsKey("maximumPoolSize")) {
      hikariConfig.setMaximumPoolSize(10);
    }
    if (!hikariProperties.containsKey("minimumIdle")) {
      hikariConfig.setMinimumIdle(2);
    }
    if (!hikariProperties.containsKey("connectionTimeout")) {
      hikariConfig.setConnectionTimeout(30000);
    }

    managedDataSource = new HikariDataSource(hikariConfig);
    logger.info("Created HikariCP connection pool for PostgreSQL");

    return managedDataSource;
  }

  /**
   * Gets a vector store by table name.
   *
   * @param tableName the table name
   * @return the vector store, or null if not found
   */
  public PostgresVectorStore getVectorStore(String tableName) {
    return vectorStores.get(tableName);
  }

  /** Closes the plugin and releases resources. */
  public void close() {
    if (managedDataSource != null && !managedDataSource.isClosed()) {
      managedDataSource.close();
      logger.info("Closed PostgreSQL connection pool");
    }
  }

  /** Builder for PostgresPlugin. */
  public static class Builder {
    private String connectionString;
    private String username;
    private String password;
    private final List<PostgresTableConfig> tableConfigs = new ArrayList<>();
    private final Map<String, Object> hikariProperties = new HashMap<>();
    private DataSource externalDataSource;

    /**
     * Sets the JDBC connection string (required unless externalDataSource is provided).
     *
     * <p>Example: "jdbc:postgresql://localhost:5432/mydb"
     */
    public Builder connectionString(String connectionString) {
      this.connectionString = connectionString;
      return this;
    }

    /** Sets the database username. */
    public Builder username(String username) {
      this.username = username;
      return this;
    }

    /** Sets the database password. */
    public Builder password(String password) {
      this.password = password;
      return this;
    }

    /** Adds a table configuration. */
    public Builder addTable(PostgresTableConfig tableConfig) {
      this.tableConfigs.add(tableConfig);
      return this;
    }

    /**
     * Sets a HikariCP connection pool property.
     *
     * <p>Common properties:
     *
     * <ul>
     *   <li>maximumPoolSize - maximum pool size (default: 10)
     *   <li>minimumIdle - minimum idle connections (default: 2)
     *   <li>connectionTimeout - connection timeout in ms (default: 30000)
     *   <li>idleTimeout - idle connection timeout in ms
     *   <li>maxLifetime - maximum connection lifetime in ms
     * </ul>
     */
    public Builder hikariProperty(String key, Object value) {
      this.hikariProperties.put(key, value);
      return this;
    }

    /**
     * Sets an external DataSource to use instead of creating one.
     *
     * <p>This is useful when you want to manage the connection pool yourself or use a different
     * connection pool implementation.
     */
    public Builder dataSource(DataSource dataSource) {
      this.externalDataSource = dataSource;
      return this;
    }

    /**
     * Creates a builder configured for a local PostgreSQL instance.
     *
     * @param database the database name
     * @param username the username
     * @param password the password
     * @return a pre-configured builder
     */
    public static Builder local(String database, String username, String password) {
      return new Builder()
          .connectionString("jdbc:postgresql://localhost:5432/" + database)
          .username(username)
          .password(password);
    }

    /**
     * Builds the PostgresPlugin.
     *
     * @throws IllegalStateException if required configuration is missing
     */
    public PostgresPlugin build() {
      if (externalDataSource == null) {
        if (connectionString == null || connectionString.isBlank()) {
          throw new IllegalStateException(
              "connectionString is required when not providing an external DataSource");
        }
      }
      if (tableConfigs.isEmpty()) {
        throw new IllegalStateException("At least one table configuration is required");
      }
      return new PostgresPlugin(this);
    }
  }
}
