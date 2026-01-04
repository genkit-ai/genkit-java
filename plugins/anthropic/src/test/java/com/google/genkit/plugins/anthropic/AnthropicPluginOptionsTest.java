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

import org.junit.jupiter.api.Test;

class AnthropicPluginOptionsTest {

  @Test
  void testDefaultBuilder() {
    AnthropicPluginOptions options = AnthropicPluginOptions.builder().apiKey("test-key").build();

    assertNotNull(options);
    assertEquals("https://api.anthropic.com/v1", options.getBaseUrl());
    assertEquals(120, options.getTimeout());
  }

  @Test
  void testBuilderWithApiKey() {
    AnthropicPluginOptions options = AnthropicPluginOptions.builder().apiKey("test-key-123").build();

    assertEquals("test-key-123", options.getApiKey());
  }

  @Test
  void testBuilderWithBaseUrl() {
    AnthropicPluginOptions options = AnthropicPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://custom.anthropic.com").build();

    assertEquals("https://custom.anthropic.com", options.getBaseUrl());
  }

  @Test
  void testBuilderWithTimeout() {
    AnthropicPluginOptions options = AnthropicPluginOptions.builder().apiKey("test-key").timeout(120).build();

    assertEquals(120, options.getTimeout());
  }

  @Test
  void testBuilderWithAnthropicVersion() {
    AnthropicPluginOptions options = AnthropicPluginOptions.builder().apiKey("test-key")
        .anthropicVersion("2024-01-01").build();

    assertEquals("2024-01-01", options.getAnthropicVersion());
  }

  @Test
  void testBuilderWithAllOptions() {
    AnthropicPluginOptions options = AnthropicPluginOptions.builder().apiKey("test-key").baseUrl("https://test.com")
        .timeout(30).anthropicVersion("2024-03-01").build();

    assertEquals("test-key", options.getApiKey());
    assertEquals("https://test.com", options.getBaseUrl());
    assertEquals(30, options.getTimeout());
    assertEquals("2024-03-01", options.getAnthropicVersion());
  }
}
