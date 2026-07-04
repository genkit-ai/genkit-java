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

package com.google.genkit.plugins.middleware;

/**
 * Options controlling the artifact tools produced by {@link Artifacts}.
 *
 * <p>The only knob is {@link #isReadonly()}: when {@code true}, only the {@code read_artifact} tool
 * is produced; when {@code false} (the default) a {@code write_artifact} tool is produced as well.
 */
public final class ArtifactsOptions {

  private final boolean readonly;

  private ArtifactsOptions(Builder builder) {
    this.readonly = builder.readonly;
  }

  /**
   * Returns a default options instance ({@code readonly = false}).
   *
   * @return default options
   */
  public static ArtifactsOptions defaults() {
    return builder().build();
  }

  /**
   * Creates a builder for {@link ArtifactsOptions}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns whether artifact writing is disabled.
   *
   * @return {@code true} if only the read tool should be produced
   */
  public boolean isReadonly() {
    return readonly;
  }

  /** Builder for {@link ArtifactsOptions}. */
  public static final class Builder {
    private boolean readonly;

    private Builder() {}

    /**
     * Sets whether artifact writing is disabled. When {@code true}, {@link Artifacts#tools} omits
     * the {@code write_artifact} tool.
     *
     * @param readonly {@code true} to produce only the read tool
     * @return this builder
     */
    public Builder readonly(boolean readonly) {
      this.readonly = readonly;
      return this;
    }

    /**
     * Builds the {@link ArtifactsOptions}.
     *
     * @return a new {@link ArtifactsOptions}
     */
    public ArtifactsOptions build() {
      return new ArtifactsOptions(this);
    }
  }
}
