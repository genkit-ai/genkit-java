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

/**
 * AgentInit represents the initialization data for an agent session.
 *
 * @param <S> the type of custom state
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentInit<S> {

  @JsonProperty("snapshotId")
  private String snapshotId;

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("state")
  private SessionState<S> state;

  /** Default constructor. */
  public AgentInit() {}

  private AgentInit(Builder<S> builder) {
    this.snapshotId = builder.snapshotId;
    this.sessionId = builder.sessionId;
    this.state = builder.state;
  }

  /**
   * Creates a builder for AgentInit.
   *
   * @param <S> the type of custom state
   * @return a new builder
   */
  public static <S> Builder<S> builder() {
    return new Builder<>();
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
   * Returns the initial session state.
   *
   * @return the session state
   */
  public SessionState<S> getState() {
    return state;
  }

  /**
   * Sets the initial session state.
   *
   * @param state the session state
   */
  public void setState(SessionState<S> state) {
    this.state = state;
  }

  /**
   * Builder for AgentInit.
   *
   * @param <S> the type of custom state
   */
  public static class Builder<S> {
    private String snapshotId;
    private String sessionId;
    private SessionState<S> state;

    public Builder<S> snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public Builder<S> sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder<S> state(SessionState<S> state) {
      this.state = state;
      return this;
    }

    public AgentInit<S> build() {
      return new AgentInit<>(this);
    }
  }
}
