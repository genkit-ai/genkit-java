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
 * The mutator for an atomic read-modify-write on a session snapshot.
 *
 * <p>Receives the existing snapshot (or {@code null} if none exists) and returns the snapshot to
 * persist, or {@code null} to decline / no-op.
 *
 * <p><strong>Contract:</strong> implementations MUST be pure functions — the store may retry the
 * mutator on transient conflicts, so side effects are not safe.
 *
 * @param <S> the type of custom session state
 */
@FunctionalInterface
public interface SnapshotMutator<S> {

  /**
   * Applies the mutation.
   *
   * @param existing the existing snapshot, or {@code null} if no snapshot exists yet
   * @return the snapshot to persist, or {@code null} to decline (no write performed)
   */
  SessionSnapshot<S> apply(SessionSnapshot<S> existing);
}
