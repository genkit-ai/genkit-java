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
 * Per-turn context exposed to {@link TurnBody} implementations. Carries the snapshot IDs reserved
 * for this turn and the current turn index.
 */
public final class TurnContext {

  private final String snapshotId;
  private final String parentSnapshotId;
  private final int turnIndex;

  /**
   * Constructs a new TurnContext.
   *
   * @param snapshotId the snapshot ID reserved for this turn (empty string for client-managed)
   * @param parentSnapshotId the snapshot ID from the previous turn, or null if this is the first
   *     turn
   * @param turnIndex the zero-based index of this turn
   */
  public TurnContext(String snapshotId, String parentSnapshotId, int turnIndex) {
    this.snapshotId = snapshotId;
    this.parentSnapshotId = parentSnapshotId;
    this.turnIndex = turnIndex;
  }

  /**
   * Returns the snapshot ID reserved for this turn.
   *
   * @return the snapshot ID (empty string for client-managed mode)
   */
  public String snapshotId() {
    return snapshotId;
  }

  /**
   * Returns the parent snapshot ID (ID of the previous turn's snapshot).
   *
   * @return the parent snapshot ID, or null if this is the first turn
   */
  public String parentSnapshotId() {
    return parentSnapshotId;
  }

  /**
   * Returns the zero-based index of this turn.
   *
   * @return the turn index
   */
  public int turnIndex() {
    return turnIndex;
  }
}
