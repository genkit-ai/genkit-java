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
import java.util.ArrayList;
import java.util.List;

/** AgentResult represents the final result of an agent execution. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentResult {

  @JsonProperty("message")
  private Message message;

  @JsonProperty("artifacts")
  private List<Artifact> artifacts;

  @JsonProperty("finishReason")
  private AgentFinishReason finishReason;

  /** Default constructor. */
  public AgentResult() {}

  private AgentResult(Builder builder) {
    this.message = builder.message;
    this.artifacts = builder.artifacts;
    this.finishReason = builder.finishReason;
  }

  /**
   * Creates a builder for AgentResult.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the result message.
   *
   * @return the message
   */
  public Message getMessage() {
    return message;
  }

  /**
   * Sets the result message.
   *
   * @param message the message
   */
  public void setMessage(Message message) {
    this.message = message;
  }

  /**
   * Returns the result artifacts.
   *
   * @return the artifacts
   */
  public List<Artifact> getArtifacts() {
    return artifacts;
  }

  /**
   * Sets the result artifacts.
   *
   * @param artifacts the artifacts
   */
  public void setArtifacts(List<Artifact> artifacts) {
    this.artifacts = artifacts != null ? new ArrayList<>(artifacts) : null;
  }

  /**
   * Returns the finish reason.
   *
   * @return the finish reason
   */
  public AgentFinishReason getFinishReason() {
    return finishReason;
  }

  /**
   * Sets the finish reason.
   *
   * @param finishReason the finish reason
   */
  public void setFinishReason(AgentFinishReason finishReason) {
    this.finishReason = finishReason;
  }

  /** Builder for AgentResult. */
  public static class Builder {
    private Message message;
    private List<Artifact> artifacts;
    private AgentFinishReason finishReason;

    public Builder message(Message message) {
      this.message = message;
      return this;
    }

    public Builder artifacts(List<Artifact> artifacts) {
      this.artifacts = artifacts;
      return this;
    }

    public Builder finishReason(AgentFinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public AgentResult build() {
      return new AgentResult(this);
    }
  }
}
