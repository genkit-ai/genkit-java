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
 * Options passed to session store operations.
 *
 * <p>Currently a thin options holder that provides room for future context (e.g. deadline,
 * credentials). Use {@link #empty()} for a default instance.
 */
public final class SessionStoreOptions {

  /** A shared default empty instance. */
  private static final SessionStoreOptions EMPTY = new SessionStoreOptions(new Builder());

  private SessionStoreOptions(Builder builder) {}

  /**
   * Returns a default empty {@code SessionStoreOptions}.
   *
   * @return the default instance
   */
  public static SessionStoreOptions empty() {
    return EMPTY;
  }

  /**
   * Creates a new builder for {@code SessionStoreOptions}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link SessionStoreOptions}. */
  public static final class Builder {

    private Builder() {}

    /**
     * Builds a new {@code SessionStoreOptions}.
     *
     * @return a new {@code SessionStoreOptions}
     */
    public SessionStoreOptions build() {
      return new SessionStoreOptions(this);
    }
  }
}
