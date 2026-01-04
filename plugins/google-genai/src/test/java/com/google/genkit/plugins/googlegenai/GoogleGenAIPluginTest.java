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

package com.google.genkit.plugins.googlegenai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;

class GoogleGenAIPluginTest {

  @Test
  void testDefaultConstruction() {
    GoogleGenAIPluginOptions options = GoogleGenAIPluginOptions.builder().apiKey("test-key").build();
    GoogleGenAIPlugin plugin = new GoogleGenAIPlugin(options);

    assertNotNull(plugin);
    assertEquals("googleai", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    GoogleGenAIPluginOptions options = GoogleGenAIPluginOptions.builder().apiKey("test-key").build();

    GoogleGenAIPlugin plugin = new GoogleGenAIPlugin(options);

    assertNotNull(plugin);
    assertEquals("googleai", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    GoogleGenAIPlugin plugin = GoogleGenAIPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("googleai", plugin.getName());
  }

  @Test
  void testCreateDefault() {
    GoogleGenAIPlugin plugin = GoogleGenAIPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("googleai", plugin.getName());
  }

  @Test
  void testInitializesActions() {
    GoogleGenAIPlugin plugin = new GoogleGenAIPlugin(GoogleGenAIPluginOptions.builder().apiKey("test-key").build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register at least some actions");
  }

  @Test
  void testSupportedModels() {
    assertNotNull(GoogleGenAIPlugin.SUPPORTED_MODELS);
    assertTrue(GoogleGenAIPlugin.SUPPORTED_MODELS.size() > 0);
  }

  @Test
  void testGetOptions() {
    GoogleGenAIPluginOptions options = GoogleGenAIPluginOptions.builder().apiKey("test-key").build();

    GoogleGenAIPlugin plugin = new GoogleGenAIPlugin(options);
    GoogleGenAIPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("test-key", retrievedOptions.getApiKey());
  }
}
