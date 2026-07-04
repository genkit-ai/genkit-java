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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.Registry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Reproduction tests for multi-turn agents (client-managed and server-managed) via the Reflection
 * V2 wire path.
 *
 * <p>Simulates the Dev UI pattern: one {@code runAction} per user message, each carrying {@code
 * init}. For client-managed agents, the prior turn's {@code result.state} is resent as the next
 * turn's {@code init.state}. For server-managed agents, the prior turn's {@code result.snapshotId}
 * is resent as the next turn's {@code init.snapshotId}. Validates that history accumulates across
 * turns in both modes.
 */
class ReflectionServerV2MultiTurnTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Builds a registry with a server-managed echo agent (InMemorySessionStore). */
  private static Registry registryWithServerManagedEchoAgent() {
    Registry registry = new DefaultRegistry();

    AgentActions.defineCustomAgent(
        registry,
        // .store() → server-managed
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("echoStore")
            .store(new com.google.genkit.ai.agent.InMemorySessionStore<>())
            .build(),
        (runner, ctx) -> {
          List<Message> msgs = runner.getMessages();
          int msgCount = msgs.size();
          String userText = "";
          for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (Role.USER.equals(m.getRole())) {
              userText = m.getText() != null ? m.getText() : "";
              break;
            }
          }
          String replyText = "reply to '" + userText + "'; history=" + msgCount;
          return AgentResult.builder()
              .message(Message.model(replyText))
              .finishReason(AgentFinishReason.STOP)
              .build();
        });

    return registry;
  }

  /** Builds a registry with a client-managed echo agent (no store). */
  private static Registry registryWithClientManagedEchoAgent() {
    Registry registry = new DefaultRegistry();

    // AgentFn: read the latest user message, reply with count of all messages seen so far
    AgentActions.defineCustomAgent(
        registry,
        // NO .store() → client-managed
        CustomAgentConfig.<Map<String, Object>>builder().name("echo").build(),
        (runner, ctx) -> {
          List<Message> msgs = runner.getMessages();
          int msgCount = msgs.size();
          // Find the latest user message text using Message.getText() convenience method
          String userText = "";
          for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (Role.USER.equals(m.getRole())) {
              userText = m.getText() != null ? m.getText() : "";
              break;
            }
          }
          String replyText = "reply to '" + userText + "'; history=" + msgCount;
          return AgentResult.builder()
              .message(Message.model(replyText))
              .finishReason(AgentFinishReason.STOP)
              .build();
        });

    return registry;
  }

  /** Collects outbound JSON-RPC messages; tracks final result by id. */
  private static final class Collector {
    final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    // Per-id latches; we pre-create for ids "1" and "2"
    final CountDownLatch done1 = new CountDownLatch(1);
    final CountDownLatch done2 = new CountDownLatch(1);

    void accept(String raw) {
      try {
        JsonNode msg = MAPPER.readTree(raw);
        messages.add(msg);
        if (msg.has("result") && msg.has("id")) {
          String id = msg.get("id").asText();
          if ("1".equals(id)) done1.countDown();
          else if ("2".equals(id)) done2.countDown();
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    JsonNode finalResultFor(String id) {
      return messages.stream()
          .filter(m -> m.has("result") && m.has("id") && id.equals(m.get("id").asText()))
          .map(m -> m.get("result"))
          .findFirst()
          .orElse(null);
    }
  }

  // --- JSON-RPC message helpers ---

  private static String runActionMsg(String id, String initJson) {
    return runActionMsg(id, "/agent/echo", initJson);
  }

  private static String runActionMsg(String id, String key, String initJson) {
    return "{"
        + "\"jsonrpc\":\"2.0\","
        + "\"method\":\"runAction\","
        + "\"params\":{"
        + "\"key\":\""
        + key
        + "\","
        + "\"init\":"
        + initJson
        + ","
        + "\"stream\":true,"
        + "\"streamInput\":true"
        + "},"
        + "\"id\":\""
        + id
        + "\""
        + "}";
  }

  private static String inputChunkMsg(String requestId, String messageText) {
    return "{"
        + "\"jsonrpc\":\"2.0\","
        + "\"method\":\"sendInputStreamChunk\","
        + "\"params\":{"
        + "\"requestId\":\""
        + requestId
        + "\","
        + "\"chunk\":{"
        + "\"message\":{"
        + "\"role\":\"user\","
        + "\"content\":[{\"text\":\""
        + messageText
        + "\"}]"
        + "}"
        + "}"
        + "}"
        + "}";
  }

  private static String endInputMsg(String requestId) {
    return "{"
        + "\"jsonrpc\":\"2.0\","
        + "\"method\":\"endInputStream\","
        + "\"params\":{"
        + "\"requestId\":\""
        + requestId
        + "\""
        + "}"
        + "}";
  }

  @Test
  void serverManagedMultiTurnAccumulatesHistory() throws Exception {
    Registry registry = registryWithServerManagedEchoAgent();
    ReflectionServerV2 server = new ReflectionServerV2(registry, "ws://localhost:1", "test");
    Collector collector = new Collector();
    server.setOutboundSinkForTesting(collector::accept);

    // ── Turn 1: send "hi" ─────────────────────────────────────────────────────
    server.handleMessageForTesting(runActionMsg("1", "/agent/echoStore", "{}"));
    server.handleMessageForTesting(inputChunkMsg("1", "hi"));
    server.handleMessageForTesting(endInputMsg("1"));

    assertTrue(collector.done1.await(10, TimeUnit.SECONDS), "Turn 1 result not received in time");

    JsonNode turn1Result = collector.finalResultFor("1");
    assertNotNull(turn1Result, "Turn 1: expected a result");

    String turn1Json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(turn1Result);
    System.out.println("=== SERVER-MANAGED TURN 1 RESULT ===");
    System.out.println(turn1Json);

    // For server-managed: result.result must have snapshotId and NO inline state
    JsonNode turn1Inner = turn1Result.get("result");
    assertNotNull(turn1Inner, "Turn 1: expected result.result");
    JsonNode snapshotIdNode = turn1Inner.get("snapshotId");
    assertNotNull(snapshotIdNode, "Turn 1: expected result.result.snapshotId (server-managed)");
    assertFalse(snapshotIdNode.isNull(), "Turn 1: snapshotId must not be null");
    assertFalse(snapshotIdNode.asText().isEmpty(), "Turn 1: snapshotId must not be blank");
    assertTrue(
        turn1Inner.get("state") == null || turn1Inner.get("state").isNull(),
        "Turn 1: server-managed must NOT return inline state");

    String capturedSnapshotId = snapshotIdNode.asText();
    System.out.println("Turn 1 snapshotId: " + capturedSnapshotId);

    // ── Turn 2: resume with snapshotId from turn 1 ───────────────────────────
    String initJson2 = "{\"snapshotId\":\"" + capturedSnapshotId + "\"}";

    server.handleMessageForTesting(runActionMsg("2", "/agent/echoStore", initJson2));
    server.handleMessageForTesting(inputChunkMsg("2", "again"));
    server.handleMessageForTesting(endInputMsg("2"));

    assertTrue(collector.done2.await(10, TimeUnit.SECONDS), "Turn 2 result not received in time");

    JsonNode turn2Result = collector.finalResultFor("2");
    assertNotNull(turn2Result, "Turn 2: expected a result");

    String turn2Json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(turn2Result);
    System.out.println("=== SERVER-MANAGED TURN 2 RESULT ===");
    System.out.println(turn2Json);

    JsonNode turn2Inner = turn2Result.get("result");
    assertNotNull(turn2Inner, "Turn 2: expected result.result");
    // Turn 2 should also yield a snapshotId (session continues)
    JsonNode snap2Node = turn2Inner.get("snapshotId");
    assertNotNull(snap2Node, "Turn 2: expected result.result.snapshotId");
    assertFalse(snap2Node.isNull(), "Turn 2: snapshotId must not be null");

    // CORE ASSERTION: the reply text must reflect accumulated history (≥4 messages across 2 turns)
    // The AgentFn echoes "history=<msgCount>" where msgCount includes prior messages loaded from
    // the store. Turn 2 sees at least 2 msgs from turn 1 + the new user msg = ≥3 total, so
    // history= value must be > 1 (which turn 1 would have seen as 1 user msg → history=1).
    JsonNode turn2Message = turn2Inner.get("message");
    assertNotNull(turn2Message, "Turn 2: expected a message in result");
    String turn2Text =
        turn2Message.get("content") != null
            ? turn2Message.get("content").get(0).get("text").asText()
            : "";
    System.out.println("Turn 2 reply text: " + turn2Text);

    // Extract the history count from the reply: "reply to 'again'; history=N"
    int historyCount = 0;
    if (turn2Text.contains("history=")) {
      historyCount = Integer.parseInt(turn2Text.substring(turn2Text.indexOf("history=") + 8));
    }
    System.out.println("Turn 2 history count seen by AgentFn: " + historyCount);
    assertTrue(
        historyCount >= 3,
        "BUG: Turn 2 AgentFn saw history="
            + historyCount
            + " but expected ≥3 (2 msgs from turn 1 + new user msg). "
            + "Server-managed history was NOT loaded from the store.");
  }

  @Test
  void clientManagedMultiTurnAccumulatesHistory() throws Exception {
    Registry registry = registryWithClientManagedEchoAgent();
    ReflectionServerV2 server = new ReflectionServerV2(registry, "ws://localhost:1", "test");
    Collector collector = new Collector();
    server.setOutboundSinkForTesting(collector::accept);

    // ── Turn 1: send "hi" ─────────────────────────────────────────────────────
    server.handleMessageForTesting(runActionMsg("1", "{}"));
    server.handleMessageForTesting(inputChunkMsg("1", "hi"));
    server.handleMessageForTesting(endInputMsg("1"));

    assertTrue(collector.done1.await(10, TimeUnit.SECONDS), "Turn 1 result not received in time");

    JsonNode turn1Result = collector.finalResultFor("1");
    assertNotNull(turn1Result, "Turn 1: expected a result");

    // Print turn 1 result for evidence
    String turn1Json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(turn1Result);
    System.out.println("=== TURN 1 RESULT ===");
    System.out.println(turn1Json);

    // For client-managed: result.result.state must be present with messages
    JsonNode turn1Inner = turn1Result.get("result");
    assertNotNull(turn1Inner, "Turn 1: expected result.result");
    JsonNode turn1State = turn1Inner.get("state");
    assertNotNull(
        turn1State,
        "Turn 1: expected result.result.state (client-managed must return inline state)");
    JsonNode turn1Messages = turn1State.get("messages");
    assertNotNull(turn1Messages, "Turn 1: expected state.messages");
    System.out.println("Turn 1 message count in state: " + turn1Messages.size());

    // ── Turn 2: send "again" with turn-1 state resent as init.state ──────────
    // Serialize the captured state to embed into the next runAction init
    String stateJson = MAPPER.writeValueAsString(turn1State);
    String initJson = "{\"state\":" + stateJson + "}";

    server.handleMessageForTesting(runActionMsg("2", initJson));
    server.handleMessageForTesting(inputChunkMsg("2", "again"));
    server.handleMessageForTesting(endInputMsg("2"));

    assertTrue(collector.done2.await(10, TimeUnit.SECONDS), "Turn 2 result not received in time");

    JsonNode turn2Result = collector.finalResultFor("2");
    assertNotNull(turn2Result, "Turn 2: expected a result");

    // Print turn 2 result for evidence
    String turn2Json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(turn2Result);
    System.out.println("=== TURN 2 RESULT ===");
    System.out.println(turn2Json);

    JsonNode turn2Inner = turn2Result.get("result");
    assertNotNull(turn2Inner, "Turn 2: expected result.result");
    JsonNode turn2State = turn2Inner.get("state");
    assertNotNull(turn2State, "Turn 2: expected result.result.state");
    JsonNode turn2Messages = turn2State.get("messages");
    assertNotNull(turn2Messages, "Turn 2: expected state.messages");
    System.out.println("Turn 2 message count in state: " + turn2Messages.size());

    // CORE ASSERTION: history must have grown between turn 1 and turn 2
    int turn1Count = turn1Messages.size();
    int turn2Count = turn2Messages.size();
    System.out.println(
        "History: turn1=" + turn1Count + " messages, turn2=" + turn2Count + " messages");
    assertTrue(
        turn2Count > turn1Count,
        "BUG REPRODUCED: Turn 2 state.messages ("
            + turn2Count
            + ") did NOT grow beyond turn 1 ("
            + turn1Count
            + ") — history was NOT accumulated across turns. "
            + "This means init.state was not hydrated correctly for turn 2.");
  }
}
