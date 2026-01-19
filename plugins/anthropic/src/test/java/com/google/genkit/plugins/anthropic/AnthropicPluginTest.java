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

package com.google.genkit.plugins.anthropic;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.core.Action;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicPluginTest {

  @Test
  void testDefaultConstruction() {
    AnthropicPluginOptions options = AnthropicPluginOptions.builder().apiKey("test-key").build();
    AnthropicPlugin plugin = new AnthropicPlugin(options);

    assertNotNull(plugin);
    assertEquals("anthropic", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    AnthropicPluginOptions options =
        AnthropicPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://test.api.com")
            .timeout(30)
            .build();

    AnthropicPlugin plugin = new AnthropicPlugin(options);

    assertNotNull(plugin);
    assertEquals("anthropic", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    AnthropicPlugin plugin = AnthropicPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("anthropic", plugin.getName());
  }

  @Test
  void testCreateDefault() {
    AnthropicPlugin plugin = AnthropicPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("anthropic", plugin.getName());
  }

  @Test
  void testInitializesActions() {
    AnthropicPlugin plugin =
        new AnthropicPlugin(AnthropicPluginOptions.builder().apiKey("test-key").build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register at least some actions");
  }

  @Test
  void testSupportedModels() {
    assertNotNull(AnthropicPlugin.SUPPORTED_MODELS);
    assertTrue(AnthropicPlugin.SUPPORTED_MODELS.contains("claude-opus-4-5-20251101"));
    assertTrue(AnthropicPlugin.SUPPORTED_MODELS.contains("claude-sonnet-4-5-20250929"));
    assertTrue(AnthropicPlugin.SUPPORTED_MODELS.contains("claude-3-opus-20240229"));
  }

  @Test
  void testCustomModel() {
    AnthropicPlugin plugin =
        new AnthropicPlugin(AnthropicPluginOptions.builder().apiKey("test-key").build());

    plugin.customModel("custom-claude-model");

    assertEquals("anthropic", plugin.getName());
  }

  @Test
  void testGetOptions() {
    AnthropicPluginOptions options =
        AnthropicPluginOptions.builder().apiKey("test-key").timeout(45).build();

    AnthropicPlugin plugin = new AnthropicPlugin(options);
    AnthropicPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("test-key", retrievedOptions.getApiKey());
    assertEquals(45, retrievedOptions.getTimeout());
  }
}
