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

package com.google.genkit.plugins.spring;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Tests for SpringPluginOptions. */
class SpringPluginOptionsTest {

  @Test
  void testDefaultOptions() {
    SpringPluginOptions options = SpringPluginOptions.builder().build();

    assertEquals(8080, options.getPort());
    assertEquals("0.0.0.0", options.getHost());
    assertEquals("/api/flows", options.getBasePath());
    assertEquals("", options.getContextPath());
  }

  @Test
  void testCustomPort() {
    SpringPluginOptions options = SpringPluginOptions.builder().port(9090).build();

    assertEquals(9090, options.getPort());
  }

  @Test
  void testCustomHost() {
    SpringPluginOptions options = SpringPluginOptions.builder().host("localhost").build();

    assertEquals("localhost", options.getHost());
  }

  @Test
  void testCustomBasePath() {
    SpringPluginOptions options = SpringPluginOptions.builder().basePath("/custom/path").build();

    assertEquals("/custom/path", options.getBasePath());
  }

  @Test
  void testCustomContextPath() {
    SpringPluginOptions options = SpringPluginOptions.builder().contextPath("/myapp").build();

    assertEquals("/myapp", options.getContextPath());
  }

  @Test
  void testFullCustomization() {
    SpringPluginOptions options =
        SpringPluginOptions.builder()
            .port(3000)
            .host("127.0.0.1")
            .basePath("/v1/flows")
            .contextPath("/api")
            .build();

    assertEquals(3000, options.getPort());
    assertEquals("127.0.0.1", options.getHost());
    assertEquals("/v1/flows", options.getBasePath());
    assertEquals("/api", options.getContextPath());
  }
}
