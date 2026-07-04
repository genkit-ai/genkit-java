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

/**
 * Options for retrieving a session snapshot.
 *
 * <p>Exactly one of {@code snapshotId} or {@code sessionId} must be set by callers. When {@code
 * snapshotId} is set the store returns that specific snapshot. When {@code sessionId} is set the
 * store resolves and returns the latest leaf snapshot for that session.
 */
public final class GetSnapshotOptions {

  private final String snapshotId;
  private final String sessionId;

  private GetSnapshotOptions(Builder builder) {
    this.snapshotId = builder.snapshotId;
    this.sessionId = builder.sessionId;
  }

  /**
   * Returns the specific snapshot ID to retrieve, or {@code null} if not set.
   *
   * @return the snapshot ID
   */
  public String getSnapshotId() {
    return snapshotId;
  }

  /**
   * Returns the session ID whose latest leaf snapshot should be retrieved, or {@code null} if not
   * set.
   *
   * @return the session ID
   */
  public String getSessionId() {
    return sessionId;
  }

  /**
   * Creates a new builder for {@code GetSnapshotOptions}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link GetSnapshotOptions}. */
  public static final class Builder {
    private String snapshotId;
    private String sessionId;

    private Builder() {}

    /**
     * Sets the specific snapshot ID to retrieve.
     *
     * @param snapshotId the snapshot ID
     * @return this builder
     */
    public Builder snapshotId(String snapshotId) {
      this.snapshotId = snapshotId;
      return this;
    }

    /**
     * Sets the session ID whose latest leaf snapshot should be retrieved.
     *
     * @param sessionId the session ID
     * @return this builder
     */
    public Builder sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    /**
     * Builds a new {@code GetSnapshotOptions}.
     *
     * @return a new {@code GetSnapshotOptions}
     */
    public GetSnapshotOptions build() {
      return new GetSnapshotOptions(this);
    }
  }
}
