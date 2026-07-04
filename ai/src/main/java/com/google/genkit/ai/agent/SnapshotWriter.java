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

package com.google.genkit.ai.agent;

/**
 * Write side of a session store.
 *
 * <p>The single write primitive is {@link #saveSnapshot}, an atomic read-modify-write. Stores
 * enforce the following rules (see individual implementations for details):
 *
 * <ul>
 *   <li>If {@code snapshotId} is {@code null} the store mints a UUID.
 *   <li>The store preserves {@code sessionId} from the existing snapshot when available.
 *   <li>An empty {@code sessionId} is rejected with {@code INVALID_ARGUMENT}.
 *   <li>An empty {@code status} defaults to {@code completed}.
 *   <li>The {@link SnapshotMutator} may be retried on transient conflicts; it must be pure.
 * </ul>
 *
 * @param <S> the type of custom session state
 */
public interface SnapshotWriter<S> {

  /**
   * Atomically reads the snapshot identified by {@code snapshotId} (or creates a new one), applies
   * {@code mutator}, and persists the result.
   *
   * @param snapshotId the ID of the snapshot to write; if {@code null} the store mints a UUID
   * @param mutator the pure read-modify-write function; receives the existing snapshot (or {@code
   *     null}) and returns the snapshot to persist, or {@code null} to decline
   * @param options store options
   * @return the snapshot ID that was written, or {@code null} if the mutator declined
   */
  String saveSnapshot(String snapshotId, SnapshotMutator<S> mutator, SessionStoreOptions options);
}
