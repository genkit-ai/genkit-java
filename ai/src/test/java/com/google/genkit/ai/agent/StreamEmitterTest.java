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

package com.google.genkit.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.agent.internal.StreamEmitter;
import com.google.genkit.core.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for StreamEmitter: customPatch streaming (whole-doc replace on first patch per turn,
 * then incremental diffs) and artifact chunk emission.
 */
class StreamEmitterTest {

  private ObjectMapper mapper;
  private List<AgentStreamChunk> emitted;
  private StreamEmitter<Map<String, Object>> emitter;

  @BeforeEach
  void setUp() {
    mapper = JsonUtils.getObjectMapper();
    emitted = new ArrayList<>();
    emitter = new StreamEmitter<>(emitted::add, mapper);
  }

  /** Helper to build a session with a given initial custom state. */
  private Session<Map<String, Object>> sessionWith(Map<String, Object> custom) {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().custom(custom).build();
    return new Session<>(state);
  }

  /** Helper to update the counter field in custom state. */
  private Map<String, Object> counterMap(int value) {
    Map<String, Object> m = new HashMap<>();
    m.put("counter", value);
    return m;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 1: Multi-custom-state updates in one turn
  //   - 3 updateCustom calls (counter 0→1, 1→2, 2→3)
  //   - 1st chunk: whole-doc replace with {counter:1}
  //   - 2nd chunk: incremental diff [{op:replace, path:"/counter", value:2}]
  //   - 3rd chunk: incremental diff [{op:replace, path:"/counter", value:3}]
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  void testThreeCustomUpdatesInOneTurn() {
    Session<Map<String, Object>> session = sessionWith(counterMap(0));
    emitter.attach(session);
    emitter.beginTurn();

    // Update 1: counter 0 → 1
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 1);
          return next;
        });

    // Update 2: counter 1 → 2
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 2);
          return next;
        });

    // Update 3: counter 2 → 3
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 3);
          return next;
        });

    assertEquals(3, emitted.size(), "exactly 3 chunks must be emitted");

    // Chunk 1: whole-doc replace at path "" with value {counter:1}
    AgentStreamChunk chunk1 = emitted.get(0);
    assertNotNull(chunk1.getCustomPatch(), "chunk1 must have a customPatch");
    assertTrue(chunk1.getCustomPatch().isArray(), "customPatch must be an array");
    assertEquals(1, chunk1.getCustomPatch().size(), "whole-doc replace: exactly 1 op");
    JsonNode op1 = chunk1.getCustomPatch().get(0);
    assertEquals("replace", op1.path("op").asText(), "op must be replace");
    assertEquals("", op1.path("path").asText(), "path must be empty string (root)");
    assertEquals(1, op1.path("value").path("counter").asInt(), "value must be {counter:1}");

    // Chunk 2: incremental diff [{op:replace, path:"/counter", value:2}]
    AgentStreamChunk chunk2 = emitted.get(1);
    assertNotNull(chunk2.getCustomPatch(), "chunk2 must have a customPatch");
    assertTrue(chunk2.getCustomPatch().isArray(), "customPatch must be an array");
    assertEquals(1, chunk2.getCustomPatch().size(), "incremental diff: exactly 1 op");
    JsonNode op2 = chunk2.getCustomPatch().get(0);
    assertEquals("replace", op2.path("op").asText());
    assertEquals("/counter", op2.path("path").asText());
    assertEquals(2, op2.path("value").asInt());

    // Chunk 3: incremental diff [{op:replace, path:"/counter", value:3}]
    AgentStreamChunk chunk3 = emitted.get(2);
    assertNotNull(chunk3.getCustomPatch(), "chunk3 must have a customPatch");
    assertEquals(1, chunk3.getCustomPatch().size(), "incremental diff: exactly 1 op");
    JsonNode op3 = chunk3.getCustomPatch().get(0);
    assertEquals("replace", op3.path("op").asText());
    assertEquals("/counter", op3.path("path").asText());
    assertEquals(3, op3.path("value").asInt());
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 2: Second beginTurn() resets to whole-doc replace
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  void testSecondBeginTurnResetsToWholeDocReplace() {
    Session<Map<String, Object>> session = sessionWith(counterMap(0));
    emitter.attach(session);

    // Turn 1
    emitter.beginTurn();
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 1);
          return next;
        });
    // One chunk emitted (whole-doc replace), counter is now at 1

    // Turn 2
    emitter.beginTurn();
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 2);
          return next;
        });

    assertEquals(2, emitted.size(), "one chunk per turn");

    // Chunk from turn 2 must be a whole-doc replace (not an incremental diff)
    AgentStreamChunk chunk2 = emitted.get(1);
    assertNotNull(chunk2.getCustomPatch());
    assertEquals(1, chunk2.getCustomPatch().size(), "turn-2 first patch: exactly 1 op");
    JsonNode op = chunk2.getCustomPatch().get(0);
    assertEquals("replace", op.path("op").asText());
    assertEquals("", op.path("path").asText(), "second turn must start with whole-doc replace");
    assertEquals(2, op.path("value").path("counter").asInt());
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 3: Artifact chunk emission
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  void testArtifactChunkEmitted() {
    Session<Map<String, Object>> session = sessionWith(null);
    emitter.attach(session);
    emitter.beginTurn();

    Artifact artifact = Artifact.builder().name("report").build();
    session.addArtifacts(artifact);

    assertEquals(1, emitted.size(), "exactly one chunk must be emitted for artifact");
    AgentStreamChunk chunk = emitted.get(0);
    assertNotNull(chunk.getArtifact(), "chunk must have an artifact");
    assertEquals("report", chunk.getArtifact().getName());
    assertNull(chunk.getCustomPatch(), "artifact chunk must not have customPatch");
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 4: setSuppressed(true) → nothing emitted
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  void testSuppressedEmitsNothing() {
    Session<Map<String, Object>> session = sessionWith(counterMap(0));
    emitter.attach(session);
    emitter.beginTurn();
    emitter.setSuppressed(true);

    // updateCustom must not emit
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 99);
          return next;
        });

    // addArtifacts must not emit
    session.addArtifacts(Artifact.builder().name("ignored").build());

    assertTrue(emitted.isEmpty(), "suppressed emitter must emit nothing");
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Test 5: Empty diff (no-op update) must not emit a chunk after the first
  // ──────────────────────────────────────────────────────────────────────────
  @Test
  void testEmptyDiffDoesNotEmitChunk() {
    Session<Map<String, Object>> session = sessionWith(counterMap(5));
    emitter.attach(session);
    emitter.beginTurn();

    // First update: whole-doc replace emitted
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 10);
          return next;
        });
    assertEquals(1, emitted.size(), "first update emits one chunk");

    // Second update: same value as previous → empty diff → no chunk
    session.updateCustom(
        s -> {
          Map<String, Object> next = new HashMap<>(s);
          next.put("counter", 10); // same value
          return next;
        });
    assertEquals(1, emitted.size(), "no-op update must not emit a chunk");
  }
}
