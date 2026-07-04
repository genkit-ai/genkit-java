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

import java.util.function.Consumer;

/**
 * Optional capability: subscribe to a snapshot's status changes.
 *
 * <p>Stores that support real-time notifications (e.g. Firestore) implement this interface.
 * Required for detach/abort workflows that need to observe when a running snapshot transitions to a
 * terminal state.
 */
public interface SnapshotSubscriber {

  /**
   * Subscribes to status changes for the snapshot identified by {@code snapshotId}.
   *
   * <p>Invokes {@code cb} immediately with the current snapshot and again on every subsequent
   * change. The returned {@link AutoCloseable} unsubscribes when closed.
   *
   * @param snapshotId the snapshot to observe
   * @param cb callback invoked with the updated snapshot on each change
   * @param options store options
   * @return a handle that cancels the subscription when {@link AutoCloseable#close()} is called
   */
  AutoCloseable onSnapshotStateChange(
      String snapshotId, Consumer<SessionSnapshot<?>> cb, SessionStoreOptions options);
}
