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

package com.google.genkit.plugins.compatoai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;

class CompatOAIPluginTest {

  @Test
  void testBuilderCreation() {
    CompatOAIPlugin.Builder builder = CompatOAIPlugin.builder();

    assertNotNull(builder);
  }

  @Test
  void testBuilderWithMinimalConfig() {
    CompatOAIPlugin plugin = CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key")
        .baseUrl("https://api.test.com/v1").addModel("model-v1").build();

    assertNotNull(plugin);
    assertEquals("test-provider", plugin.getName());
  }

  @Test
  void testBuilderWithMultipleModels() {
    CompatOAIPlugin plugin = CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key")
        .baseUrl("https://api.test.com/v1").addModel("model-v1").addModel("model-v2").addModel("model-v3")
        .build();

    assertNotNull(plugin);
    assertEquals("test-provider", plugin.getName());
  }

  @Test
  void testBuilderWithModelsVarargs() {
    CompatOAIPlugin plugin = CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key")
        .baseUrl("https://api.test.com/v1").addModels("model-v1", "model-v2", "model-v3").build();

    assertNotNull(plugin);
    assertEquals("test-provider", plugin.getName());
  }

  @Test
  void testBuilderWithCustomLabel() {
    CompatOAIPlugin plugin = CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key")
        .baseUrl("https://api.test.com/v1").addModel("model-v1", "Custom Model Label").build();

    assertNotNull(plugin);
    assertEquals("test-provider", plugin.getName());
  }

  @Test
  void testBuilderWithAllOptions() {
    CompatOAIPlugin plugin = CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key")
        .baseUrl("https://api.test.com/v1").organization("test-org").timeout(120).addModel("model-v1").build();

    assertNotNull(plugin);
    assertEquals("test-provider", plugin.getName());

    CompatOAIPluginOptions options = plugin.getOptions();
    assertNotNull(options);
    assertEquals("test-key", options.getApiKey());
    assertEquals("https://api.test.com/v1", options.getBaseUrl());
    assertEquals("test-org", options.getOrganization());
    assertEquals(120, options.getTimeout());
  }

  @Test
  void testInitializesActions() {
    CompatOAIPlugin plugin = CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key")
        .baseUrl("https://api.test.com/v1").addModels("model-v1", "model-v2").build();

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertEquals(2, actions.size(), "Should register 2 models");
  }

  @Test
  void testBuilderThrowsExceptionWhenPluginNameMissing() {
    assertThrows(IllegalStateException.class, () -> {
      CompatOAIPlugin.builder().apiKey("test-key").baseUrl("https://api.test.com/v1").addModel("model-v1")
          .build();
    });
  }

  @Test
  void testBuilderThrowsExceptionWhenNoModels() {
    assertThrows(IllegalStateException.class, () -> {
      CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key").baseUrl("https://api.test.com/v1")
          .build();
    });
  }

  @Test
  void testBuilderThrowsExceptionWhenAddingModelBeforePluginName() {
    assertThrows(IllegalStateException.class, () -> {
      CompatOAIPlugin.builder().apiKey("test-key").baseUrl("https://api.test.com/v1").addModel("model-v1");
    });
  }

  @Test
  void testGetOptions() {
    CompatOAIPlugin plugin = CompatOAIPlugin.builder().pluginName("test-provider").apiKey("test-key")
        .baseUrl("https://api.test.com/v1").addModel("model-v1").build();

    CompatOAIPluginOptions options = plugin.getOptions();

    assertNotNull(options);
    assertEquals("test-key", options.getApiKey());
    assertEquals("https://api.test.com/v1", options.getBaseUrl());
  }

  @Test
  void testModelDefinitionWithDefaultLabel() {
    CompatOAIPlugin.ModelDefinition modelDef = new CompatOAIPlugin.ModelDefinition("test-provider", "model-v1");

    assertEquals("test-provider/model-v1", modelDef.getFullName());
    assertEquals("test-provider model-v1", modelDef.getLabel());
  }

  @Test
  void testModelDefinitionWithCustomLabel() {
    CompatOAIPlugin.ModelDefinition modelDef = new CompatOAIPlugin.ModelDefinition("test-provider", "model-v1",
        "Custom Label");

    assertEquals("test-provider/model-v1", modelDef.getFullName());
    assertEquals("Custom Label", modelDef.getLabel());
  }
}
