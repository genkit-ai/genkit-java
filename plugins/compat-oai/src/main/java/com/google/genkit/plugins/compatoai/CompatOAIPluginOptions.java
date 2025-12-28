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

package com.google.genkit.plugins.compatoai;

import java.util.Map;

/**
 * Options for configuring OpenAI-compatible API plugins.
 */
public class CompatOAIPluginOptions {
  private final String apiKey;
  private final String baseUrl;
  private final String organization;
  private final int timeout;
  private final Map<String, String> queryParams;

  private CompatOAIPluginOptions(Builder builder) {
    this.apiKey = builder.apiKey;
    this.baseUrl = builder.baseUrl;
    this.organization = builder.organization;
    this.timeout = builder.timeout;
    this.queryParams = builder.queryParams;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the API key.
   *
   * @return the API key
   */
  public String getApiKey() {
    return apiKey;
  }

  /**
   * Gets the base URL for API requests.
   *
   * @return the base URL
   */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Gets the organization ID.
   *
   * @return the organization ID
   */
  public String getOrganization() {
    return organization;
  }

  /**
   * Gets the request timeout in seconds.
   *
   * @return the timeout
   */
  public int getTimeout() {
    return timeout;
  }

  /**
   * Gets the query parameters to append to API requests.
   *
   * @return the query parameters map, or null if none
   */
  public Map<String, String> getQueryParams() {
    return queryParams;
  }

  /**
   * Builder for CompatOAIPluginOptions.
   */
  public static class Builder {
    private String apiKey;
    private String baseUrl;
    private String organization;
    private int timeout = 60;
    private Map<String, String> queryParams;

    /**
     * Sets the API key.
     *
     * @param apiKey
     *            the API key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the base URL for API requests.
     *
     * @param baseUrl
     *            the base URL
     * @return this builder
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Sets the organization ID.
     *
     * @param organization
     *            the organization ID
     * @return this builder
     */
    public Builder organization(String organization) {
      this.organization = organization;
      return this;
    }

    /**
     * Sets the request timeout in seconds.
     *
     * @param timeout
     *            the timeout
     * @return this builder
     */
    public Builder timeout(int timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Sets query parameters to append to API requests.
     *
     * @param queryParams
     *            the query parameters map
     * @return this builder
     */
    public Builder queryParams(Map<String, String> queryParams) {
      this.queryParams = queryParams;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public CompatOAIPluginOptions build() {
      if (apiKey == null || apiKey.isEmpty()) {
        throw new IllegalStateException("API key is required. Provide it in options.");
      }
      if (baseUrl == null || baseUrl.isEmpty()) {
        throw new IllegalStateException("Base URL is required. Provide it in options.");
      }
      return new CompatOAIPluginOptions(this);
    }
  }
}
