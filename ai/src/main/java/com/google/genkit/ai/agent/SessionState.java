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
import com.google.genkit.ai.Usage;
import java.util.ArrayList;
import java.util.List;

/**
 * SessionState represents the state of an agent session.
 *
 * @param <S> the type of custom state
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionState<S> {

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("messages")
  private List<Message> messages;

  @JsonProperty("custom")
  private S custom;

  @JsonProperty("artifacts")
  private List<Artifact> artifacts;

  @JsonProperty("usage")
  private Usage usage;

  /** Default constructor. */
  public SessionState() {}

  private SessionState(Builder<S> builder) {
    this.sessionId = builder.sessionId;
    this.messages = builder.messages;
    this.custom = builder.custom;
    this.artifacts = builder.artifacts;
    this.usage = builder.usage;
  }

  /**
   * Creates a builder for SessionState.
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
   * Returns the session messages.
   *
   * @return the messages
   */
  public List<Message> getMessages() {
    return messages;
  }

  /**
   * Sets the session messages.
   *
   * @param messages the messages
   */
  public void setMessages(List<Message> messages) {
    this.messages = messages != null ? new ArrayList<>(messages) : null;
  }

  /**
   * Returns the custom state.
   *
   * @return the custom state
   */
  public S getCustom() {
    return custom;
  }

  /**
   * Sets the custom state.
   *
   * @param custom the custom state
   */
  public void setCustom(S custom) {
    this.custom = custom;
  }

  /**
   * Returns the session artifacts.
   *
   * @return the artifacts
   */
  public List<Artifact> getArtifacts() {
    return artifacts;
  }

  /**
   * Sets the session artifacts.
   *
   * @param artifacts the artifacts
   */
  public void setArtifacts(List<Artifact> artifacts) {
    this.artifacts = artifacts != null ? new ArrayList<>(artifacts) : null;
  }

  /**
   * Builder for SessionState.
   *
   * @param <S> the type of custom state
   */
  /**
   * Returns the usage of the session's own model calls across all its turns and invocations, summed
   * field by field. It excludes the usage of sub-agents the session ran.
   *
   * @return the usage, or null when no model call reported any
   */
  public Usage getUsage() {
    return usage;
  }

  public void setUsage(Usage usage) {
    this.usage = usage;
  }

  public static class Builder<S> {
    private String sessionId;
    private List<Message> messages;
    private S custom;
    private List<Artifact> artifacts;
    private Usage usage;

    public Builder<S> sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder<S> messages(List<Message> messages) {
      this.messages = messages;
      return this;
    }

    public Builder<S> custom(S custom) {
      this.custom = custom;
      return this;
    }

    public Builder<S> artifacts(List<Artifact> artifacts) {
      this.artifacts = artifacts;
      return this;
    }

    public Builder<S> usage(Usage usage) {
      this.usage = usage;
      return this;
    }

    public SessionState<S> build() {
      return new SessionState<>(this);
    }
  }
}
