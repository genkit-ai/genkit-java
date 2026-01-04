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

package com.google.genkit.plugins.ollama;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OllamaPluginOptionsTest {

  @Test
  void testDefaultBuilder() {
    OllamaPluginOptions options = OllamaPluginOptions.builder().build();

    assertNotNull(options);
    assertEquals("http://localhost:11434", options.getBaseUrl());
    assertEquals(300, options.getTimeout());
  }

  @Test
  void testBuilderWithServerUrl() {
    OllamaPluginOptions options = OllamaPluginOptions.builder().baseUrl("http://custom:8080").build();

    assertEquals("http://custom:8080", options.getBaseUrl());
  }

  @Test
  void testBuilderWithTimeout() {
    OllamaPluginOptions options = OllamaPluginOptions.builder().timeout(120).build();

    assertEquals(120, options.getTimeout());
  }

  @Test
  void testBuilderWithAllOptions() {
    OllamaPluginOptions options = OllamaPluginOptions.builder().baseUrl("http://test:9000").timeout(30).build();

    assertEquals("http://test:9000", options.getBaseUrl());
    assertEquals(30, options.getTimeout());
  }
}
