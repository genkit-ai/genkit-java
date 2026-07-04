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
import java.util.Map;

/**
 * AgentMetadata describes the capabilities and configuration of an agent endpoint.
 *
 * <p>{@code stateManagement} is the string {@code "server"} or {@code "client"}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentMetadata {

  @JsonProperty("stateManagement")
  private String stateManagement;

  /** abortable is a primitive boolean — always serialized (not omitted when false). */
  @JsonProperty("abortable")
  private boolean abortable;

  @JsonProperty("stateSchema")
  private Map<String, Object> stateSchema;

  /** Default constructor. */
  public AgentMetadata() {}

  private AgentMetadata(Builder builder) {
    this.stateManagement = builder.stateManagement;
    this.abortable = builder.abortable;
    this.stateSchema = builder.stateSchema;
  }

  /**
   * Creates a builder for AgentMetadata.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the state management mode.
   *
   * @return {@code "server"} or {@code "client"}
   */
  public String getStateManagement() {
    return stateManagement;
  }

  /**
   * Sets the state management mode.
   *
   * @param stateManagement {@code "server"} or {@code "client"}
   */
  public void setStateManagement(String stateManagement) {
    this.stateManagement = stateManagement;
  }

  /**
   * Returns whether the agent supports abort.
   *
   * @return true if abortable
   */
  public boolean isAbortable() {
    return abortable;
  }

  /**
   * Sets whether the agent supports abort.
   *
   * @param abortable true if abortable
   */
  public void setAbortable(boolean abortable) {
    this.abortable = abortable;
  }

  /**
   * Returns the JSON schema for the agent state, if provided.
   *
   * @return the state schema, or null if not set
   */
  public Map<String, Object> getStateSchema() {
    return stateSchema;
  }

  /**
   * Sets the JSON schema for the agent state.
   *
   * @param stateSchema the state schema
   */
  public void setStateSchema(Map<String, Object> stateSchema) {
    this.stateSchema = stateSchema;
  }

  /** Builder for AgentMetadata. */
  public static class Builder {
    private String stateManagement;
    private boolean abortable;
    private Map<String, Object> stateSchema;

    public Builder stateManagement(String stateManagement) {
      this.stateManagement = stateManagement;
      return this;
    }

    public Builder abortable(boolean abortable) {
      this.abortable = abortable;
      return this;
    }

    public Builder stateSchema(Map<String, Object> stateSchema) {
      this.stateSchema = stateSchema;
      return this;
    }

    public AgentMetadata build() {
      return new AgentMetadata(this);
    }
  }
}
