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

package com.google.genkit.plugins.firebase.session;

import com.google.genkit.ai.agent.SessionStoreOptions;
import java.util.function.Function;

/**
 * Configuration for {@link FirestoreSessionStore}.
 *
 * <p>Mirrors the upstream Go/JS Firestore session store options. The store derives three Firestore
 * collection roots from {@link #getCollection()}:
 *
 * <ul>
 *   <li>{@code <collection>} — one document per snapshot (checkpoint or diff metadata).
 *   <li>{@code <collection>-shards} — sharded checkpoint state JSON.
 *   <li>{@code <collection>-pointers} — per-session pointer to the current leaf snapshot.
 * </ul>
 *
 * <p>All three are namespaced under a per-tenant prefix derived from {@link
 * #getSnapshotPathPrefix()} (default {@code "global"}).
 */
public final class FirestoreSessionStoreOptions {

  /** Default top-level collection name. */
  public static final String DEFAULT_COLLECTION = "genkit-sessions";

  /** Default number of turns between full checkpoints. */
  public static final int DEFAULT_CHECKPOINT_INTERVAL = 25;

  /** Default shard size in bytes for checkpoint state (512 KiB). */
  public static final int DEFAULT_SHARD_SIZE = 512 * 1024;

  private final String collection;
  private final int checkpointInterval;
  private final int shardSize;
  private final Function<SessionStoreOptions, String> snapshotPathPrefix;

  private FirestoreSessionStoreOptions(Builder builder) {
    this.collection = builder.collection;
    this.checkpointInterval = builder.checkpointInterval;
    this.shardSize = builder.shardSize;
    this.snapshotPathPrefix = builder.snapshotPathPrefix;
  }

  /**
   * Returns the top-level collection name (default {@value #DEFAULT_COLLECTION}).
   *
   * @return the collection name
   */
  public String getCollection() {
    return collection;
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
   * Returns the function that derives the per-tenant path prefix from the per-request store options
   * (default {@code o -> "global"}).
   *
   * @return the prefix function
   */
  public Function<SessionStoreOptions, String> getSnapshotPathPrefix() {
    return snapshotPathPrefix;
  }

  /**
   * Returns default options.
   *
   * @return a {@code FirestoreSessionStoreOptions} with all defaults
   */
  public static FirestoreSessionStoreOptions defaults() {
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

  /** Builder for {@link FirestoreSessionStoreOptions}. */
  public static final class Builder {
    private String collection = DEFAULT_COLLECTION;
    private int checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
    private int shardSize = DEFAULT_SHARD_SIZE;
    private Function<SessionStoreOptions, String> snapshotPathPrefix = o -> "global";

    private Builder() {}

    /**
     * Sets the top-level collection name.
     *
     * @param collection the collection name
     * @return this builder
     */
    public Builder collection(String collection) {
      this.collection = collection;
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
     * Sets the shard size in bytes for checkpoint state.
     *
     * @param shardSize the shard size in bytes (must be {@code >= 1})
     * @return this builder
     */
    public Builder shardSize(int shardSize) {
      this.shardSize = shardSize;
      return this;
    }

    /**
     * Sets the function that derives the per-tenant path prefix from the per-request store options.
     *
     * @param snapshotPathPrefix the prefix function
     * @return this builder
     */
    public Builder snapshotPathPrefix(Function<SessionStoreOptions, String> snapshotPathPrefix) {
      this.snapshotPathPrefix = snapshotPathPrefix;
      return this;
    }

    /**
     * Builds a new {@code FirestoreSessionStoreOptions}.
     *
     * @return a new options instance
     */
    public FirestoreSessionStoreOptions build() {
      if (collection == null || collection.isBlank()) {
        throw new IllegalArgumentException("collection must be non-empty");
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
      return new FirestoreSessionStoreOptions(this);
    }
  }
}
