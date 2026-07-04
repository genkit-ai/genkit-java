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

package com.google.genkit.plugins.azurefoundry.session;

import com.google.genkit.ai.agent.SessionStoreOptions;
import java.util.function.Function;

/**
 * Configuration for {@link CosmosSessionStore}.
 *
 * <p>The store persists all documents in a single Cosmos DB container (default database {@value
 * #DEFAULT_DATABASE}, container {@value #DEFAULT_CONTAINER}) partitioned by {@code /pk} (the
 * per-tenant prefix). Each document's {@code id} discriminates snapshot, shard, and pointer
 * records. All documents for a tenant share the same {@code pk}, derived from {@link
 * #getSnapshotPathPrefix()} (default {@code "global"}).
 *
 * <p>The default {@link #getShardSize()} is {@value #DEFAULT_SHARD_SIZE} bytes, kept safely under
 * the Cosmos DB 2&nbsp;MB document-size limit; because the store forces a checkpoint whenever a
 * diff would exceed the shard size, diff documents stay bounded too.
 */
public final class CosmosSessionStoreOptions {

  /** Default database name. */
  public static final String DEFAULT_DATABASE = "genkit";

  /** Default container name. */
  public static final String DEFAULT_CONTAINER = "genkit-sessions";

  /** Default number of turns between full checkpoints. */
  public static final int DEFAULT_CHECKPOINT_INTERVAL = 25;

  /** Default shard size in bytes for checkpoint state (1 MiB, under the 2 MB document cap). */
  public static final int DEFAULT_SHARD_SIZE = 1024 * 1024;

  /** Default subscription poll interval in milliseconds. */
  public static final long DEFAULT_POLL_INTERVAL_MS = 2000L;

  private final String databaseName;
  private final String containerName;
  private final int checkpointInterval;
  private final int shardSize;
  private final Function<SessionStoreOptions, String> snapshotPathPrefix;
  private final boolean createIfNotExists;
  private final long pollIntervalMs;

  private CosmosSessionStoreOptions(Builder builder) {
    this.databaseName = builder.databaseName;
    this.containerName = builder.containerName;
    this.checkpointInterval = builder.checkpointInterval;
    this.shardSize = builder.shardSize;
    this.snapshotPathPrefix = builder.snapshotPathPrefix;
    this.createIfNotExists = builder.createIfNotExists;
    this.pollIntervalMs = builder.pollIntervalMs;
  }

  /**
   * Returns the Cosmos DB database name (default {@value #DEFAULT_DATABASE}).
   *
   * @return the database name
   */
  public String getDatabaseName() {
    return databaseName;
  }

  /**
   * Returns the Cosmos DB container name (default {@value #DEFAULT_CONTAINER}).
   *
   * @return the container name
   */
  public String getContainerName() {
    return containerName;
  }

  /**
   * Returns the number of turns between full checkpoints (default {@value
   * #DEFAULT_CHECKPOINT_INTERVAL}).
   *
   * @return the checkpoint interval
   */
  public int getCheckpointInterval() {
    return checkpointInterval;
  }

  /**
   * Returns the shard size in bytes for checkpoint state (default {@value #DEFAULT_SHARD_SIZE}).
   *
   * @return the shard size in bytes
   */
  public int getShardSize() {
    return shardSize;
  }

  /**
   * Returns the function that derives the per-tenant partition-key prefix from the per-request
   * store options (default {@code o -> "global"}).
   *
   * @return the prefix function
   */
  public Function<SessionStoreOptions, String> getSnapshotPathPrefix() {
    return snapshotPathPrefix;
  }

  /**
   * Returns whether the store should create the database and container on first use if they do not
   * exist (default {@code false}).
   *
   * @return {@code true} if the database/container should be created when missing
   */
  public boolean isCreateIfNotExists() {
    return createIfNotExists;
  }

  /**
   * Returns the subscription poll interval in milliseconds (default {@value
   * #DEFAULT_POLL_INTERVAL_MS}).
   *
   * @return the poll interval in milliseconds
   */
  public long getPollIntervalMs() {
    return pollIntervalMs;
  }

  /**
   * Returns default options.
   *
   * @return a {@code CosmosSessionStoreOptions} with all defaults
   */
  public static CosmosSessionStoreOptions defaults() {
    return builder().build();
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link CosmosSessionStoreOptions}. */
  public static final class Builder {
    private String databaseName = DEFAULT_DATABASE;
    private String containerName = DEFAULT_CONTAINER;
    private int checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
    private int shardSize = DEFAULT_SHARD_SIZE;
    private Function<SessionStoreOptions, String> snapshotPathPrefix = o -> "global";
    private boolean createIfNotExists = false;
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    private Builder() {}

    /**
     * Sets the Cosmos DB database name.
     *
     * @param databaseName the database name
     * @return this builder
     */
    public Builder databaseName(String databaseName) {
      this.databaseName = databaseName;
      return this;
    }

    /**
     * Sets the Cosmos DB container name.
     *
     * @param containerName the container name
     * @return this builder
     */
    public Builder containerName(String containerName) {
      this.containerName = containerName;
      return this;
    }

    /**
     * Sets the number of turns between full checkpoints.
     *
     * @param checkpointInterval the checkpoint interval (must be {@code >= 1})
     * @return this builder
     */
    public Builder checkpointInterval(int checkpointInterval) {
      this.checkpointInterval = checkpointInterval;
      return this;
    }

    /**
     * Sets the shard size in bytes for checkpoint state (must stay under the 2 MB document cap).
     *
     * @param shardSize the shard size in bytes (must be {@code >= 1})
     * @return this builder
     */
    public Builder shardSize(int shardSize) {
      this.shardSize = shardSize;
      return this;
    }

    /**
     * Sets the function that derives the per-tenant partition-key prefix.
     *
     * @param snapshotPathPrefix the prefix function
     * @return this builder
     */
    public Builder snapshotPathPrefix(Function<SessionStoreOptions, String> snapshotPathPrefix) {
      this.snapshotPathPrefix = snapshotPathPrefix;
      return this;
    }

    /**
     * Sets whether to create the database and container on first use if they do not exist.
     *
     * @param createIfNotExists whether to create the database/container when missing
     * @return this builder
     */
    public Builder createIfNotExists(boolean createIfNotExists) {
      this.createIfNotExists = createIfNotExists;
      return this;
    }

    /**
     * Sets the subscription poll interval in milliseconds.
     *
     * @param pollIntervalMs the poll interval (must be {@code >= 1})
     * @return this builder
     */
    public Builder pollIntervalMs(long pollIntervalMs) {
      this.pollIntervalMs = pollIntervalMs;
      return this;
    }

    /**
     * Builds a new {@code CosmosSessionStoreOptions}.
     *
     * @return a new options instance
     */
    public CosmosSessionStoreOptions build() {
      if (databaseName == null || databaseName.isBlank()) {
        throw new IllegalArgumentException("databaseName must be non-empty");
      }
      if (containerName == null || containerName.isBlank()) {
        throw new IllegalArgumentException("containerName must be non-empty");
      }
      if (checkpointInterval < 1) {
        throw new IllegalArgumentException("checkpointInterval must be >= 1");
      }
      if (shardSize < 1) {
        throw new IllegalArgumentException("shardSize must be >= 1");
      }
      if (snapshotPathPrefix == null) {
        throw new IllegalArgumentException("snapshotPathPrefix must be non-null");
      }
      if (pollIntervalMs < 1) {
        throw new IllegalArgumentException("pollIntervalMs must be >= 1");
      }
      return new CosmosSessionStoreOptions(this);
    }
  }
}
