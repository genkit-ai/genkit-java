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
 * AgentRef is a lightweight reference to an agent, carrying only its name and description.
 *
 * <p>Returned by {@link AgentApi#ref()} so callers can identify the agent without holding the full
 * implementation object.
 */
public final class AgentRef {

  private final String name;
  private final String description;

  /**
   * Constructs an AgentRef.
   *
   * @param name the agent's registered name
   * @param description the agent's human-readable description; may be {@code null}
   */
  public AgentRef(String name, String description) {
    this.name = name;
    this.description = description;
  }

  /**
   * Returns the agent name.
   *
   * @return the agent name
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the agent description.
   *
   * @return the description, or {@code null} if not set
   */
  public String getDescription() {
    return description;
  }
}
