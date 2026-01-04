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

package com.google.genkit.plugins.mistral;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;

class MistralPluginTest {

  @Test
  void testDefaultConstruction() {
    MistralPlugin plugin = new MistralPlugin(
        CompatOAIPluginOptions.builder().apiKey("test-key").baseUrl("https://api.mistral.ai/v1").build());

    assertNotNull(plugin);
    assertEquals("mistral", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.mistral.ai/v1").build();

    MistralPlugin plugin = new MistralPlugin(options);

    assertNotNull(plugin);
    assertEquals("mistral", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    MistralPlugin plugin = MistralPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("mistral", plugin.getName());
  }

  @Test
  void testCreateDefault() {
    MistralPlugin plugin = MistralPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("mistral", plugin.getName());
  }

  @Test
  void testInitializesActions() {
    MistralPlugin plugin = new MistralPlugin(
        CompatOAIPluginOptions.builder().apiKey("test-key").baseUrl("https://api.mistral.ai/v1").build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register at least some actions");
  }

  @Test
  void testSupportedModels() {
    assertNotNull(MistralPlugin.SUPPORTED_MODELS);
    assertTrue(MistralPlugin.SUPPORTED_MODELS.contains("mistral-large-2512"));
    assertTrue(MistralPlugin.SUPPORTED_MODELS.contains("mistral-small-2506"));
  }

  @Test
  void testCustomModel() {
    MistralPlugin plugin = new MistralPlugin(
        CompatOAIPluginOptions.builder().apiKey("test-key").baseUrl("https://api.mistral.ai/v1").build());

    plugin.customModel("custom-mistral-model");

    assertEquals("mistral", plugin.getName());
  }

  @Test
  void testGetOptions() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.mistral.ai/v1").build();

    MistralPlugin plugin = new MistralPlugin(options);
    CompatOAIPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("test-key", retrievedOptions.getApiKey());
  }
}
