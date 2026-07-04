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

package com.google.genkit.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for BidiActionImpl. */
class BidiActionImplTest {

  private Registry registry;
  private ActionContext ctx;
  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  @BeforeEach
  void setUp() {
    registry = new DefaultRegistry();
    ctx = new ActionContext(registry);
  }

  // -------------------------------------------------------------------------
  // Multi-input bidi test: feed 3 inputs from a separate thread, assert
  // runBidi returns 3 and 3 chunks were observed.
  // -------------------------------------------------------------------------
  @Test
  void testMultiInputBidi() throws Exception {
    // Handler: drain inputs, count them, emit one chunk per input, return count
    BidiActionImpl<Integer, Integer, Integer, Void> action =
        BidiActionImpl.<Integer, Integer, Integer, Void>builder()
            .name("countInputs")
            .inputClass(Integer.class)
            .outputClass(Integer.class)
            .streamClass(Integer.class)
            .initClass(Void.class)
            .handler(
                (handlerCtx, init, inputs, cb) -> {
                  int count = 0;
                  Optional<Integer> next;
                  while ((next = inputs.next()).isPresent()) {
                    count++;
                    cb.accept(next.get());
                  }
                  return count;
                })
            .build();

    List<Integer> chunks = new ArrayList<>();
    BufferedInputSource<Integer> source = new BufferedInputSource<>();

    // Feed inputs from a separate thread
    ExecutorService exec = Executors.newSingleThreadExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    exec.submit(
        () -> {
          try {
            latch.await(); // Wait until runBidi starts
            source.offer(10);
            source.offer(20);
            source.offer(30);
            source.end();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    // Use a latch to signal producer after runBidi is called
    latch.countDown();

    Integer result = action.runBidi(ctx, null, source, chunks::add);

    exec.shutdown();
    assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

    assertEquals(3, result);
    assertEquals(3, chunks.size());
    assertEquals(List.of(10, 20, 30), chunks);
  }

  // -------------------------------------------------------------------------
  // Type + metadata assertions
  // -------------------------------------------------------------------------
  @Test
  void testTypeAndMetadata() {
    BidiActionImpl<Integer, Integer, Integer, Void> action =
        BidiActionImpl.<Integer, Integer, Integer, Void>builder()
            .name("myAgent")
            .inputClass(Integer.class)
            .outputClass(Integer.class)
            .streamClass(Integer.class)
            .initClass(Void.class)
            .metadata(Map.of("custom", "value"))
            .handler((handlerCtx, init, inputs, cb) -> 0)
            .build();

    assertEquals(ActionType.AGENT, action.getType());
    assertEquals(Boolean.TRUE, action.getMetadata().get("bidi"));
    // custom metadata must also be present
    assertEquals("value", action.getMetadata().get("custom"));
    // desc key must be /agent/<name>
    assertEquals("/agent/myAgent", action.getDesc().getKey());
  }

  // -------------------------------------------------------------------------
  // Caller's metadata map must NOT be mutated
  // -------------------------------------------------------------------------
  @Test
  void testMetadataNotMutated() {
    Map<String, Object> callerMeta = new java.util.HashMap<>();
    callerMeta.put("x", "y");

    BidiActionImpl<Integer, Integer, Integer, Void> action =
        BidiActionImpl.<Integer, Integer, Integer, Void>builder()
            .name("myAgent2")
            .inputClass(Integer.class)
            .outputClass(Integer.class)
            .streamClass(Integer.class)
            .initClass(Void.class)
            .metadata(callerMeta)
            .handler((handlerCtx, init, inputs, cb) -> 0)
            .build();

    // Original map must not contain "bidi"
    assertFalse(callerMeta.containsKey("bidi"));
    // Action's metadata must contain "bidi"
    assertEquals(Boolean.TRUE, action.getMetadata().get("bidi"));
  }

  // -------------------------------------------------------------------------
  // Unary adaptation: action.run(ctx, singleInput, chunkCollector) must work
  // -------------------------------------------------------------------------
  @Test
  void testUnaryAdaptation() throws Exception {
    BidiActionImpl<Integer, Integer, Integer, Void> action =
        BidiActionImpl.<Integer, Integer, Integer, Void>builder()
            .name("unaryAgent")
            .inputClass(Integer.class)
            .outputClass(Integer.class)
            .streamClass(Integer.class)
            .initClass(Void.class)
            .handler(
                (handlerCtx, init, inputs, cb) -> {
                  int count = 0;
                  Optional<Integer> next;
                  while ((next = inputs.next()).isPresent()) {
                    count++;
                    cb.accept(next.get());
                  }
                  return count;
                })
            .build();

    List<Integer> chunks = new ArrayList<>();
    Integer result = action.run(ctx, 42, chunks::add);

    // Should have seen exactly 1 input → count = 1
    assertEquals(1, result);
    assertEquals(1, chunks.size());
    assertEquals(42, chunks.get(0));
  }

  // -------------------------------------------------------------------------
  // Unary adaptation with null input
  // -------------------------------------------------------------------------
  @Test
  void testUnaryAdaptationNullInput() throws Exception {
    BidiActionImpl<Integer, Integer, Integer, Void> action =
        BidiActionImpl.<Integer, Integer, Integer, Void>builder()
            .name("unaryNullAgent")
            .inputClass(Integer.class)
            .outputClass(Integer.class)
            .streamClass(Integer.class)
            .initClass(Void.class)
            .handler(
                (handlerCtx, init, inputs, cb) -> {
                  int count = 0;
                  Optional<Integer> next;
                  while ((next = inputs.next()).isPresent()) {
                    count++;
                  }
                  return count;
                })
            .build();

    // null input → 0 inputs seen
    Integer result = action.run(ctx, null, null);
    assertEquals(0, result);
  }

  // -------------------------------------------------------------------------
  // runBidiJson round-trip: feed JsonNode inputs, assert JSON output
  // -------------------------------------------------------------------------
  @Test
  void testRunBidiJsonRoundTrip() throws Exception {
    BidiActionImpl<Integer, Integer, Integer, Void> action =
        BidiActionImpl.<Integer, Integer, Integer, Void>builder()
            .name("jsonAgent")
            .inputClass(Integer.class)
            .outputClass(Integer.class)
            .streamClass(Integer.class)
            .initClass(Void.class)
            .handler(
                (handlerCtx, init, inputs, cb) -> {
                  int count = 0;
                  Optional<Integer> next;
                  while ((next = inputs.next()).isPresent()) {
                    count++;
                    cb.accept(next.get() * 2); // emit doubled value as chunk
                  }
                  return count;
                })
            .build();

    BufferedInputSource<JsonNode> jsonSource = new BufferedInputSource<>();
    jsonSource.offer(MAPPER.valueToTree(5));
    jsonSource.offer(MAPPER.valueToTree(10));
    jsonSource.end();

    List<JsonNode> jsonChunks = new ArrayList<>();
    JsonNode jsonResult = action.runBidiJson(ctx, null, jsonSource, jsonChunks::add);

    // 2 inputs → count = 2
    assertNotNull(jsonResult);
    assertEquals(2, jsonResult.asInt());
    assertEquals(2, jsonChunks.size());
    assertEquals(10, jsonChunks.get(0).asInt()); // 5 * 2
    assertEquals(20, jsonChunks.get(1).asInt()); // 10 * 2
  }

  // -------------------------------------------------------------------------
  // BufferedInputSource: offer/end/next semantics
  // -------------------------------------------------------------------------
  @Test
  void testBufferedInputSourceBasic() throws InterruptedException {
    BufferedInputSource<String> source = new BufferedInputSource<>();
    source.offer("hello");
    source.offer("world");
    source.end();

    assertEquals(Optional.of("hello"), source.next());
    assertEquals(Optional.of("world"), source.next());
    assertEquals(Optional.empty(), source.next());
    // After end, subsequent calls must also return empty
    assertEquals(Optional.empty(), source.next());
  }

  // -------------------------------------------------------------------------
  // Registration: register(registry) must register under /agent/<name>
  // -------------------------------------------------------------------------
  @Test
  void testRegistration() {
    BidiActionImpl<Integer, Integer, Integer, Void> action =
        BidiActionImpl.<Integer, Integer, Integer, Void>builder()
            .name("registeredAgent")
            .inputClass(Integer.class)
            .outputClass(Integer.class)
            .streamClass(Integer.class)
            .initClass(Void.class)
            .handler((handlerCtx, init, inputs, cb) -> 0)
            .build();

    action.register(registry);

    Action<?, ?, ?> found = registry.lookupAction("/agent/registeredAgent");
    assertNotNull(found);
    assertSame(action, found);
  }

  // -------------------------------------------------------------------------
  // runBidiJsonWithTelemetry must thread the REAL init to the handler — the V1
  // reflection server relies on this for agent multi-turn (the Dev UI sends the
  // prior turn's session state/snapshotId in init each turn). The inherited
  // unary runJsonWithTelemetry adaptation passes a null init, which is exactly
  // why agents could not be resumed over V1 before the fix.
  // -------------------------------------------------------------------------
  @Test
  void testRunBidiJsonWithTelemetryThreadsInit() throws Exception {
    // Handler echoes back the init it received: {"initSeen": <init or null>}
    BidiActionImpl<JsonNode, JsonNode, JsonNode, JsonNode> action =
        BidiActionImpl.<JsonNode, JsonNode, JsonNode, JsonNode>builder()
            .name("echoInit")
            .inputClass(JsonNode.class)
            .outputClass(JsonNode.class)
            .streamClass(JsonNode.class)
            .initClass(JsonNode.class)
            .handler(
                (handlerCtx, init, inputs, cb) -> {
                  inputs.next(); // drain the single input
                  var out = MAPPER.createObjectNode();
                  out.set("initSeen", init == null ? MAPPER.nullNode() : init);
                  return out;
                })
            .build();

    JsonNode initJson = MAPPER.readTree("{\"state\":{\"messages\":[{\"role\":\"user\"}]}}");

    // Bidi-with-telemetry: init MUST reach the handler.
    BufferedInputSource<JsonNode> inputs = new BufferedInputSource<>();
    inputs.offer(MAPPER.readTree("{\"message\":{\"role\":\"user\"}}"));
    inputs.end();
    ActionRunResult<JsonNode> bidiResult =
        action.runBidiJsonWithTelemetry(ctx, initJson, inputs, null);
    assertEquals(initJson, bidiResult.getResult().get("initSeen"));

    // Unary path drops init (handler sees null) — documents why V1 must not use it for agents.
    JsonNode unary =
        action
            .runJsonWithTelemetry(ctx, MAPPER.readTree("{\"message\":{\"role\":\"user\"}}"), null)
            .getResult();
    assertTrue(unary.get("initSeen").isNull());
  }
}
