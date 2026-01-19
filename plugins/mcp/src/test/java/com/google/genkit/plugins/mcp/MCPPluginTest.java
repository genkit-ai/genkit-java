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

package com.google.genkit.plugins.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.ai.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

class MCPPluginTest {

  @Test
  void testCreateWithOptions() {
    MCPPluginOptions options = MCPPluginOptions.builder().name("test-mcp-host").build();

    MCPPlugin plugin = MCPPlugin.create(options);

    assertNotNull(plugin);
    assertEquals("mcp", plugin.getName());
  }

  @Test
  void testGetName() {
    MCPPluginOptions options = MCPPluginOptions.builder().name("test-host").build();

    MCPPlugin plugin = MCPPlugin.create(options);

    assertEquals("mcp", plugin.getName());
  }

  @Test
  void testCreateWithServerConfig() {
    MCPServerConfig serverConfig = MCPServerConfig.stdio("npx", "-y", "test-server");

    MCPPluginOptions options =
        MCPPluginOptions.builder().name("test-host").addServer("test-server", serverConfig).build();

    MCPPlugin plugin = MCPPlugin.create(options);

    assertNotNull(plugin);
  }

  @Test
  void testCreateWithHttpServerConfig() {
    MCPServerConfig serverConfig = MCPServerConfig.http("http://localhost:3001/mcp");

    MCPPluginOptions options =
        MCPPluginOptions.builder().name("test-host").addServer("http-server", serverConfig).build();

    MCPPlugin plugin = MCPPlugin.create(options);

    assertNotNull(plugin);
  }

  @Test
  void testGetTools() {
    MCPPluginOptions options = MCPPluginOptions.builder().name("test-host").build();

    MCPPlugin plugin = MCPPlugin.create(options);
    List<Tool<?, ?>> tools = plugin.getTools();

    assertNotNull(tools);
  }
}
