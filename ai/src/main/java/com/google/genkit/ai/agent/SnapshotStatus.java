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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** SnapshotStatus represents the status of an agent snapshot. */
public enum SnapshotStatus {
  /** The snapshot is pending. */
  PENDING("pending"),

  /** The snapshot is completed. */
  COMPLETED("completed"),

  /** The snapshot was aborted. */
  ABORTED("aborted"),

  /** The snapshot failed. */
  FAILED("failed"),

  /** The snapshot expired. */
  EXPIRED("expired");

  private final String value;

  SnapshotStatus(String value) {
    this.value = value;
  }

  /**
   * Returns the string value of the snapshot status.
   *
   * @return the snapshot status string value
   */
  @JsonValue
  public String getValue() {
    return value;
  }

  /**
   * Creates a SnapshotStatus from a string value.
   *
   * @param value the string value
   * @return the corresponding SnapshotStatus
   * @throws IllegalArgumentException if the value doesn't match any SnapshotStatus
   */
  @JsonCreator
  public static SnapshotStatus fromValue(String value) {
    for (SnapshotStatus status : values()) {
      if (status.value.equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown snapshot status: " + value);
  }

  /**
   * Creates a SnapshotStatus from a string value, treating null or empty strings as COMPLETED.
   *
   * @param value the string value
   * @return the corresponding SnapshotStatus, or COMPLETED if value is null or empty
   * @throws IllegalArgumentException if the non-empty value doesn't match any SnapshotStatus
   */
  public static SnapshotStatus fromValueOrCompleted(String value) {
    if (value == null || value.isEmpty()) {
      return COMPLETED;
    }
    return fromValue(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
