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

/** TurnEnd signals the end of an agent turn in a streaming response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TurnEnd {

  @JsonProperty("snapshotId")
  private String snapshotId;

  @JsonProperty("finishReason")
  private AgentFinishReason finishReason;

  /** Default constructor. */
  public TurnEnd() {}

  private TurnEnd(Builder builder) {
    this.snapshotId = builder.snapshotId;
    this.finishReason = builder.finishReason;
  }

  /**
   * Creates a builder for TurnEnd.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
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

  /** Builder for TurnEnd. */
  public static class Builder {
    private String snapshotId;
    private AgentFinishReason finishReason;

    public Builder snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public Builder finishReason(AgentFinishReason finishReason) {
      this.finishReason = finishReason;
      return this;
    }

    public TurnEnd build() {
      return new TurnEnd(this);
    }
  }
}
