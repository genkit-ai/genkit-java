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

import com.google.genkit.ai.Part;
import com.google.genkit.ai.ToolRequest;

/**
 * AgentInterrupt represents a tool request that paused agent execution awaiting a caller response.
 *
 * <p>Extracted from the final message's tool-request parts when a turn finishes with {@link
 * AgentFinishReason#INTERRUPTED}. The caller resolves an interrupt by sending a {@link
 * ToolResume#getRespond()} via {@link AgentChat#resume(java.util.List)}.
 */
public final class AgentInterrupt {

  private final String name;
  private final Object input;
  private final Part part;

  /**
   * Constructs an AgentInterrupt.
   *
   * @param name the tool name
   * @param input the tool input
   * @param part the originating tool-request part (preserved for resume correlation)
   */
  public AgentInterrupt(String name, Object input, Part part) {
    this.name = name;
    this.input = input;
    this.part = part;
  }

  /**
   * Returns the interrupted tool's name.
   *
   * @return the tool name, or {@code null} if unknown
   */
  public String name() {
    return name;
  }

  /**
   * Returns the interrupted tool's input.
   *
   * @return the tool input, or {@code null}
   */
  public Object input() {
    return input;
  }

  /**
   * Returns the originating tool-request part.
   *
   * @return the part, or {@code null}
   */
  public Part part() {
    return part;
  }

  /**
   * Builds an interrupt from a tool-request part.
   *
   * @param part a part whose {@link Part#getToolRequest()} is non-null
   * @return a new interrupt
   */
  static AgentInterrupt fromPart(Part part) {
    ToolRequest tr = part.getToolRequest();
    return new AgentInterrupt(
        tr != null ? tr.getName() : null, tr != null ? tr.getInput() : null, part);
  }
}
