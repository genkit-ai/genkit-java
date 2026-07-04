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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.genkit.ai.Message;

/**
 * AgentInput represents the input to an agent turn.
 *
 * <p>The {@code detach} field is omitted when false (default).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentInput {

  @JsonProperty("message")
  private Message message;

  @JsonProperty("resume")
  private ToolResume resume;

  /** detach is omitted when false; use primitive boolean with NON_DEFAULT include. */
  @JsonProperty("detach")
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  private boolean detach;

  /** Default constructor. */
  public AgentInput() {}

  private AgentInput(Builder builder) {
    this.message = builder.message;
    this.resume = builder.resume;
    this.detach = builder.detach;
  }

  /**
   * Creates a builder for AgentInput.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the input message.
   *
   * @return the message
   */
  public Message getMessage() {
    return message;
  }

  /**
   * Sets the input message.
   *
   * @param message the message
   */
  public void setMessage(Message message) {
    this.message = message;
  }

  /**
   * Returns the tool resume data.
   *
   * @return the resume, or null if not resuming
   */
  public ToolResume getResume() {
    return resume;
  }

  /**
   * Sets the tool resume data.
   *
   * @param resume the resume
   */
  public void setResume(ToolResume resume) {
    this.resume = resume;
  }

  /**
   * Returns whether the agent should detach.
   *
   * @return true if detaching, false otherwise
   */
  public boolean isDetach() {
    return detach;
  }

  /**
   * Gets the detach flag (alias for isDetach).
   *
   * @return true if detaching, false otherwise
   */
  public boolean getDetach() {
    return detach;
  }

  /**
   * Sets the detach flag. Omitted from JSON when false.
   *
   * @param detach true to detach
   */
  public void setDetach(boolean detach) {
    this.detach = detach;
  }

  /** Builder for AgentInput. */
  public static class Builder {
    private Message message;
    private ToolResume resume;
    private boolean detach;

    public Builder message(Message message) {
      this.message = message;
      return this;
    }

    public Builder resume(ToolResume resume) {
      this.resume = resume;
      return this;
    }

    public Builder detach(boolean detach) {
      this.detach = detach;
      return this;
    }

    public AgentInput build() {
      return new AgentInput(this);
    }
  }
}
