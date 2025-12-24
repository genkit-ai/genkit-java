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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;

/**
 * Tests for SpringPlugin.
 */
class SpringPluginTest {

  @Test
  void testPluginName() {
    SpringPlugin plugin = new SpringPlugin();
    assertEquals("spring", plugin.getName());
  }

  @Test
  void testDefaultOptions() {
    SpringPlugin plugin = new SpringPlugin();
    assertEquals(8080, plugin.getPort());
  }

  @Test
  void testCustomPort() {
    SpringPlugin plugin = new SpringPlugin(SpringPluginOptions.builder().port(9090).build());
    assertEquals(9090, plugin.getPort());
  }

  @Test
  void testStaticCreate() {
    SpringPlugin plugin = SpringPlugin.create(3000);
    assertEquals(3000, plugin.getPort());
  }

  @Test
  void testInitReturnsEmptyList() {
    SpringPlugin plugin = new SpringPlugin();
    List<Action<?, ?, ?>> actions = plugin.init();
    assertNotNull(actions);
    assertTrue(actions.isEmpty());
  }

  @Test
  void testIsNotRunningByDefault() {
    SpringPlugin plugin = new SpringPlugin();
    assertFalse(plugin.isRunning());
  }
}
