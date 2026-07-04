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

/** GetSnapshotRequest is the request body for retrieving a session snapshot. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetSnapshotRequest {

  @JsonProperty("snapshotId")
  private String snapshotId;

  @JsonProperty("sessionId")
  private String sessionId;

  /** Default constructor. */
  public GetSnapshotRequest() {}

  private GetSnapshotRequest(Builder builder) {
    this.snapshotId = builder.snapshotId;
    this.sessionId = builder.sessionId;
  }

  /**
   * Creates a builder for GetSnapshotRequest.
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

  /** Builder for GetSnapshotRequest. */
  public static class Builder {
    private String snapshotId;
    private String sessionId;

    public Builder snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public Builder sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public GetSnapshotRequest build() {
      return new GetSnapshotRequest(this);
    }
  }
}
