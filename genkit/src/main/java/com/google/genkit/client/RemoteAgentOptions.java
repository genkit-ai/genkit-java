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

package com.google.genkit.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Options for configuring a {@link RemoteAgent} HTTP client.
 *
 * <p>Build with {@link #builder()}.
 *
 * <ul>
 *   <li>{@link #url()} — the agent turn endpoint (e.g. {@code http://host:8080/myAgent}).
 *   <li>{@link #getSnapshotUrl()} — companion snapshot endpoint; defaults to {@code url +
 *       "/getSnapshot"}.
 *   <li>{@link #abortUrl()} — companion abort endpoint; defaults to {@code url + "/abort"}.
 *   <li>{@link #headers()} — optional extra request headers.
 *   <li>{@link #serverManaged()} — whether state is server-managed; default {@code true}.
 * </ul>
 */
public final class RemoteAgentOptions {

  private final String url;
  private final String getSnapshotUrl;
  private final String abortUrl;
  private final Map<String, String> headers;
  private final boolean serverManaged;

  private RemoteAgentOptions(Builder builder) {
    this.url = builder.url;
    this.getSnapshotUrl =
        builder.getSnapshotUrl != null ? builder.getSnapshotUrl : builder.url + "/getSnapshot";
    this.abortUrl = builder.abortUrl != null ? builder.abortUrl : builder.url + "/abort";
    this.headers =
        builder.headers != null
            ? Collections.unmodifiableMap(new HashMap<>(builder.headers))
            : Collections.emptyMap();
    this.serverManaged = builder.serverManaged;
  }

  /**
   * Creates a builder for {@link RemoteAgentOptions}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the agent turn endpoint URL.
   *
   * @return the URL
   */
  public String url() {
    return url;
  }

  /**
   * Returns the companion getSnapshot URL (defaults to {@code url + "/getSnapshot"}).
   *
   * @return the getSnapshot URL
   */
  public String getSnapshotUrl() {
    return getSnapshotUrl;
  }

  /**
   * Returns the companion abort URL (defaults to {@code url + "/abort"}).
   *
   * @return the abort URL
   */
  public String abortUrl() {
    return abortUrl;
  }

  /**
   * Returns extra HTTP request headers sent on every request.
   *
   * @return an unmodifiable map of headers (never null)
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns whether the agent is server-managed (default {@code true}).
   *
   * @return {@code true} if server-managed
   */
  public boolean serverManaged() {
    return serverManaged;
  }

  /** Builder for {@link RemoteAgentOptions}. */
  public static final class Builder {

    private String url;
    private String getSnapshotUrl;
    private String abortUrl;
    private Map<String, String> headers;
    private boolean serverManaged = true;

    private Builder() {}

    /**
     * Sets the agent turn endpoint URL (required).
     *
     * @param url the URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Overrides the getSnapshot URL. Defaults to {@code url + "/getSnapshot"}.
     *
     * @param getSnapshotUrl the URL
     * @return this builder
     */
    public Builder getSnapshotUrl(String getSnapshotUrl) {
      this.getSnapshotUrl = getSnapshotUrl;
      return this;
    }

    /**
     * Overrides the abort URL. Defaults to {@code url + "/abort"}.
     *
     * @param abortUrl the URL
     * @return this builder
     */
    public Builder abortUrl(String abortUrl) {
      this.abortUrl = abortUrl;
      return this;
    }

    /**
     * Sets extra HTTP request headers.
     *
     * @param headers headers to include on every request
     * @return this builder
     */
    public Builder headers(Map<String, String> headers) {
      this.headers = headers;
      return this;
    }

    /**
     * Sets whether the agent is server-managed. Default {@code true}.
     *
     * @param serverManaged true for server-managed
     * @return this builder
     */
    public Builder serverManaged(boolean serverManaged) {
      this.serverManaged = serverManaged;
      return this;
    }

    /**
     * Builds the {@link RemoteAgentOptions}.
     *
     * @return a new options instance
     * @throws IllegalStateException if {@code url} is null
     */
    public RemoteAgentOptions build() {
      if (url == null || url.isBlank()) {
        throw new IllegalStateException("url is required");
      }
      return new RemoteAgentOptions(this);
    }
  }
}
