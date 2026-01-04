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

import org.junit.jupiter.api.Test;

class OpenAIPluginOptionsTest {

  @Test
  void testDefaultBuilder() {
    OpenAIPluginOptions options = OpenAIPluginOptions.builder().build();

    assertNotNull(options);
    assertEquals("https://api.openai.com/v1", options.getBaseUrl());
    assertEquals(60, options.getTimeout());
  }

  @Test
  void testBuilderWithApiKey() {
    OpenAIPluginOptions options = OpenAIPluginOptions.builder().apiKey("test-key-123").build();

    assertEquals("test-key-123", options.getApiKey());
  }

  @Test
  void testBuilderWithBaseUrl() {
    OpenAIPluginOptions options = OpenAIPluginOptions.builder().baseUrl("https://custom.openai.com/v1").build();

    assertEquals("https://custom.openai.com/v1", options.getBaseUrl());
  }

  @Test
  void testBuilderWithOrganization() {
    OpenAIPluginOptions options = OpenAIPluginOptions.builder().organization("org-test-123").build();

    assertEquals("org-test-123", options.getOrganization());
  }

  @Test
  void testBuilderWithTimeout() {
    OpenAIPluginOptions options = OpenAIPluginOptions.builder().timeout(120).build();

    assertEquals(120, options.getTimeout());
  }

  @Test
  void testBuilderWithAllOptions() {
    OpenAIPluginOptions options = OpenAIPluginOptions.builder().apiKey("test-key").baseUrl("https://test.com/v1")
        .organization("org-123").timeout(30).build();

    assertEquals("test-key", options.getApiKey());
    assertEquals("https://test.com/v1", options.getBaseUrl());
    assertEquals("org-123", options.getOrganization());
    assertEquals(30, options.getTimeout());
  }

  @Test
  void testBuilderIsReusable() {
    OpenAIPluginOptions.Builder builder = OpenAIPluginOptions.builder().apiKey("key1");

    OpenAIPluginOptions options1 = builder.build();
    OpenAIPluginOptions options2 = builder.apiKey("key2").build();

    // Both options should be valid but independent
    assertNotNull(options1);
    assertNotNull(options2);
  }
}
