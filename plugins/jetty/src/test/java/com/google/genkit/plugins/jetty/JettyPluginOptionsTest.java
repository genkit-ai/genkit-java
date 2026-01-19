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

package com.google.genkit.plugins.jetty;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JettyPluginOptionsTest {

  @Test
  void testDefaultBuilder() {
    JettyPluginOptions options = JettyPluginOptions.builder().build();

    assertNotNull(options);
    assertEquals(8080, options.getPort());
    assertEquals("0.0.0.0", options.getHost());
    assertEquals("/api/flows", options.getBasePath());
  }

  @Test
  void testBuilderWithPort() {
    JettyPluginOptions options = JettyPluginOptions.builder().port(8080).build();

    assertEquals(8080, options.getPort());
  }

  @Test
  void testBuilderWithHost() {
    JettyPluginOptions options = JettyPluginOptions.builder().host("localhost").build();

    assertEquals("localhost", options.getHost());
  }

  @Test
  void testBuilderWithBasePath() {
    JettyPluginOptions options = JettyPluginOptions.builder().basePath("/custom").build();

    assertEquals("/custom", options.getBasePath());
  }

  @Test
  void testBuilderWithAllOptions() {
    JettyPluginOptions options =
        JettyPluginOptions.builder().port(9000).host("127.0.0.1").basePath("/v2").build();

    assertEquals(9000, options.getPort());
    assertEquals("127.0.0.1", options.getHost());
    assertEquals("/v2", options.getBasePath());
  }
}
