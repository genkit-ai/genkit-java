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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tiny POJO representing the contents of a per-session pointer file.
 *
 * <p>The pointer file lives at {@code <dir>/<prefix>/.pointers/<sessionId>.json} and contains a
 * reference to the current latest-leaf snapshot for that session, allowing fast lookup without
 * scanning all snapshot files.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PointerDoc {

  /** The snapshot ID of the current latest-leaf snapshot for the session. */
  @JsonProperty("currentSnapshotId")
  private String currentSnapshotId;

  /** The {@code createdAt} timestamp of the current latest-leaf snapshot (RFC-3339). */
  @JsonProperty("currentCreatedAt")
  private String currentCreatedAt;

  /** The timestamp when this pointer was last updated (RFC-3339). */
  @JsonProperty("updatedAt")
  private String updatedAt;

  /** Default constructor (required for Jackson deserialization). */
  public PointerDoc() {}

  /**
   * Creates a new {@code PointerDoc}.
   *
   * @param currentSnapshotId the snapshot ID
   * @param currentCreatedAt the snapshot's createdAt timestamp
   * @param updatedAt when this pointer was written
   */
  public PointerDoc(String currentSnapshotId, String currentCreatedAt, String updatedAt) {
    this.currentSnapshotId = currentSnapshotId;
    this.currentCreatedAt = currentCreatedAt;
    this.updatedAt = updatedAt;
  }

  /**
   * Returns the current snapshot ID.
   *
   * @return the snapshot ID
   */
  public String getCurrentSnapshotId() {
    return currentSnapshotId;
  }

  /**
   * Sets the current snapshot ID.
   *
   * @param currentSnapshotId the snapshot ID
   */
  public void setCurrentSnapshotId(String currentSnapshotId) {
    this.currentSnapshotId = currentSnapshotId;
  }

  /**
   * Returns the current snapshot's createdAt timestamp.
   *
   * @return the createdAt timestamp (RFC-3339)
   */
  public String getCurrentCreatedAt() {
    return currentCreatedAt;
  }

  /**
   * Sets the current snapshot's createdAt timestamp.
   *
   * @param currentCreatedAt the createdAt timestamp (RFC-3339)
   */
  public void setCurrentCreatedAt(String currentCreatedAt) {
    this.currentCreatedAt = currentCreatedAt;
  }

  /**
   * Returns the timestamp when this pointer was last updated.
   *
   * @return the updatedAt timestamp (RFC-3339)
   */
  public String getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Sets the timestamp when this pointer was last updated.
   *
   * @param updatedAt the updatedAt timestamp (RFC-3339)
   */
  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
