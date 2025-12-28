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

package com.google.genkit.plugins.azurefoundry;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

/**
 * Configuration options for the Azure AI Foundry plugin.
 */
public class AzureFoundryPluginOptions {

  private final String endpoint;
  private final String apiKey;
  private final TokenCredential credential;
  private final String deployment;
  private final String apiVersion;

  private AzureFoundryPluginOptions(Builder builder) {
    this.endpoint = builder.endpoint;
    this.apiKey = builder.apiKey;
    this.credential = builder.credential;
    this.deployment = builder.deployment;
    this.apiVersion = builder.apiVersion != null ? builder.apiVersion : "2024-10-01-preview";
  }

  /**
   * Gets the Azure AI Foundry endpoint URL.
   *
   * @return the endpoint
   */
  public String getEndpoint() {
    return endpoint;
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
   * Gets the Azure credential.
   *
   * @return the credential
   */
  public TokenCredential getCredential() {
    return credential;
  }

  /**
   * Gets the deployment name.
   *
   * @return the deployment name
   */
  public String getDeployment() {
    return deployment;
  }

  /**
   * Gets the API version.
   *
   * @return the API version
   */
  public String getApiVersion() {
    return apiVersion;
  }

  /**
   * Creates a new builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for AzureFoundryPluginOptions.
   */
  public static class Builder {

    private String endpoint;
    private String apiKey;
    private TokenCredential credential;
    private String deployment;
    private String apiVersion;

    /**
     * Sets the Azure AI Foundry endpoint URL.
     *
     * @param endpoint
     *            the endpoint URL (e.g.,
     *            "https://my-project.eastus.models.ai.azure.com")
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Sets the API key for authentication.
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
     * Sets the Azure credential for authentication (alternative to API key).
     *
     * @param credential
     *            the Azure token credential
     * @return this builder
     */
    public Builder credential(TokenCredential credential) {
      this.credential = credential;
      return this;
    }

    /**
     * Sets the deployment name.
     *
     * @param deployment
     *            the deployment name
     * @return this builder
     */
    public Builder deployment(String deployment) {
      this.deployment = deployment;
      return this;
    }

    /**
     * Sets the API version.
     *
     * @param apiVersion
     *            the API version (default: "2024-10-01-preview")
     * @return this builder
     */
    public Builder apiVersion(String apiVersion) {
      this.apiVersion = apiVersion;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public AzureFoundryPluginOptions build() {
      // Use default Azure credential if neither API key nor credential is provided
      if (this.apiKey == null && this.credential == null) {
        this.credential = new DefaultAzureCredentialBuilder().build();
      }

      if (this.endpoint == null) {
        // Try to get from environment variable
        this.endpoint = System.getenv("AZURE_AI_FOUNDRY_ENDPOINT");
        if (this.endpoint == null) {
          throw new IllegalStateException(
              "Azure AI Foundry endpoint is required. Set it via builder or AZURE_AI_FOUNDRY_ENDPOINT environment variable.");
        }
      }

      return new AzureFoundryPluginOptions(this);
    }
  }
}
