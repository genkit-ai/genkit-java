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

package com.google.genkit.plugins.cohere;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.core.Action;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoherePluginTest {

  @Test
  void testDefaultConstruction() {
    CoherePlugin plugin =
        new CoherePlugin(
            CompatOAIPluginOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.cohere.ai/compatibility/v1")
                .build());

    assertNotNull(plugin);
    assertEquals("cohere", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.cohere.ai/compatibility/v1")
            .build();

    CoherePlugin plugin = new CoherePlugin(options);

    assertNotNull(plugin);
    assertEquals("cohere", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    CoherePlugin plugin = CoherePlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("cohere", plugin.getName());
  }

  @Test
  void testCreateDefault() {
    CoherePlugin plugin = CoherePlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("cohere", plugin.getName());
  }

  @Test
  void testInitializesActions() {
    CoherePlugin plugin =
        new CoherePlugin(
            CompatOAIPluginOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.cohere.ai/compatibility/v1")
                .build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register at least some actions");
  }

  @Test
  void testSupportedModels() {
    assertNotNull(CoherePlugin.SUPPORTED_MODELS);
    assertTrue(CoherePlugin.SUPPORTED_MODELS.contains("command-a-03-2025"));
    assertTrue(CoherePlugin.SUPPORTED_MODELS.contains("command-r-plus-08-2024"));
  }

  @Test
  void testCustomModel() {
    CoherePlugin plugin =
        new CoherePlugin(
            CompatOAIPluginOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.cohere.ai/compatibility/v1")
                .build());

    plugin.customModel("custom-cohere-model");

    assertEquals("cohere", plugin.getName());
  }

  @Test
  void testGetOptions() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.cohere.ai/compatibility/v1")
            .build();

    CoherePlugin plugin = new CoherePlugin(options);
    CompatOAIPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("test-key", retrievedOptions.getApiKey());
  }
}
