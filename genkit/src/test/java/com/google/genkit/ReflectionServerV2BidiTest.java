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

package com.google.genkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.core.BidiActionImpl;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.Registry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests the Reflection V2 bidirectional agent path: runAction with {@code streamInput}, input
 * chunks via {@code sendInputStreamChunk}, end-of-input via {@code endInputStream}, and the
 * resulting {@code streamChunk}/{@code result} notifications — i.e. how the Dev UI drives an agent
 * chat turn.
 */
class ReflectionServerV2BidiTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Registers a bidi "echo" agent that counts inputs and echoes each one as a stream chunk. */
  private static Registry registryWithEchoAgent() {
    Registry registry = new DefaultRegistry();
    BidiActionImpl<JsonNode, JsonNode, JsonNode, JsonNode> echo =
        BidiActionImpl.<JsonNode, JsonNode, JsonNode, JsonNode>builder()
            .name("echo")
            .inputClass(JsonNode.class)
            .outputClass(JsonNode.class)
            .streamClass(JsonNode.class)
            .initClass(JsonNode.class)
            .handler(
                (ctx, init, inputs, cb) -> {
                  int n = 0;
                  while (true) {
                    var next = inputs.next();
                    if (next.isEmpty()) {
                      break;
                    }
                    n++;
                    if (cb != null) {
                      ObjectNode chunk = MAPPER.createObjectNode();
                      chunk.set("echo", next.get());
                      cb.accept(chunk);
                    }
                  }
                  ObjectNode out = MAPPER.createObjectNode();
                  out.put("count", n);
                  return out;
                })
            .build();
    echo.register(registry);
    return registry;
  }

  /**
   * Collects outbound JSON-RPC messages; fires {@code done} when the response for {@code id} lands.
   */
  private static final class Collector {
    final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    final CountDownLatch done = new CountDownLatch(1);
    final String awaitId;

    Collector(String awaitId) {
      this.awaitId = awaitId;
    }

    void accept(String raw) {
      try {
        JsonNode msg = MAPPER.readTree(raw);
        messages.add(msg);
        if (msg.has("result") && msg.has("id") && awaitId.equals(msg.get("id").asText())) {
          done.countDown();
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    long countNotifications(String method, String requestId) {
      return messages.stream()
          .filter(m -> m.has("method") && method.equals(m.get("method").asText()))
          .filter(
              m ->
                  m.has("params")
                      && m.get("params").has("requestId")
                      && requestId.equals(m.get("params").get("requestId").asText()))
          .count();
    }

    JsonNode finalResultFor(String id) {
      return messages.stream()
          .filter(m -> m.has("result") && m.has("id") && id.equals(m.get("id").asText()))
          .map(m -> m.get("result"))
          .findFirst()
          .orElse(null);
    }
  }

  private static String runActionMsg(String id) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"runAction\",\"params\":{\"key\":\"/agent/echo\","
        + "\"init\":{},\"stream\":true,\"streamInput\":true},\"id\":\""
        + id
        + "\"}";
  }

  private static String inputChunkMsg(String requestId, String text) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"sendInputStreamChunk\",\"params\":{\"requestId\":\""
        + requestId
        + "\",\"chunk\":{\"message\":\""
        + text
        + "\"}}}";
  }

  private static String endInputMsg(String requestId) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"endInputStream\",\"params\":{\"requestId\":\""
        + requestId
        + "\"}}";
  }

  @Test
  void bidiTurnStreamsInputsAndReturnsResult() throws Exception {
    ReflectionServerV2 server =
        new ReflectionServerV2(registryWithEchoAgent(), "ws://localhost:1", "test");
    Collector collector = new Collector("b1");
    server.setOutboundSinkForTesting(collector::accept);

    server.handleMessageForTesting(runActionMsg("b1"));
    server.handleMessageForTesting(inputChunkMsg("b1", "a"));
    server.handleMessageForTesting(inputChunkMsg("b1", "b"));
    server.handleMessageForTesting(endInputMsg("b1"));

    assertTrue(collector.done.await(5, TimeUnit.SECONDS), "final result not received in time");

    // runActionState (with traceId) was emitted.
    boolean sawState =
        collector.messages.stream()
            .anyMatch(m -> m.has("method") && "runActionState".equals(m.get("method").asText()));
    assertTrue(sawState, "expected a runActionState notification");

    // Two input chunks → two streamChunk notifications.
    assertEquals(2, collector.countNotifications("streamChunk", "b1"));

    // Final result reflects 2 inputs counted by the bidi handler.
    JsonNode result = collector.finalResultFor("b1");
    assertNotNull(result, "expected a final result for b1");
    assertEquals(2, result.get("result").get("count").asInt());
  }

  @Test
  void earlyInputChunksAreBufferedBeforeRunAction() throws Exception {
    ReflectionServerV2 server =
        new ReflectionServerV2(registryWithEchoAgent(), "ws://localhost:1", "test");
    Collector collector = new Collector("b2");
    server.setOutboundSinkForTesting(collector::accept);

    // Inputs arrive BEFORE the runAction handler is dispatched — must be buffered, not dropped.
    server.handleMessageForTesting(inputChunkMsg("b2", "x"));
    server.handleMessageForTesting(inputChunkMsg("b2", "y"));
    server.handleMessageForTesting(inputChunkMsg("b2", "z"));
    server.handleMessageForTesting(endInputMsg("b2"));
    server.handleMessageForTesting(runActionMsg("b2").replace("\"b1\"", "\"b2\""));

    assertTrue(collector.done.await(5, TimeUnit.SECONDS), "final result not received in time");
    JsonNode result = collector.finalResultFor("b2");
    assertNotNull(result, "expected a final result for b2");
    assertEquals(3, result.get("result").get("count").asInt());
  }

  @Test
  void listActionsExposesBidiMetadata() {
    ReflectionServerV2 server =
        new ReflectionServerV2(registryWithEchoAgent(), "ws://localhost:1", "test");
    AtomicReference<JsonNode> response = new AtomicReference<>();
    server.setOutboundSinkForTesting(
        raw -> {
          try {
            response.set(MAPPER.readTree(raw));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    server.handleMessageForTesting(
        "{\"jsonrpc\":\"2.0\",\"method\":\"listActions\",\"id\":\"l1\"}");

    JsonNode msg = response.get();
    assertNotNull(msg, "expected a listActions response");
    JsonNode actions = msg.get("result").get("actions");
    JsonNode echo = actions.get("/agent/echo");
    assertNotNull(echo, "expected /agent/echo in listActions");
    assertTrue(echo.get("metadata").get("bidi").asBoolean(), "expected metadata.bidi == true");
  }
}
