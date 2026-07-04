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

/** AgentAbortRequest is the request body for aborting an agent execution. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentAbortRequest {

  @JsonProperty("snapshotId")
  private String snapshotId;

  /** Default constructor. */
  public AgentAbortRequest() {}

  private AgentAbortRequest(Builder builder) {
    this.snapshotId = builder.snapshotId;
  }

  /**
   * Creates a builder for AgentAbortRequest.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the snapshot ID to abort.
   *
   * @return the snapshot ID
   */
  public String getSnapshotId() {
    return snapshotId;
  }

  /**
   * Sets the snapshot ID to abort.
   *
   * @param snapshotId the snapshot ID
   */
  public void setSnapshotId(String snapshotId) {
    this.snapshotId = snapshotId;
  }

  /** Builder for AgentAbortRequest. */
  public static class Builder {
    private String snapshotId;

    public Builder snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public AgentAbortRequest build() {
      return new AgentAbortRequest(this);
    }
  }
}
