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

package com.google.genkit.plugins.xai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;

class XAIPluginTest {

  @Test
  void testDefaultConstruction() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.x.ai/v1").build();
    XAIPlugin plugin = new XAIPlugin(options);

    assertNotNull(plugin);
    assertEquals("xai", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.x.ai/v1").build();

    XAIPlugin plugin = new XAIPlugin(options);

    assertNotNull(plugin);
    assertEquals("xai", plugin.getName());
  }

  @Test
  void testCreate() {
    XAIPlugin plugin = XAIPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("xai", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    XAIPlugin plugin = XAIPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("xai", plugin.getName());
  }

  @Test
  void testSupportedModels() {
    assertNotNull(XAIPlugin.SUPPORTED_MODELS);
    assertTrue(XAIPlugin.SUPPORTED_MODELS.contains("grok-4"));
    assertTrue(XAIPlugin.SUPPORTED_MODELS.contains("grok-4-1-fast"));
    assertTrue(XAIPlugin.SUPPORTED_MODELS.contains("grok-3"));
    assertTrue(XAIPlugin.SUPPORTED_MODELS.contains("grok-code-fast-1"));
  }

  @Test
  void testInitializesActions() {
    XAIPlugin plugin = new XAIPlugin(
        CompatOAIPluginOptions.builder().apiKey("test-key").baseUrl("https://api.x.ai/v1").build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() >= XAIPlugin.SUPPORTED_MODELS.size(),
        "Should register at least all supported models");
  }

  @Test
  void testCustomModel() {
    XAIPlugin plugin = XAIPlugin.create("test-api-key");
    plugin.customModel("custom-grok-model");

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > XAIPlugin.SUPPORTED_MODELS.size(),
        "Should include custom model in addition to supported models");
  }
}
