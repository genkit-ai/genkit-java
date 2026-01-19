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

package com.google.genkit.plugins.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.core.Action;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAIPluginTest {

  @Test
  void testDefaultConstruction() {
    OpenAIPluginOptions options = OpenAIPluginOptions.builder().apiKey("test-key").build();
    OpenAIPlugin plugin = new OpenAIPlugin(options);

    assertNotNull(plugin);
    assertEquals("openai", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    OpenAIPluginOptions options =
        OpenAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://test.api.com")
            .timeout(30)
            .build();

    OpenAIPlugin plugin = new OpenAIPlugin(options);

    assertNotNull(plugin);
    assertEquals("openai", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    OpenAIPlugin plugin = OpenAIPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("openai", plugin.getName());
  }

  @Test
  void testCreateDefault() {
    OpenAIPlugin plugin = OpenAIPlugin.create("test-api-key");

    assertNotNull(plugin);
    assertEquals("openai", plugin.getName());
  }

  @Test
  void testInitializesActions() {
    OpenAIPlugin plugin =
        new OpenAIPlugin(OpenAIPluginOptions.builder().apiKey("test-key").build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register at least some actions");
  }

  @Test
  void testSupportedModels() {
    assertNotNull(OpenAIPlugin.SUPPORTED_MODELS);
    assertTrue(OpenAIPlugin.SUPPORTED_MODELS.contains("gpt-4o"));
    assertTrue(OpenAIPlugin.SUPPORTED_MODELS.contains("gpt-4-turbo"));
    assertTrue(OpenAIPlugin.SUPPORTED_MODELS.contains("gpt-3.5-turbo"));
  }

  @Test
  void testSupportedEmbeddingModels() {
    assertNotNull(OpenAIPlugin.SUPPORTED_EMBEDDING_MODELS);
    assertTrue(OpenAIPlugin.SUPPORTED_EMBEDDING_MODELS.contains("text-embedding-3-small"));
    assertTrue(OpenAIPlugin.SUPPORTED_EMBEDDING_MODELS.contains("text-embedding-3-large"));
    assertTrue(OpenAIPlugin.SUPPORTED_EMBEDDING_MODELS.contains("text-embedding-ada-002"));
  }

  @Test
  void testSupportedImageModels() {
    assertNotNull(OpenAIPlugin.SUPPORTED_IMAGE_MODELS);
    assertTrue(OpenAIPlugin.SUPPORTED_IMAGE_MODELS.contains("dall-e-3"));
    assertTrue(OpenAIPlugin.SUPPORTED_IMAGE_MODELS.contains("dall-e-2"));
    assertTrue(OpenAIPlugin.SUPPORTED_IMAGE_MODELS.contains("gpt-image-1"));
  }

  @Test
  void testCustomModel() {
    OpenAIPlugin plugin =
        new OpenAIPlugin(OpenAIPluginOptions.builder().apiKey("test-key").build());

    plugin.customModel("custom-model");

    // Verify the plugin still works after adding custom model
    assertEquals("openai", plugin.getName());
  }

  @Test
  void testCustomEmbeddingModel() {
    OpenAIPlugin plugin =
        new OpenAIPlugin(OpenAIPluginOptions.builder().apiKey("test-key").build());

    plugin.customEmbeddingModel("custom-embedding");

    assertEquals("openai", plugin.getName());
  }

  @Test
  void testCustomImageModel() {
    OpenAIPlugin plugin =
        new OpenAIPlugin(OpenAIPluginOptions.builder().apiKey("test-key").build());

    plugin.customImageModel("custom-image-model");

    assertEquals("openai", plugin.getName());
  }

  @Test
  void testGetOptions() {
    OpenAIPluginOptions options =
        OpenAIPluginOptions.builder().apiKey("test-key").organization("org-123").build();

    OpenAIPlugin plugin = new OpenAIPlugin(options);
    OpenAIPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("test-key", retrievedOptions.getApiKey());
    assertEquals("org-123", retrievedOptions.getOrganization());
  }
}
