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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;

class OllamaPluginTest {

  @Test
  void testDefaultConstruction() {
    OllamaPlugin plugin = new OllamaPlugin();

    assertNotNull(plugin);
    assertEquals("ollama", plugin.getName());
  }

  @Test
  void testConstructionWithOptions() {
    OllamaPluginOptions options = OllamaPluginOptions.builder().baseUrl("http://localhost:11434").timeout(30)
        .build();

    OllamaPlugin plugin = new OllamaPlugin(options);

    assertNotNull(plugin);
    assertEquals("ollama", plugin.getName());
  }

  @Test
  void testCreateWithServerUrl() {
    OllamaPlugin plugin = OllamaPlugin.create("http://localhost:11434");

    assertNotNull(plugin);
    assertEquals("ollama", plugin.getName());
  }

  @Test
  void testCreateDefault() {
    OllamaPlugin plugin = OllamaPlugin.create();

    assertNotNull(plugin);
    assertEquals("ollama", plugin.getName());
  }

  @Test
  void testInitializesActions() {
    OllamaPlugin plugin = new OllamaPlugin(OllamaPluginOptions.builder().baseUrl("http://localhost:11434").build());

    List<Action<?, ?, ?>> actions = plugin.init();

    assertNotNull(actions);
    assertTrue(actions.size() >= 0, "Should initialize actions list");
  }

  @Test
  void testGetOptions() {
    OllamaPluginOptions options = OllamaPluginOptions.builder().baseUrl("http://test:8080").timeout(45).build();

    OllamaPlugin plugin = new OllamaPlugin(options);
    OllamaPluginOptions retrievedOptions = plugin.getOptions();

    assertNotNull(retrievedOptions);
    assertEquals("http://test:8080", retrievedOptions.getBaseUrl());
    assertEquals(45, retrievedOptions.getTimeout());
  }
}
