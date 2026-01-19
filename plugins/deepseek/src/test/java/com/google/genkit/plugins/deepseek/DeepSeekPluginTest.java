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

package com.google.genkit.plugins.deepseek;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.core.Action;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeepSeekPluginTest {

  @Test
  void testDefaultConstruction() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.deepseek.com/v1")
            .build();
    DeepSeekPlugin plugin = new DeepSeekPlugin(options);

    assertNotNull(plugin);
    assertEquals("deepseek", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.deepseek.com/v1")
            .build();

    DeepSeekPlugin plugin = new DeepSeekPlugin(options);

    assertNotNull(plugin);
    assertEquals("deepseek", plugin.getName());
  }

  @Test
  void testCreate() {
    DeepSeekPlugin plugin = DeepSeekPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("deepseek", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    DeepSeekPlugin plugin = DeepSeekPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("deepseek", plugin.getName());
  }

  @Test
  void testSupportedModels() {
    assertNotNull(DeepSeekPlugin.SUPPORTED_MODELS);
    assertTrue(DeepSeekPlugin.SUPPORTED_MODELS.contains("deepseek-chat"));
    assertTrue(DeepSeekPlugin.SUPPORTED_MODELS.contains("deepseek-reasoner"));
  }

  @Test
  void testInitializesActions() {
    DeepSeekPlugin plugin =
        new DeepSeekPlugin(
            CompatOAIPluginOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.deepseek.com/v1")
                .build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() >= 2, "Should register at least the supported models");
  }

  @Test
  void testCustomModel() {
    DeepSeekPlugin plugin = DeepSeekPlugin.create("test-api-key");
    plugin.customModel("custom-deepseek-model");

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 2, "Should include custom model in addition to supported models");
  }
}
