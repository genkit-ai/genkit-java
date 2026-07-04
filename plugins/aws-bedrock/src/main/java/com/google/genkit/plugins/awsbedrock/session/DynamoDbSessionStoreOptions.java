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

package com.google.genkit.plugins.awsbedrock.session;

import com.google.genkit.ai.agent.SessionStoreOptions;
import java.util.function.Function;

/**
 * Configuration for {@link DynamoDbSessionStore}.
 *
 * <p>The store persists all rows in a single DynamoDB table (default {@value #DEFAULT_TABLE}) keyed
 * by a partition key {@code pk} (the per-tenant prefix) and a sort key {@code sk} that
 * discriminates snapshot, shard, and pointer rows. All rows for a tenant share the same {@code pk},
 * derived from {@link #getSnapshotPathPrefix()} (default {@code "global"}).
 *
 * <p>The default {@link #getShardSize()} is {@value #DEFAULT_SHARD_SIZE} bytes, kept safely under
 * the DynamoDB 400&nbsp;KB item-size limit; because the store forces a checkpoint whenever a diff
 * would exceed the shard size, diff rows stay bounded too.
 */
public final class DynamoDbSessionStoreOptions {

  /** Default table name. */
  public static final String DEFAULT_TABLE = "genkit-sessions";

  /** Default number of turns between full checkpoints. */
  public static final int DEFAULT_CHECKPOINT_INTERVAL = 25;

  /** Default shard size in bytes for checkpoint state (350 KiB, under the 400 KB item cap). */
  public static final int DEFAULT_SHARD_SIZE = 350 * 1024;

  /** Default subscription poll interval in milliseconds. */
  public static final long DEFAULT_POLL_INTERVAL_MS = 2000L;

  private final String tableName;
  private final int checkpointInterval;
  private final int shardSize;
  private final Function<SessionStoreOptions, String> snapshotPathPrefix;
  private final boolean createTableIfNotExists;
  private final long pollIntervalMs;

  private DynamoDbSessionStoreOptions(Builder builder) {
    this.tableName = builder.tableName;
    this.checkpointInterval = builder.checkpointInterval;
    this.shardSize = builder.shardSize;
    this.snapshotPathPrefix = builder.snapshotPathPrefix;
    this.createTableIfNotExists = builder.createTableIfNotExists;
    this.pollIntervalMs = builder.pollIntervalMs;
  }

  /**
   * Returns the DynamoDB table name (default {@value #DEFAULT_TABLE}).
   *
   * @return the table name
   */
  public String getTableName() {
    return tableName;
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
   * Returns whether the store should create the table on first use if it does not exist (default
   * {@code false}).
   *
   * @return {@code true} if the table should be created when missing
   */
  public boolean isCreateTableIfNotExists() {
    return createTableIfNotExists;
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
   * @return a {@code DynamoDbSessionStoreOptions} with all defaults
   */
  public static DynamoDbSessionStoreOptions defaults() {
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

  /** Builder for {@link DynamoDbSessionStoreOptions}. */
  public static final class Builder {
    private String tableName = DEFAULT_TABLE;
    private int checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
    private int shardSize = DEFAULT_SHARD_SIZE;
    private Function<SessionStoreOptions, String> snapshotPathPrefix = o -> "global";
    private boolean createTableIfNotExists = false;
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    private Builder() {}

    /**
     * Sets the DynamoDB table name.
     *
     * @param tableName the table name
     * @return this builder
     */
    public Builder tableName(String tableName) {
      this.tableName = tableName;
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
     * Sets the shard size in bytes for checkpoint state (must stay under the 400 KB item cap).
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
     * Sets whether to create the table on first use if it does not exist.
     *
     * @param createTableIfNotExists whether to create the table when missing
     * @return this builder
     */
    public Builder createTableIfNotExists(boolean createTableIfNotExists) {
      this.createTableIfNotExists = createTableIfNotExists;
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
     * Builds a new {@code DynamoDbSessionStoreOptions}.
     *
     * @return a new options instance
     */
    public DynamoDbSessionStoreOptions build() {
      if (tableName == null || tableName.isBlank()) {
        throw new IllegalArgumentException("tableName must be non-empty");
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
      return new DynamoDbSessionStoreOptions(this);
    }
  }
}
