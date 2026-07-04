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

/** AgentAbortResponse is the response body for an agent abort operation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentAbortResponse {

  @JsonProperty("snapshotId")
  private String snapshotId;

  @JsonProperty("status")
  private SnapshotStatus status;

  /** Default constructor. */
  public AgentAbortResponse() {}

  private AgentAbortResponse(Builder builder) {
    this.snapshotId = builder.snapshotId;
    this.status = builder.status;
  }

  /**
   * Creates a builder for AgentAbortResponse.
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
   * Returns the resulting snapshot status.
   *
   * @return the status
   */
  public SnapshotStatus getStatus() {
    return status;
  }

  /**
   * Sets the resulting snapshot status.
   *
   * @param status the status
   */
  public void setStatus(SnapshotStatus status) {
    this.status = status;
  }

  /** Builder for AgentAbortResponse. */
  public static class Builder {
    private String snapshotId;
    private SnapshotStatus status;

    public Builder snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public Builder status(SnapshotStatus status) {
      this.status = status;
      return this;
    }

    public AgentAbortResponse build() {
      return new AgentAbortResponse(this);
    }
  }
}
