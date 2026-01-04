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

import org.junit.jupiter.api.Test;

class GoogleGenAIPluginOptionsTest {

  @Test
  void testDefaultBuilder() {
    GoogleGenAIPluginOptions options = GoogleGenAIPluginOptions.builder().build();

    assertNotNull(options);
  }

  @Test
  void testBuilderWithApiKey() {
    GoogleGenAIPluginOptions options = GoogleGenAIPluginOptions.builder().apiKey("test-key-123").build();

    assertEquals("test-key-123", options.getApiKey());
  }

  @Test
  void testBuilderWithBaseUrl() {
    GoogleGenAIPluginOptions options = GoogleGenAIPluginOptions.builder().baseUrl("https://custom.google.com")
        .build();

    assertEquals("https://custom.google.com", options.getBaseUrl());
  }

  @Test
  void testBuilderWithAllOptions() {
    GoogleGenAIPluginOptions options = GoogleGenAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://test.com").build();

    assertEquals("test-key", options.getApiKey());
    assertEquals("https://test.com", options.getBaseUrl());
  }
}
