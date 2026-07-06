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

package com.google.genkit.plugins.mongodb.session;

import com.google.genkit.ai.agent.SessionStoreOptions;
import java.util.function.Function;

/**
 * Configuration for {@link MongoSessionStore}.
 *
 * <p>The store persists all records in a single MongoDB collection (default database {@value
 * #DEFAULT_DATABASE}, collection {@value #DEFAULT_COLLECTION}). Each document's {@code _id}
 * combines the per-tenant prefix (default {@code "global"}) with the record id; the record kind is
 * discriminated by the id. Each document carries a {@code version} field used for optimistic
 * concurrency.
 *
 * <p>The default {@link #getShardSize()} is {@value #DEFAULT_SHARD_SIZE} bytes, kept safely under
 * MongoDB's 16&nbsp;MB document-size limit; because the store forces a checkpoint whenever a diff
 * would exceed the shard size, diff documents stay bounded too.
 */
public final class MongoSessionStoreOptions {

  /** Default database name. */
  public static final String DEFAULT_DATABASE = "genkit";

  /** Default collection name. */
  public static final String DEFAULT_COLLECTION = "genkit_sessions";

  /** Default number of turns between full checkpoints. */
  public static final int DEFAULT_CHECKPOINT_INTERVAL = 25;

  /** Default shard size in bytes for checkpoint state (1 MiB, under the 16 MB document cap). */
  public static final int DEFAULT_SHARD_SIZE = 1024 * 1024;

  /** Default subscription poll interval in milliseconds. */
  public static final long DEFAULT_POLL_INTERVAL_MS = 2000L;

  private final String databaseName;
  private final String collectionName;
  private final int checkpointInterval;
  private final int shardSize;
  private final Function<SessionStoreOptions, String> snapshotPathPrefix;
  private final long pollIntervalMs;

  private MongoSessionStoreOptions(Builder builder) {
    this.databaseName = builder.databaseName;
    this.collectionName = builder.collectionName;
    this.checkpointInterval = builder.checkpointInterval;
    this.shardSize = builder.shardSize;
    this.snapshotPathPrefix = builder.snapshotPathPrefix;
    this.pollIntervalMs = builder.pollIntervalMs;
  }

  /**
   * Returns the MongoDB database name (default {@value #DEFAULT_DATABASE}).
   *
   * @return the database name
   */
  public String getDatabaseName() {
    return databaseName;
  }

  /**
   * Returns the MongoDB collection name (default {@value #DEFAULT_COLLECTION}).
   *
   * @return the collection name
   */
  public String getCollectionName() {
    return collectionName;
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
   * Returns the function that derives the per-tenant prefix from the per-request store options
   * (default {@code o -> "global"}).
   *
   * @return the prefix function
   */
  public Function<SessionStoreOptions, String> getSnapshotPathPrefix() {
    return snapshotPathPrefix;
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
   * @return a {@code MongoSessionStoreOptions} with all defaults
   */
  public static MongoSessionStoreOptions defaults() {
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

  /** Builder for {@link MongoSessionStoreOptions}. */
  public static final class Builder {
    private String databaseName = DEFAULT_DATABASE;
    private String collectionName = DEFAULT_COLLECTION;
    private int checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
    private int shardSize = DEFAULT_SHARD_SIZE;
    private Function<SessionStoreOptions, String> snapshotPathPrefix = o -> "global";
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    private Builder() {}

    /**
     * Sets the MongoDB database name.
     *
     * @param databaseName the database name
     * @return this builder
     */
    public Builder databaseName(String databaseName) {
      this.databaseName = databaseName;
      return this;
    }

    /**
     * Sets the MongoDB collection name.
     *
     * @param collectionName the collection name
     * @return this builder
     */
    public Builder collectionName(String collectionName) {
      this.collectionName = collectionName;
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
     * Sets the shard size in bytes for checkpoint state (must stay under the 16 MB document cap).
     *
     * @param shardSize the shard size in bytes (must be {@code >= 1})
     * @return this builder
     */
    public Builder shardSize(int shardSize) {
      this.shardSize = shardSize;
      return this;
    }

    /**
     * Sets the function that derives the per-tenant prefix.
     *
     * @param snapshotPathPrefix the prefix function
     * @return this builder
     */
    public Builder snapshotPathPrefix(Function<SessionStoreOptions, String> snapshotPathPrefix) {
      this.snapshotPathPrefix = snapshotPathPrefix;
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
     * Builds a new {@code MongoSessionStoreOptions}.
     *
     * @return a new options instance
     */
    public MongoSessionStoreOptions build() {
      if (databaseName == null || databaseName.isBlank()) {
        throw new IllegalArgumentException("databaseName must be non-empty");
      }
      if (collectionName == null || collectionName.isBlank()) {
        throw new IllegalArgumentException("collectionName must be non-empty");
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
      return new MongoSessionStoreOptions(this);
    }
  }
}
