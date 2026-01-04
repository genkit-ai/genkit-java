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

class JettyPluginTest {

  @Test
  void testDefaultConstruction() {
    JettyPlugin plugin = new JettyPlugin();

    assertNotNull(plugin);
    assertEquals("jetty", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    JettyPluginOptions options = JettyPluginOptions.builder().port(8080).host("0.0.0.0").build();

    JettyPlugin plugin = new JettyPlugin(options);

    assertNotNull(plugin);
    assertEquals("jetty", plugin.getName());
  }

  @Test
  void testCreateWithPort() {
    JettyPlugin plugin = JettyPlugin.create(9000);

    assertNotNull(plugin);
    assertEquals("jetty", plugin.getName());
  }

  @Test
  void testImplementsServerPlugin() {
    JettyPlugin plugin = new JettyPlugin();

    assertTrue(plugin instanceof com.google.genkit.core.ServerPlugin);
  }
}
