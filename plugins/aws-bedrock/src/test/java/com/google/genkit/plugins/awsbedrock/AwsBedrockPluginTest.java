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

package com.google.genkit.plugins.awsbedrock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;

class AwsBedrockPluginTest {

  @Test
  void testDefaultConstruction() {
    AwsBedrockPluginOptions options = AwsBedrockPluginOptions.builder().build();
    AwsBedrockPlugin plugin = new AwsBedrockPlugin(options);

    assertNotNull(plugin);
    assertEquals("aws-bedrock", plugin.getName());
  }

  @Test
  void testConstructionWithRegion() {
    AwsBedrockPluginOptions options = AwsBedrockPluginOptions.builder().region("us-east-1").build();

    AwsBedrockPlugin plugin = new AwsBedrockPlugin(options);

    assertNotNull(plugin);
    assertEquals("aws-bedrock", plugin.getName());
  }

  @Test
  void testCreateWithOptions() {
    AwsBedrockPluginOptions options = AwsBedrockPluginOptions.builder().region("us-west-2").build();

    AwsBedrockPlugin plugin = new AwsBedrockPlugin(options);

    assertNotNull(plugin);
    assertEquals("aws-bedrock", plugin.getName());
  }

  @Test
  void testSupportedModels() {
    assertNotNull(AwsBedrockPlugin.SUPPORTED_MODELS);
    assertTrue(AwsBedrockPlugin.SUPPORTED_MODELS.size() > 0);
    assertTrue(AwsBedrockPlugin.SUPPORTED_MODELS.contains("amazon.nova-pro-v1:0"));
    assertTrue(AwsBedrockPlugin.SUPPORTED_MODELS.contains("anthropic.claude-sonnet-4-20250514-v1:0"));
  }

  @Test
  void testInitializesActions() {
    AwsBedrockPluginOptions options = AwsBedrockPluginOptions.builder().region("us-east-1").build();

    AwsBedrockPlugin plugin = new AwsBedrockPlugin(options);
    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > 0, "Should register supported models");
  }

  @Test
  void testCustomModel() {
    AwsBedrockPluginOptions options = AwsBedrockPluginOptions.builder().build();
    AwsBedrockPlugin plugin = new AwsBedrockPlugin(options);

    plugin.customModel("custom-bedrock-model");

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() > AwsBedrockPlugin.SUPPORTED_MODELS.size(), "Should include custom model");
  }
}
