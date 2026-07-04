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

package com.google.genkit.ai.agent.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry mapping a still-{@code PENDING} detached turn's snapshot id to the live {@link
 * AtomicBoolean} abort signal handed to that turn's {@code AgentFnContext}.
 *
 * <p>This is the only case where an external caller can know a turn's snapshot id WHILE the turn is
 * still running: {@code DetachController.detach(...)} writes the pending snapshot and returns its
 * id immediately, before the background work finishes. Foreground turns have no resolvable id until
 * after they return, so there is no reachable window in which to abort them externally — the
 * registry intentionally only covers detached turns.
 *
 * <p>{@link DetachController#detach} registers the signal for a snapshot id when the background
 * turn starts, and removes it in a {@code finally} block when the turn finishes (success, failure,
 * or abort) so entries never leak. {@code Agent.abort(String)} looks up the signal and flips it (in
 * addition to the existing snapshot-store status mutation, which remains the source of truth for
 * anything that reads the snapshot after the fact, e.g. a poller that only checks status).
 */
public final class PendingAbortRegistry {

  private static final ConcurrentHashMap<String, AtomicBoolean> SIGNALS = new ConcurrentHashMap<>();

  private PendingAbortRegistry() {}

  /**
   * Registers {@code signal} as the abort flag for the pending detached turn identified by {@code
   * snapshotId}.
   *
   * @param snapshotId the pending snapshot id
   * @param signal the abort signal handed to that turn's {@code AgentFnContext}
   */
  public static void register(String snapshotId, AtomicBoolean signal) {
    if (snapshotId == null || signal == null) {
      return;
    }
    SIGNALS.put(snapshotId, signal);
  }

  /**
   * Removes the registration for {@code snapshotId}, if present. Safe to call more than once.
   *
   * @param snapshotId the pending snapshot id
   */
  public static void unregister(String snapshotId) {
    if (snapshotId == null) {
      return;
    }
    SIGNALS.remove(snapshotId);
  }

  /**
   * Flips the abort signal registered for {@code snapshotId} to {@code true}, if one is currently
   * registered (i.e. the turn is a still-running detached turn).
   *
   * @param snapshotId the snapshot id to signal
   * @return {@code true} if a signal was found and flipped; {@code false} if no turn is currently
   *     registered under that id (already finished, foreground, or unknown)
   */
  public static boolean signal(String snapshotId) {
    if (snapshotId == null) {
      return false;
    }
    AtomicBoolean flag = SIGNALS.get(snapshotId);
    if (flag == null) {
      return false;
    }
    flag.set(true);
    return true;
  }

  /**
   * Returns the number of currently registered (still-pending, running) detached turns. Test-only
   * visibility hook.
   *
   * @return the number of registered signals
   */
  static int size() {
    return SIGNALS.size();
  }
}
