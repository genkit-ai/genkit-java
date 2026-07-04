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

/**
 * AgentOutput represents the output of an agent turn.
 *
 * @param <S> the type of custom state
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentOutput<S> {

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("snapshotId")
  private String snapshotId;

  @JsonProperty("state")
  private SessionState<S> state;

  @JsonProperty("message")
  private Message message;

  @JsonProperty("artifacts")
  private List<Artifact> artifacts;

  @JsonProperty("finishReason")
  private AgentFinishReason finishReason;

  @JsonProperty("error")
  private RuntimeError error;

  /** Default constructor. */
  public AgentOutput() {}

  private AgentOutput(Builder<S> builder) {
    this.sessionId = builder.sessionId;
    this.snapshotId = builder.snapshotId;
    this.state = builder.state;
    this.message = builder.message;
    this.artifacts = builder.artifacts;
    this.finishReason = builder.finishReason;
    this.error = builder.error;
  }

  /**
   * Creates a builder for AgentOutput.
   *
   * @param <S> the type of custom state
   * @return a new builder
   */
  public static <S> Builder<S> builder() {
    return new Builder<>();
  }

  /**
   * Returns the session ID.
   *
   * @return the session ID
   */
  public String getSessionId() {
    return sessionId;
  }

  /**
   * Sets the session ID.
   *
   * @param sessionId the session ID
   */
  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  /**
   * Returns the snapshot ID.
   *
   * @return the snapshot ID
   */
  public String getSnapshotId() {
    return snapshotId;
  }

  /**
   * Sets the snapshot ID.
   *
   * @param snapshotId the snapshot ID
   */
  public void setSnapshotId(String snapshotId) {
    this.snapshotId = snapshotId;
  }

  /**
   * Returns the session state.
   *
   * @return the state
   */
  public SessionState<S> getState() {
    return state;
  }

  /**
   * Sets the session state.
   *
   * @param state the state
   */
  public void setState(SessionState<S> state) {
    this.state = state;
  }

  /**
   * Returns the output message.
   *
   * @return the message
   */
  public Message getMessage() {
    return message;
  }

  /**
   * Sets the output message.
   *
   * @param message the message
   */
  public void setMessage(Message message) {
    this.message = message;
  }

  /**
   * Returns the output artifacts.
   *
   * @return the artifacts
   */
  public List<Artifact> getArtifacts() {
    return artifacts;
  }

  /**
   * Sets the output artifacts.
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

  /**
   * Returns the runtime error, if any.
   *
   * @return the error, or null if no error
   */
  public RuntimeError getError() {
    return error;
  }

  /**
   * Sets the runtime error.
   *
   * @param error the error
   */
  public void setError(RuntimeError error) {
    this.error = error;
  }

  /**
   * Builder for AgentOutput.
   *
   * @param <S> the type of custom state
   */
  public static class Builder<S> {
    private String sessionId;
    private String snapshotId;
    private SessionState<S> state;
    private Message message;
    private List<Artifact> artifacts;
    private AgentFinishReason finishReason;
    private RuntimeError error;

    public Builder<S> sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder<S> snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public Builder<S> state(SessionState<S> state) {
      this.state = state;
      return this;
    }

    public Builder<S> message(Message message) {
      this.message = message;
      return this;
    }

    public Builder<S> artifacts(List<Artifact> artifacts) {
      this.artifacts = artifacts;
      return this;
    }

    public Builder<S> finishReason(AgentFinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public Builder<S> error(RuntimeError error) {
      this.error = error;
      return this;
    }

    public AgentOutput<S> build() {
      return new AgentOutput<>(this);
    }
  }
}
