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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.Registry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for the {@link AgentChat} programmatic client (Tasks 5.1 + 5.2). */
class AgentChatTest {

  private Registry registry;
  private ActionContext ctx;

  @BeforeEach
  void setUp() {
    registry = new DefaultRegistry();
    ctx = new ActionContext(registry);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** An AgentFn that echoes the latest user text back as an assistant message. */
  private static AgentFn<Map<String, Object>> echoFn() {
    return (sess, fnCtx) -> {
      String userText = latestUserText(sess.getMessages());
      return AgentResult.builder()
          .message(Message.model("echo: " + userText))
          .finishReason(AgentFinishReason.STOP)
          .build();
    };
  }

  /**
   * An AgentFn that maintains a custom turn counter, streams a model chunk, and increments the
   * counter (which produces a {@code customPatch} stream chunk).
   */
  private static AgentFn<Map<String, Object>> countingFn() {
    return (sess, fnCtx) -> {
      String userText = latestUserText(sess.getMessages());
      // Emit a model chunk so sendStream callbacks receive text.
      fnCtx
          .sendChunk()
          .accept(
              AgentStreamChunk.builder()
                  .modelChunk(ModelResponseChunk.text("chunk: " + userText))
                  .build());
      // Mutate custom state -> produces a customPatch chunk.
      sess.updateCustom(
          cur -> {
            Map<String, Object> next = cur != null ? new HashMap<>(cur) : new HashMap<>();
            int count =
                next.get("count") instanceof Number ? ((Number) next.get("count")).intValue() : 0;
            next.put("count", count + 1);
            return next;
          });
      return AgentResult.builder()
          .message(Message.model("echo: " + userText))
          .finishReason(AgentFinishReason.STOP)
          .build();
    };
  }

  private static String latestUserText(List<Message> msgs) {
    for (int i = msgs.size() - 1; i >= 0; i--) {
      if (msgs.get(i).getRole() == Role.USER) {
        return msgs.get(i).getText();
      }
    }
    return "";
  }

  private static Agent<Map<String, Object>> serverAgent(
      Registry registry, String name, AgentFn<Map<String, Object>> fn) {
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder()
            .name(name)
            .store(new InMemorySessionStore<>())
            .build();
    return AgentActions.defineCustomAgent(registry, config, fn);
  }

  private static Agent<Map<String, Object>> clientAgent(
      Registry registry, String name, AgentFn<Map<String, Object>> fn) {
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name(name).build();
    return AgentActions.defineCustomAgent(registry, config, fn);
  }

  // ── server-managed multi-turn ─────────────────────────────────────────────────

  @Test
  void testServerManagedMultiTurn() throws Exception {
    Agent<Map<String, Object>> agent = serverAgent(registry, "svr", echoFn());

    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> r1 = chat.send("hi");
    assertEquals("echo: hi", r1.text());
    assertNotNull(r1.snapshotId());
    assertFalse(r1.snapshotId().isEmpty());
    String snap1 = chat.snapshotId();
    assertNotNull(snap1);

    AgentResponse<Map<String, Object>> r2 = chat.send("again");
    assertEquals("echo: again", r2.text());
    // The second turn must have resumed the first: snapshot advanced.
    assertNotNull(chat.snapshotId());
    assertFalse(chat.snapshotId().equals(snap1), "snapshot should advance across turns");

    // History accumulated on the server: 2 user + 2 model messages.
    SessionSnapshot<Map<String, Object>> snap =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(chat.snapshotId()).build());
    assertNotNull(snap);
    assertEquals(4, snap.getState().getMessages().size());
  }

  // ── client-managed multi-turn (state round-trip) ──────────────────────────────

  @Test
  void testClientManagedMultiTurn() throws Exception {
    Agent<Map<String, Object>> agent = clientAgent(registry, "cli", echoFn());

    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> r1 = chat.send("one");
    assertEquals("echo: one", r1.text());
    // After turn 1: 1 user + 1 model message.
    assertEquals(2, chat.messages().size());

    AgentResponse<Map<String, Object>> r2 = chat.send("two");
    assertEquals("echo: two", r2.text());
    // Turn 2 carried the prior state forward: 2 user + 2 model messages.
    assertEquals(4, chat.messages().size());
    assertNotNull(r2.state());
    assertEquals(4, r2.state().getMessages().size());
  }

  // ── sendStream delivers chunks ────────────────────────────────────────────────

  @Test
  void testSendStreamDeliversChunks() throws Exception {
    Agent<Map<String, Object>> agent = serverAgent(registry, "stream", countingFn());

    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    List<AgentChunk<Map<String, Object>>> chunks = new ArrayList<>();
    AgentResponse<Map<String, Object>> resp = chat.sendStream("hello", chunks::add);

    assertEquals("echo: hello", resp.text());
    // At least the model chunk should have been delivered.
    boolean sawText = chunks.stream().anyMatch(c -> "chunk: hello".equals(c.text()));
    assertTrue(sawText, "expected a model chunk with text");
  }

  // ── custom state via chat.state() and chunk.custom() ──────────────────────────

  @Test
  void testCustomStateAndChunkCustom() throws Exception {
    Agent<Map<String, Object>> agent = serverAgent(registry, "custom", countingFn());

    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    List<AgentChunk<Map<String, Object>>> chunks = new ArrayList<>();
    chat.sendStream("first", chunks::add);

    // chat.state() reflects custom state after the turn.
    assertNotNull(chat.state());
    assertEquals(1, ((Number) chat.state().get("count")).intValue());

    // A customPatch chunk should have made chunk.custom() reflect the post-patch state.
    AgentChunk<Map<String, Object>> patchChunk =
        chunks.stream().filter(c -> c.custom() != null).reduce((a, b) -> b).orElse(null);
    assertNotNull(patchChunk, "expected a chunk carrying custom state");
    assertEquals(1, ((Number) patchChunk.custom().get("count")).intValue());

    // Second turn increments again.
    chat.send("second");
    assertEquals(2, ((Number) chat.state().get("count")).intValue());
  }

  // ── client-managed custom state round-trip ────────────────────────────────────

  @Test
  void testClientManagedCustomStateRoundTrip() throws Exception {
    Agent<Map<String, Object>> agent = clientAgent(registry, "cliCustom", countingFn());

    AgentChat<Map<String, Object>> chat = agent.chat(ctx);
    chat.send("a");
    assertEquals(1, ((Number) chat.state().get("count")).intValue());
    chat.send("b");
    assertEquals(2, ((Number) chat.state().get("count")).intValue());
  }

  // ── loadChat resumes a snapshot ───────────────────────────────────────────────

  @Test
  void testLoadChatResumesSnapshot() throws Exception {
    Agent<Map<String, Object>> agent = serverAgent(registry, "load", echoFn());

    AgentChat<Map<String, Object>> chat = agent.chat(ctx);
    chat.send("hi");
    String snapshotId = chat.snapshotId();
    String sessionId = chat.sessionId();
    assertNotNull(snapshotId);

    // Hydrate a fresh chat from the snapshot.
    AgentChat<Map<String, Object>> loaded =
        agent.loadChat(ctx, GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertEquals(snapshotId, loaded.snapshotId());
    assertEquals(sessionId, loaded.sessionId());

    // Its next send resumes that snapshot: history grows to 4 messages.
    loaded.send("again");
    SessionSnapshot<Map<String, Object>> snap =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(loaded.snapshotId()).build());
    assertEquals(4, snap.getState().getMessages().size());
  }

  // ── behavior 1: server-managed response.state() is null, snapshotId present ───

  @Test
  void testServerManagedResponseStateIsNull() throws Exception {
    Agent<Map<String, Object>> agent = serverAgent(registry, "svrState", echoFn());
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> resp = chat.send("hi");

    assertNull(resp.state(), "server-managed agents must not echo full state inline");
    assertNotNull(resp.snapshotId());
    assertFalse(resp.snapshotId().isEmpty());
  }

  // ── behavior 2: client-managed response.state() is non-null, no snapshotId ───

  @Test
  void testClientManagedResponseStateIsNonNull() throws Exception {
    Agent<Map<String, Object>> agent = clientAgent(registry, "cliState", echoFn());
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> resp = chat.send("hi");

    assertNotNull(resp.state(), "client-managed agents must echo full inline state");
    assertTrue(resp.snapshotId() == null || resp.snapshotId().isEmpty());
  }

  // ── behavior 3: interrupted turn surfaces a non-empty interrupts() list ──────

  @Test
  void testInterruptedResponseHasInterruptsList() throws Exception {
    AgentFn<Map<String, Object>> interruptingFn =
        (sess, fnCtx) -> {
          Part toolRequestPart = new Part();
          ToolRequest tr = new ToolRequest();
          tr.setName("confirmAction");
          tr.setRef("ref-1");
          toolRequestPart.setToolRequest(tr);
          Message msg = new Message(Role.MODEL, List.of(toolRequestPart));
          return AgentResult.builder()
              .message(msg)
              .finishReason(AgentFinishReason.INTERRUPTED)
              .build();
        };
    Agent<Map<String, Object>> agent = serverAgent(registry, "interrupt", interruptingFn);
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> resp = chat.send("please confirm");

    assertEquals(AgentFinishReason.INTERRUPTED, resp.finishReason());
    assertFalse(resp.interrupts().isEmpty(), "expected a non-empty interrupts() list");
    assertEquals("confirmAction", resp.interrupts().get(0).name());
  }

  // ── behavior 4: resume() continues and yields a non-interrupted response ────

  @Test
  void testResumeAfterInterrupt() throws Exception {
    // First invocation (session has only the 1 user message so far) interrupts; every
    // subsequent invocation (history already carries that message plus the interrupted
    // assistant reply) stops normally. turnIndex() cannot be used here because each top-level
    // send()/resume() call resolves a fresh SessionRunner (turnIndex always starts at 0).
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          if (sess.getMessages().size() <= 1) {
            Part toolRequestPart = new Part();
            ToolRequest tr = new ToolRequest();
            tr.setName("confirmAction");
            toolRequestPart.setToolRequest(tr);
            Message msg = new Message(Role.MODEL, List.of(toolRequestPart));
            return AgentResult.builder()
                .message(msg)
                .finishReason(AgentFinishReason.INTERRUPTED)
                .build();
          }
          return AgentResult.builder()
              .message(Message.model("resumed-ok"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };
    Agent<Map<String, Object>> agent = serverAgent(registry, "resumeFlow", fn);
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> interrupted = chat.send("please confirm");
    assertEquals(AgentFinishReason.INTERRUPTED, interrupted.finishReason());
    assertFalse(interrupted.interrupts().isEmpty());

    Part respondPart = new Part();
    respondPart.setText("confirmed");
    AgentResponse<Map<String, Object>> resumed = chat.resume(List.of(respondPart));

    assertNotEquals(AgentFinishReason.INTERRUPTED, resumed.finishReason());
    assertEquals(AgentFinishReason.STOP, resumed.finishReason());
    assertEquals("resumed-ok", resumed.text());
  }

  // ── behavior 5: sendStream delivers a chunk carrying turnEnd ──────────────────

  @Test
  void testSendStreamDeliversTurnEndChunk() throws Exception {
    Agent<Map<String, Object>> agent = serverAgent(registry, "turnEnd", echoFn());
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    List<AgentChunk<Map<String, Object>>> chunks = new ArrayList<>();
    chat.sendStream("hi", chunks::add);

    boolean sawTurnEnd =
        chunks.stream().anyMatch(c -> c.raw() != null && c.raw().getTurnEnd() != null);
    assertTrue(sawTurnEnd, "expected at least one chunk carrying a non-null turnEnd");
  }

  // ── behavior 6: sendStream delivers a customPatch-derived chunk.custom() ─────

  @Test
  void testSendStreamDeliverCustomChunk() throws Exception {
    Agent<Map<String, Object>> agent = serverAgent(registry, "customChunk", countingFn());
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    List<AgentChunk<Map<String, Object>>> chunks = new ArrayList<>();
    chat.sendStream("hi", chunks::add);

    AgentChunk<Map<String, Object>> withCustom =
        chunks.stream().filter(c -> c.custom() != null).findFirst().orElse(null);
    assertNotNull(withCustom, "expected a chunk with non-null custom() derived from a customPatch");
    assertEquals(1, ((Number) withCustom.custom().get("count")).intValue());
  }

  // ── behavior 7: ctx.sendChunk(modelChunk) is delivered to sendStream callback ─

  @Test
  void testCustomAgentSendChunkIsDeliveredToSendStream() throws Exception {
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          fnCtx
              .sendChunk()
              .accept(
                  AgentStreamChunk.builder()
                      .modelChunk(ModelResponseChunk.text("hello-from-agentfn"))
                      .build());
          return AgentResult.builder()
              .message(Message.model("done"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };
    Agent<Map<String, Object>> agent = serverAgent(registry, "sendChunk", fn);
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    List<AgentChunk<Map<String, Object>>> chunks = new ArrayList<>();
    chat.sendStream("hi", chunks::add);

    boolean sawChunk = chunks.stream().anyMatch(c -> "hello-from-agentfn".equals(c.text()));
    assertTrue(sawChunk, "expected the model chunk sent via ctx.sendChunk() to reach the callback");
  }

  // ── behavior 8: artifacts added by the AgentFn appear in the response ────────

  @Test
  void testArtifactsPresentInResponse() throws Exception {
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          sess.addArtifacts(Artifact.builder().name("report").parts(List.of()).build());
          return AgentResult.builder()
              .message(Message.model("done"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };
    Agent<Map<String, Object>> agent = serverAgent(registry, "artifacts", fn);
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> resp = chat.send("hi");

    assertFalse(
        resp.artifacts().isEmpty(), "expected the added artifact to be present on the response");
    assertEquals("report", resp.artifacts().get(0).getName());
  }

  // ── behavior 9: clientTransform redacts custom state returned to the caller ──

  @Test
  void testClientTransformRedactsState() throws Exception {
    ClientTransform<Map<String, Object>> redact =
        state -> {
          if (state == null) {
            return state;
          }
          Map<String, Object> custom = state.getCustom();
          if (custom != null) {
            Map<String, Object> redacted = new HashMap<>(custom);
            redacted.put("count", 0);
            state.setCustom(redacted);
          }
          return state;
        };
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("redact")
            .clientTransform(redact)
            .build();
    Agent<Map<String, Object>> agent =
        AgentActions.defineCustomAgent(registry, config, countingFn());

    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    AgentResponse<Map<String, Object>> r1 = chat.send("a");
    // Without the clientTransform, countingFn's first turn would set count=1; the transform must
    // rewrite the outgoing state so the caller observes the redacted value (0) instead.
    assertEquals(0, ((Number) r1.state().getCustom().get("count")).intValue());
    assertEquals(0, ((Number) chat.state().get("count")).intValue());

    // Client-managed mode round-trips the transformed state as the next turn's input, so the
    // redaction is applied on every turn the caller observes.
    AgentResponse<Map<String, Object>> r2 = chat.send("b");
    assertEquals(0, ((Number) r2.state().getCustom().get("count")).intValue());
  }

  // ── behavior 11: a thrown AgentFn does not throw from chat.send(); FAILED result ─

  @Test
  void testFailedTurnPopulatesFinishReasonFailed() throws Exception {
    AgentFn<Map<String, Object>> throwingFn =
        (sess, fnCtx) -> {
          throw new RuntimeException("boom");
        };
    Agent<Map<String, Object>> agent = serverAgent(registry, "failing", throwingFn);
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    // chat.send() must not throw even though the AgentFn throws.
    AgentResponse<Map<String, Object>> resp = chat.send("hi");

    assertEquals(AgentFinishReason.FAILED, resp.finishReason());
    assertNotNull(resp.raw().getError());
    assertEquals("boom", resp.raw().getError().getMessage());
  }

  // ── behavior 12: sendStream also surfaces a FAILED turnEnd for a thrown AgentFn ─

  @Test
  void testSendStreamDeliversFailedTurnEnd() throws Exception {
    AgentFn<Map<String, Object>> throwingFn =
        (sess, fnCtx) -> {
          throw new RuntimeException("stream-boom");
        };
    Agent<Map<String, Object>> agent = serverAgent(registry, "failingStream", throwingFn);
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    List<AgentChunk<Map<String, Object>>> chunks = new ArrayList<>();
    AgentResponse<Map<String, Object>> resp = chat.sendStream("hi", chunks::add);

    assertEquals(AgentFinishReason.FAILED, resp.finishReason());
    boolean sawFailedTurnEnd =
        chunks.stream()
            .anyMatch(
                c ->
                    c.raw() != null
                        && c.raw().getTurnEnd() != null
                        && c.raw().getTurnEnd().getFinishReason() == AgentFinishReason.FAILED);
    assertTrue(sawFailedTurnEnd, "expected a turnEnd chunk with finishReason FAILED");
  }

  // ── behavior 15: loadChat resumes a detached turn after it completes ─────────

  private static JsonNode initJson(AgentInit<Map<String, Object>> init) {
    return JsonUtils.toJsonNode(init != null ? init : new AgentInit<Map<String, Object>>());
  }

  private static BufferedInputSource<JsonNode> inputSourceWith(AgentInput... inputs) {
    BufferedInputSource<JsonNode> src = new BufferedInputSource<>();
    for (AgentInput in : inputs) {
      src.offer(JsonUtils.toJsonNode(in));
    }
    src.end();
    return src;
  }

  private SessionSnapshot<Map<String, Object>> pollForStatus(
      Agent<Map<String, Object>> agent, String snapshotId, SnapshotStatus until) throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    SessionSnapshot<Map<String, Object>> snap = null;
    while (System.currentTimeMillis() < deadline) {
      snap = agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
      if (snap != null && snap.getStatus() == until) {
        return snap;
      }
      Thread.sleep(20);
    }
    return snap;
  }

  @Test
  void testLoadChatAfterDetachedTurnCompletes() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("detachedLoad").store(store).build();

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AgentFn<Map<String, Object>> gatedFn =
        (sess, fnCtx) -> {
          started.countDown();
          if (!gate.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("gate not released in time");
          }
          String userText = latestUserText(sess.getMessages());
          return AgentResult.builder()
              .message(Message.model("echo: " + userText))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, gatedFn);

    AgentInput detachInput =
        AgentInput.builder().message(Message.user("first")).detach(true).build();
    JsonNode out = agent.runBidiJson(ctx, initJson(null), inputSourceWith(detachInput), c -> {});
    String snapshotId = out.get("snapshotId").asText();
    assertTrue(started.await(5, TimeUnit.SECONDS), "background turn should have started");

    gate.countDown();
    SessionSnapshot<Map<String, Object>> completed =
        pollForStatus(agent, snapshotId, SnapshotStatus.COMPLETED);
    assertNotNull(completed);
    assertEquals(SnapshotStatus.COMPLETED, completed.getStatus());

    // loadChat resumes the detached-then-completed snapshot; a follow-up send extends history.
    AgentChat<Map<String, Object>> loaded =
        agent.loadChat(ctx, GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertEquals(snapshotId, loaded.snapshotId());
    assertEquals(2, loaded.messages().size(), "prior detached turn's messages must be present");

    AgentResponse<Map<String, Object>> resp = loaded.send("second");
    assertEquals("echo: second", resp.text());

    SessionSnapshot<Map<String, Object>> finalSnap =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(loaded.snapshotId()).build());
    assertNotNull(finalSnap);
    assertEquals(4, finalSnap.getState().getMessages().size());
  }
}
