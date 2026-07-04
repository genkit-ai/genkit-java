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

package com.google.genkit.plugins.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.ai.Tool;
import com.google.genkit.ai.agent.AgentSessionContext;
import com.google.genkit.ai.agent.Session;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.Registry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for {@link Artifacts} (Stage 6). */
class ArtifactsTest {

  private Registry registry;
  private ActionContext ctx;

  @BeforeEach
  void setUp() {
    registry = new DefaultRegistry();
    ctx = new ActionContext(registry);
  }

  private static Session<Map<String, Object>> newSession() {
    return new Session<>(SessionState.<Map<String, Object>>builder().build());
  }

  @SuppressWarnings("unchecked")
  private static Tool<Artifacts.ReadInput, Artifacts.ReadOutput> readTool(List<Tool<?, ?>> tools) {
    return (Tool<Artifacts.ReadInput, Artifacts.ReadOutput>)
        tools.stream()
            .filter(t -> Artifacts.READ_TOOL.equals(t.getName()))
            .findFirst()
            .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static Tool<Artifacts.WriteInput, Artifacts.WriteOutput> writeTool(
      List<Tool<?, ?>> tools) {
    return (Tool<Artifacts.WriteInput, Artifacts.WriteOutput>)
        tools.stream()
            .filter(t -> Artifacts.WRITE_TOOL.equals(t.getName()))
            .findFirst()
            .orElseThrow();
  }

  @Test
  void defaultProducesReadAndWriteTools() {
    List<Tool<?, ?>> tools = Artifacts.tools(ArtifactsOptions.defaults());
    assertEquals(2, tools.size());
    assertTrue(tools.stream().anyMatch(t -> Artifacts.READ_TOOL.equals(t.getName())));
    assertTrue(tools.stream().anyMatch(t -> Artifacts.WRITE_TOOL.equals(t.getName())));
  }

  @Test
  void readonlyOmitsWriteTool() {
    List<Tool<?, ?>> tools = Artifacts.tools(ArtifactsOptions.builder().readonly(true).build());
    assertEquals(1, tools.size());
    assertEquals(Artifacts.READ_TOOL, tools.get(0).getName());
    assertFalse(tools.stream().anyMatch(t -> Artifacts.WRITE_TOOL.equals(t.getName())));
  }

  @Test
  void writeThenReadRoundTrips() {
    List<Tool<?, ?>> tools = Artifacts.tools(ArtifactsOptions.defaults());
    Tool<Artifacts.WriteInput, Artifacts.WriteOutput> write = writeTool(tools);
    Tool<Artifacts.ReadInput, Artifacts.ReadOutput> read = readTool(tools);

    Session<Map<String, Object>> session = newSession();
    AgentSessionContext.run(
        session,
        () -> {
          Artifacts.WriteInput wi = new Artifacts.WriteInput();
          wi.name = "notes";
          wi.content = "hello world";
          Artifacts.WriteOutput wo = write.run(ctx, wi);
          assertEquals("ok", wo.status);

          Artifacts.ReadInput ri = new Artifacts.ReadInput();
          ri.name = "notes";
          Artifacts.ReadOutput ro = read.run(ctx, ri);
          assertTrue(ro.found);
          assertEquals("hello world", ro.content);
          assertEquals("notes", ro.name);
        });

    // Persisted on the underlying store, deduplicated by name.
    assertEquals(1, session.getArtifacts().size());
    assertEquals("notes", session.getArtifacts().get(0).getName());
  }

  @Test
  void writeDeduplicatesByName() {
    List<Tool<?, ?>> tools = Artifacts.tools(ArtifactsOptions.defaults());
    Tool<Artifacts.WriteInput, Artifacts.WriteOutput> write = writeTool(tools);
    Tool<Artifacts.ReadInput, Artifacts.ReadOutput> read = readTool(tools);

    Session<Map<String, Object>> session = newSession();
    AgentSessionContext.run(
        session,
        () -> {
          Artifacts.WriteInput w1 = new Artifacts.WriteInput();
          w1.name = "doc";
          w1.content = "v1";
          write.run(ctx, w1);

          Artifacts.WriteInput w2 = new Artifacts.WriteInput();
          w2.name = "doc";
          w2.content = "v2";
          write.run(ctx, w2);

          Artifacts.ReadInput ri = new Artifacts.ReadInput();
          ri.name = "doc";
          Artifacts.ReadOutput ro = read.run(ctx, ri);
          assertEquals("v2", ro.content);
        });

    assertEquals(1, session.getArtifacts().size());
  }

  @Test
  void readMissingReportsNotFound() {
    List<Tool<?, ?>> tools = Artifacts.tools(ArtifactsOptions.defaults());
    Tool<Artifacts.ReadInput, Artifacts.ReadOutput> read = readTool(tools);

    Session<Map<String, Object>> session = newSession();
    AgentSessionContext.run(
        session,
        () -> {
          Artifacts.ReadInput ri = new Artifacts.ReadInput();
          ri.name = "absent";
          Artifacts.ReadOutput ro = read.run(ctx, ri);
          assertFalse(ro.found);
          assertNull(ro.content);
        });
  }

  @Test
  void degradesWhenNoActiveSession() {
    List<Tool<?, ?>> tools = Artifacts.tools(ArtifactsOptions.defaults());
    Tool<Artifacts.ReadInput, Artifacts.ReadOutput> read = readTool(tools);
    Tool<Artifacts.WriteInput, Artifacts.WriteOutput> write = writeTool(tools);

    // No AgentSessionContext bound on this thread.
    Artifacts.ReadInput ri = new Artifacts.ReadInput();
    ri.name = "x";
    Artifacts.ReadOutput ro = read.run(ctx, ri);
    assertFalse(ro.found);

    Artifacts.WriteInput wi = new Artifacts.WriteInput();
    wi.name = "x";
    wi.content = "y";
    Artifacts.WriteOutput wo = write.run(ctx, wi);
    assertEquals("no active session", wo.status);
  }
}
