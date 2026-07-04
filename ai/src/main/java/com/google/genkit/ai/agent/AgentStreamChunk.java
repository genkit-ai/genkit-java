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
import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.ModelResponseChunk;

/**
 * AgentStreamChunk represents a streaming chunk from an agent execution.
 *
 * <p>The {@code customPatch} field is a JSON array of JSON-patch operations (RFC 6902).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentStreamChunk {

  @JsonProperty("modelChunk")
  private ModelResponseChunk modelChunk;

  /** JSON-patch array of ops; typed as JsonNode to stay aligned with core.jsonpatch.JsonPatch. */
  @JsonProperty("customPatch")
  private JsonNode customPatch;

  @JsonProperty("artifact")
  private Artifact artifact;

  @JsonProperty("turnEnd")
  private TurnEnd turnEnd;

  /** Default constructor. */
  public AgentStreamChunk() {}

  private AgentStreamChunk(Builder builder) {
    this.modelChunk = builder.modelChunk;
    this.customPatch = builder.customPatch;
    this.artifact = builder.artifact;
    this.turnEnd = builder.turnEnd;
  }

  /**
   * Creates a builder for AgentStreamChunk.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the model response chunk.
   *
   * @return the model chunk
   */
  public ModelResponseChunk getModelChunk() {
    return modelChunk;
  }

  /**
   * Sets the model response chunk.
   *
   * @param modelChunk the model chunk
   */
  public void setModelChunk(ModelResponseChunk modelChunk) {
    this.modelChunk = modelChunk;
  }

  /**
   * Returns the custom-state patch as a JSON array of patch operations.
   *
   * @return the custom patch node (array)
   */
  public JsonNode getCustomPatch() {
    return customPatch;
  }

  /**
   * Sets the custom-state patch.
   *
   * @param customPatch a JSON array of patch operations
   */
  public void setCustomPatch(JsonNode customPatch) {
    this.customPatch = customPatch;
  }

  /**
   * Returns the artifact in this chunk.
   *
   * @return the artifact, or null if not present
   */
  public Artifact getArtifact() {
    return artifact;
  }

  /**
   * Sets the artifact.
   *
   * @param artifact the artifact
   */
  public void setArtifact(Artifact artifact) {
    this.artifact = artifact;
  }

  /**
   * Returns the turn-end signal.
   *
   * @return the turn end, or null if the turn has not ended
   */
  public TurnEnd getTurnEnd() {
    return turnEnd;
  }

  /**
   * Sets the turn-end signal.
   *
   * @param turnEnd the turn end
   */
  public void setTurnEnd(TurnEnd turnEnd) {
    this.turnEnd = turnEnd;
  }

  /** Builder for AgentStreamChunk. */
  public static class Builder {
    private ModelResponseChunk modelChunk;
    private JsonNode customPatch;
    private Artifact artifact;
    private TurnEnd turnEnd;

    public Builder modelChunk(ModelResponseChunk modelChunk) {
      this.modelChunk = modelChunk;
      return this;
    }

    public Builder customPatch(JsonNode customPatch) {
      this.customPatch = customPatch;
      return this;
    }

    public Builder artifact(Artifact artifact) {
      this.artifact = artifact;
      return this;
    }

    public Builder turnEnd(TurnEnd turnEnd) {
      this.turnEnd = turnEnd;
      return this;
    }

    public AgentStreamChunk build() {
      return new AgentStreamChunk(this);
    }
  }
}
