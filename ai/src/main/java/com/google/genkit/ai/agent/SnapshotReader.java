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
 * Read side of a session store.
 *
 * @param <S> the type of custom session state
 */
public interface SnapshotReader<S> {

  /**
   * Retrieves a session snapshot.
   *
   * <p>When {@link GetSnapshotOptions#getSnapshotId()} is set the store returns that specific
   * snapshot. When {@link GetSnapshotOptions#getSessionId()} is set the store resolves and returns
   * the latest leaf snapshot for that session. Returns {@code null} if no matching snapshot exists.
   *
   * @param opts options specifying which snapshot to retrieve; exactly one of {@code
   *     snapshotId}/{@code sessionId} must be set
   * @return the matching snapshot, or {@code null} if none exists
   */
  SessionSnapshot<S> getSnapshot(GetSnapshotOptions opts);
}
