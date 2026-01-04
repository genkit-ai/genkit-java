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

import org.junit.jupiter.api.Test;

class CompatOAIPluginOptionsTest {

  @Test
  void testBuilderWithRequiredFields() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").build();

    assertNotNull(options);
    assertEquals("test-key", options.getApiKey());
    assertEquals("https://api.test.com/v1", options.getBaseUrl());
  }

  @Test
  void testBuilderWithApiKey() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key-123")
        .baseUrl("https://api.test.com/v1").build();

    assertEquals("test-key-123", options.getApiKey());
  }

  @Test
  void testBuilderWithBaseUrl() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.custom.com/v1").build();

    assertEquals("https://api.custom.com/v1", options.getBaseUrl());
  }

  @Test
  void testBuilderWithOrganization() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").organization("test-org").build();

    assertEquals("test-org", options.getOrganization());
  }

  @Test
  void testBuilderWithTimeout() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").timeout(120).build();

    assertEquals(120, options.getTimeout());
  }

  @Test
  void testBuilderWithAllOptions() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").organization("test-org").timeout(90).build();

    assertEquals("test-key", options.getApiKey());
    assertEquals("https://api.test.com/v1", options.getBaseUrl());
    assertEquals("test-org", options.getOrganization());
    assertEquals(90, options.getTimeout());
  }

  @Test
  void testBuilderChaining() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("key").baseUrl("url")
        .organization("org").timeout(60).build();

    assertNotNull(options);
    assertEquals("key", options.getApiKey());
    assertEquals("url", options.getBaseUrl());
    assertEquals("org", options.getOrganization());
    assertEquals(60, options.getTimeout());
  }
}
