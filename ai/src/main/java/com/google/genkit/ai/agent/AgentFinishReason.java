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

/** AgentFinishReason indicates why an agent finished execution. */
public enum AgentFinishReason {
  /** The agent finished normally due to a stop condition. */
  STOP("stop"),

  /** The agent finished due to reaching token length limits. */
  LENGTH("length"),

  /** The agent was blocked from proceeding. */
  BLOCKED("blocked"),

  /** The agent execution was interrupted. */
  INTERRUPTED("interrupted"),

  /** The agent finished for some other reason. */
  OTHER("other"),

  /** The agent finish reason is unknown. */
  UNKNOWN("unknown"),

  /** The agent was aborted. */
  ABORTED("aborted"),

  /** The agent was detached. */
  DETACHED("detached"),

  /** The agent failed. */
  FAILED("failed");

  private final String value;

  AgentFinishReason(String value) {
    this.value = value;
  }

  /**
   * Returns the string value of the agent finish reason.
   *
   * @return the agent finish reason string value
   */
  @JsonValue
  public String getValue() {
    return value;
  }

  /**
   * Creates an AgentFinishReason from a string value.
   *
   * @param value the string value
   * @return the corresponding AgentFinishReason
   * @throws IllegalArgumentException if the value doesn't match any AgentFinishReason
   */
  @JsonCreator
  public static AgentFinishReason fromValue(String value) {
    for (AgentFinishReason reason : values()) {
      if (reason.value.equals(value)) {
        return reason;
      }
    }
    throw new IllegalArgumentException("Unknown agent finish reason: " + value);
  }

  @Override
  public String toString() {
    return value;
  }
}
