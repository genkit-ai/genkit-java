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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;

class AzureFoundryPluginTest {

  @Test
  void testConstructionWithOptions() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .apiKey("test-key").build();

    AzureFoundryPlugin plugin = new AzureFoundryPlugin(options);

    assertNotNull(plugin);
    assertEquals("azure-foundry", plugin.getName());
  }

  @Test
  void testCreateWithEndpointAndApiKey() {
    AzureFoundryPlugin plugin = AzureFoundryPlugin.create("https://test.azure.com", "test-api-key");

    assertNotNull(plugin);
    assertEquals("azure-foundry", plugin.getName());
  }

  @Test
  void testSupportedModels() {
    assertNotNull(AzureFoundryPlugin.SUPPORTED_MODELS);
    assertTrue(AzureFoundryPlugin.SUPPORTED_MODELS.size() > 0);
    assertTrue(AzureFoundryPlugin.SUPPORTED_MODELS.contains("gpt-4o"));
    assertTrue(AzureFoundryPlugin.SUPPORTED_MODELS.contains("claude-opus-4-5"));
    assertTrue(AzureFoundryPlugin.SUPPORTED_MODELS.contains("llama-3-3-70b-instruct"));
  }

  @Test
  void testInitializesActions() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .apiKey("test-key").build();

    AzureFoundryPlugin plugin = new AzureFoundryPlugin(options);
    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register supported models");
  }

  @Test
  void testCustomModel() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .apiKey("test-key").build();

    AzureFoundryPlugin plugin = new AzureFoundryPlugin(options);
    plugin.customModel("custom-azure-model");

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > AzureFoundryPlugin.SUPPORTED_MODELS.size(), "Should include custom model");
  }

  @Test
  void testGetOptions() {
    AzureFoundryPluginOptions options = AzureFoundryPluginOptions.builder().endpoint("https://test.azure.com")
        .apiKey("test-key").build();

    AzureFoundryPlugin plugin = new AzureFoundryPlugin(options);
    AzureFoundryPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("https://test.azure.com", retrievedOptions.getEndpoint());
    assertEquals("test-key", retrievedOptions.getApiKey());
  }
}
