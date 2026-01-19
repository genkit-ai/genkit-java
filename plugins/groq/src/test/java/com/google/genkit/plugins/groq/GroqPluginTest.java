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

package com.google.genkit.plugins.groq;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.core.Action;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroqPluginTest {

  @Test
  void testDefaultConstruction() {
    GroqPlugin plugin =
        new GroqPlugin(
            CompatOAIPluginOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.groq.com/openai/v1")
                .build());

    assertNotNull(plugin);
    assertEquals("groq", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.groq.com/openai/v1")
            .build();

    GroqPlugin plugin = new GroqPlugin(options);

    assertNotNull(plugin);
    assertEquals("groq", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    GroqPlugin plugin = GroqPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("groq", plugin.getName());
  }

  @Test
  void testCreateDefault() {
    GroqPlugin plugin = GroqPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("groq", plugin.getName());
  }

  @Test
  void testInitializesActions() {
    GroqPlugin plugin =
        new GroqPlugin(
            CompatOAIPluginOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.groq.com/openai/v1")
                .build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register at least some actions");
  }

  @Test
  void testSupportedModels() {
    assertNotNull(GroqPlugin.SUPPORTED_MODELS);
    assertTrue(GroqPlugin.SUPPORTED_MODELS.contains("llama-3.1-8b-instant"));
    assertTrue(GroqPlugin.SUPPORTED_MODELS.contains("llama-3.3-70b-versatile"));
  }

  @Test
  void testCustomModel() {
    GroqPlugin plugin =
        new GroqPlugin(
            CompatOAIPluginOptions.builder()
                .apiKey("test-key")
                .baseUrl("https://api.groq.com/openai/v1")
                .build());

    plugin.customModel("custom-groq-model");

    assertEquals("groq", plugin.getName());
  }

  @Test
  void testGetOptions() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.groq.com/openai/v1")
            .build();

    GroqPlugin plugin = new GroqPlugin(options);
    CompatOAIPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("test-key", retrievedOptions.getApiKey());
  }
}
