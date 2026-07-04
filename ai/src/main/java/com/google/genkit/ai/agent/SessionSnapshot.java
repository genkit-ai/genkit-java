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
 * SessionSnapshot represents a point-in-time snapshot of an agent session.
 *
 * <p>Timestamps ({@code createdAt}, {@code updatedAt}, {@code heartbeatAt}) are stored as RFC-3339
 * strings for exact wire fidelity.
 *
 * @param <S> the type of custom state
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionSnapshot<S> {

  @JsonProperty("snapshotId")
  private String snapshotId;

  @JsonProperty("sessionId")
  private String sessionId;

  @JsonProperty("parentId")
  private String parentId;

  /** RFC-3339 timestamp string. */
  @JsonProperty("createdAt")
  private String createdAt;

  /** RFC-3339 timestamp string. */
  @JsonProperty("updatedAt")
  private String updatedAt;

  /** RFC-3339 timestamp string. */
  @JsonProperty("heartbeatAt")
  private String heartbeatAt;

  @JsonProperty("status")
  private SnapshotStatus status;

  @JsonProperty("finishReason")
  private AgentFinishReason finishReason;

  @JsonProperty("error")
  private RuntimeError error;

  @JsonProperty("state")
  private SessionState<S> state;

  /** Default constructor. */
  public SessionSnapshot() {}

  private SessionSnapshot(Builder<S> builder) {
    this.snapshotId = builder.snapshotId;
    this.sessionId = builder.sessionId;
    this.parentId = builder.parentId;
    this.createdAt = builder.createdAt;
    this.updatedAt = builder.updatedAt;
    this.heartbeatAt = builder.heartbeatAt;
    this.status = builder.status;
    this.finishReason = builder.finishReason;
    this.error = builder.error;
    this.state = builder.state;
  }

  /**
   * Creates a builder for SessionSnapshot.
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
   * Returns the parent snapshot ID.
   *
   * @return the parent ID
   */
  public String getParentId() {
    return parentId;
  }

  /**
   * Sets the parent snapshot ID.
   *
   * @param parentId the parent ID
   */
  public void setParentId(String parentId) {
    this.parentId = parentId;
  }

  /**
   * Returns the creation timestamp (RFC-3339).
   *
   * @return the created-at timestamp
   */
  public String getCreatedAt() {
    return createdAt;
  }

  /**
   * Sets the creation timestamp.
   *
   * @param createdAt the created-at timestamp (RFC-3339)
   */
  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt;
  }

  /**
   * Returns the last-update timestamp (RFC-3339).
   *
   * @return the updated-at timestamp
   */
  public String getUpdatedAt() {
    return updatedAt;
  }

  /**
   * Sets the last-update timestamp.
   *
   * @param updatedAt the updated-at timestamp (RFC-3339)
   */
  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }

  /**
   * Returns the last-heartbeat timestamp (RFC-3339).
   *
   * @return the heartbeat-at timestamp
   */
  public String getHeartbeatAt() {
    return heartbeatAt;
  }

  /**
   * Sets the last-heartbeat timestamp.
   *
   * @param heartbeatAt the heartbeat-at timestamp (RFC-3339)
   */
  public void setHeartbeatAt(String heartbeatAt) {
    this.heartbeatAt = heartbeatAt;
  }

  /**
   * Returns the snapshot status.
   *
   * @return the status
   */
  public SnapshotStatus getStatus() {
    return status;
  }

  /**
   * Sets the snapshot status.
   *
   * @param status the status
   */
  public void setStatus(SnapshotStatus status) {
    this.status = status;
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
   * Returns the session state at this snapshot.
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
   * Builder for SessionSnapshot.
   *
   * @param <S> the type of custom state
   */
  public static class Builder<S> {
    private String snapshotId;
    private String sessionId;
    private String parentId;
    private String createdAt;
    private String updatedAt;
    private String heartbeatAt;
    private SnapshotStatus status;
    private AgentFinishReason finishReason;
    private RuntimeError error;
    private SessionState<S> state;

    public Builder<S> snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    public Builder<S> sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder<S> parentId(String parentId) {
      this.parentId = parentId;
      return this;
    }

    public Builder<S> createdAt(String createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Builder<S> updatedAt(String updatedAt) {
      this.updatedAt = updatedAt;
      return this;
    }

    public Builder<S> heartbeatAt(String heartbeatAt) {
      this.heartbeatAt = heartbeatAt;
      return this;
    }

    public Builder<S> status(SnapshotStatus status) {
      this.status = status;
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

    public Builder<S> state(SessionState<S> state) {
      this.state = state;
      return this;
    }

    public SessionSnapshot<S> build() {
      return new SessionSnapshot<>(this);
    }
  }
}
