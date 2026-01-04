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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AzureFoundryPluginOptionsTest {

  @Test
  void testDefaultBuilder() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .build();

    assertNotNull(options);
  }

  @Test
  void testBuilderWithEndpoint() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder()
        .endpoint("https://test.openai.azure.com").build();

    assertEquals("https://test.openai.azure.com", options.getEndpoint());
  }

  @Test
  void testBuilderWithApiKey() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .apiKey("test-key-123").build();

    assertEquals("test-key-123", options.getApiKey());
  }

  @Test
  void testBuilderWithDeploymentId() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .deployment("my-deployment").build();

    assertEquals("my-deployment", options.getDeployment());
  }

  @Test
  void testBuilderWithAllOptions() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .apiKey("test-key").deployment("my-deployment").build();

    assertEquals("https://test.azure.com", options.getEndpoint());
    assertEquals("test-key", options.getApiKey());
    assertEquals("my-deployment", options.getDeployment());
  }
}
