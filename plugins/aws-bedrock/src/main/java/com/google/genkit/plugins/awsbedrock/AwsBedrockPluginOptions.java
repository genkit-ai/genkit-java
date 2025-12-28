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

package com.google.genkit.plugins.awsbedrock;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Configuration options for the AWS Bedrock plugin.
 */
public class AwsBedrockPluginOptions {

  private final Region region;
  private final AwsCredentialsProvider credentialsProvider;

  private AwsBedrockPluginOptions(Builder builder) {
    this.region = builder.region;
    this.credentialsProvider = builder.credentialsProvider;
  }

  /**
   * Gets the AWS region.
   *
   * @return the region
   */
  public Region getRegion() {
    return region;
  }

  /**
   * Gets the AWS credentials provider.
   *
   * @return the credentials provider
   */
  public AwsCredentialsProvider getCredentialsProvider() {
    return credentialsProvider;
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
   * Builder for AwsBedrockPluginOptions.
   */
  public static class Builder {

    private Region region = Region.US_EAST_1;
    private AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();

    /**
     * Sets the AWS region.
     *
     * @param region
     *            the region
     * @return this builder
     */
    public Builder region(Region region) {
      this.region = region;
      return this;
    }

    /**
     * Sets the AWS region by region string.
     *
     * @param regionString
     *            the region string (e.g., "us-east-1")
     * @return this builder
     */
    public Builder region(String regionString) {
      this.region = Region.of(regionString);
      return this;
    }

    /**
     * Sets the AWS credentials provider.
     *
     * @param credentialsProvider
     *            the credentials provider
     * @return this builder
     */
    public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
      this.credentialsProvider = credentialsProvider;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public AwsBedrockPluginOptions build() {
      return new AwsBedrockPluginOptions(this);
    }
  }
}
