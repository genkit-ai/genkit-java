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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.ai.agent.internal.DetachController;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.Registry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for detach + heartbeat + background-finalize (Task 4.5b). */
class DetachTest {

  private Registry registry;
  private ActionContext ctx;
  private long prevHeartbeat;

  @BeforeEach
  void setUp() {
    registry = new DefaultRegistry();
    ctx = new ActionContext(registry);
    // Make heartbeat fire fast so the heartbeat test is deterministic.
    prevHeartbeat = DetachController.setHeartbeatIntervalMillisForTest(50L);
  }

  @AfterEach
  void tearDown() {
    DetachController.setHeartbeatIntervalMillisForTest(prevHeartbeat);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /**
   * An AgentFn that blocks on {@code gate} before echoing, and counts down {@code started} once it
   * begins. Lets a test observe the PENDING phase, then release the turn to observe COMPLETED.
   */
  private static AgentFn<Map<String, Object>> gatedEchoFn(
      CountDownLatch started, CountDownLatch gate) {
    return (sess, fnCtx) -> {
      started.countDown();
      if (!gate.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("gate not released in time");
      }
      List<Message> msgs = sess.getMessages();
      String userText = "";
      for (int i = msgs.size() - 1; i >= 0; i--) {
        if (msgs.get(i).getRole() == Role.USER) {
          userText = msgs.get(i).getText();
          break;
        }
      }
      return AgentResult.builder()
          .message(Message.model("echo: " + userText))
          .finishReason(AgentFinishReason.STOP)
          .build();
    };
  }

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

  private static AgentInput detachInput(String text) {
    return AgentInput.builder().message(Message.user(text)).detach(true).build();
  }

  private SessionSnapshot<Map<String, Object>> poll(
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

  // ── detach returns DETACHED + pending snapshot, then finalizes to COMPLETED ───

  @Test
  void testDetachReturnsDetachedThenFinalizes() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("dtc").store(store).build();

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    Agent<Map<String, Object>> agent =
        AgentActions.defineCustomAgent(registry, config, gatedEchoFn(started, gate));

    List<JsonNode> chunks = new ArrayList<>();
    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(detachInput("hi")), chunks::add);

    // Immediate return: finishReason DETACHED + snapshotId, no inline state.
    assertNotNull(out);
    assertEquals("detached", out.get("finishReason").asText());
    String snapshotId = out.get("snapshotId").asText();
    assertNotNull(snapshotId);
    assertFalse(snapshotId.isEmpty());

    // The background turn has started but is gated → snapshot is PENDING.
    assertTrue(started.await(5, TimeUnit.SECONDS), "background turn should have started");
    SessionSnapshot<Map<String, Object>> pending =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertNotNull(pending);
    assertEquals(SnapshotStatus.PENDING, pending.getStatus());

    // No stream chunks for a detached run (streaming suppressed).
    assertTrue(chunks.isEmpty(), "detached run must not emit stream chunks");

    // Release the gate → background finalizes to COMPLETED with cumulative state.
    gate.countDown();
    SessionSnapshot<Map<String, Object>> done = poll(agent, snapshotId, SnapshotStatus.COMPLETED);
    assertNotNull(done);
    assertEquals(SnapshotStatus.COMPLETED, done.getStatus());
    assertEquals(AgentFinishReason.STOP, done.getFinishReason());
    // Cumulative state: user message + echoed model message.
    assertNotNull(done.getState());
    assertEquals(2, done.getState().getMessages().size());
    assertEquals("echo: hi", done.getState().getMessages().get(1).getContent().get(0).getText());
    // Heartbeat cleared on terminal finalize.
    assertTrue(
        done.getHeartbeatAt() == null || done.getHeartbeatAt().isEmpty(),
        "heartbeatAt should be cleared on finalize");
  }

  // ── heartbeat refreshes heartbeatAt while pending ─────────────────────────────

  @Test
  void testHeartbeatRefreshesWhilePending() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("hb").store(store).build();

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    Agent<Map<String, Object>> agent =
        AgentActions.defineCustomAgent(registry, config, gatedEchoFn(started, gate));

    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(detachInput("hi")), c -> {});
    String snapshotId = out.get("snapshotId").asText();
    assertTrue(started.await(5, TimeUnit.SECONDS));

    // Observe the heartbeat advancing while pending (interval shrunk to 50ms in setUp).
    String first =
        agent
            .getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build())
            .getHeartbeatAt();
    assertNotNull(first);
    String later = first;
    long deadline = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(60);
      later =
          agent
              .getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build())
              .getHeartbeatAt();
      if (later != null && !later.equals(first)) {
        break;
      }
    }
    assertNotNull(later);
    assertTrue(
        Instant.parse(later).compareTo(Instant.parse(first)) >= 0,
        "heartbeatAt should advance while pending");
    assertFalse(later.equals(first), "heartbeatAt should be refreshed by the heartbeat task");

    gate.countDown();
    poll(agent, snapshotId, SnapshotStatus.COMPLETED);
  }

  // ── abort during pending: background finalize must NOT overwrite ABORTED ──────

  @Test
  void testAbortDuringPendingNotOverwritten() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("abt").store(store).build();

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    Agent<Map<String, Object>> agent =
        AgentActions.defineCustomAgent(registry, config, gatedEchoFn(started, gate));

    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(detachInput("hi")), c -> {});
    String snapshotId = out.get("snapshotId").asText();
    assertTrue(started.await(5, TimeUnit.SECONDS));

    // Abort while still pending → ABORTED.
    SnapshotStatus afterAbort = agent.abort(snapshotId);
    assertEquals(SnapshotStatus.ABORTED, afterAbort);

    // Release the gate; the background finalize must NOT clobber the ABORTED row.
    gate.countDown();
    Thread.sleep(500);
    SessionSnapshot<Map<String, Object>> snap =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertNotNull(snap);
    assertEquals(SnapshotStatus.ABORTED, snap.getStatus());
  }

  // ── client-managed + detach: processed normally (graceful no-op for detach) ───

  @Test
  void testClientManagedDetachProcessedNormally() throws Exception {
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("clidtc").build();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(0); // already open: no blocking for client-managed
    Agent<Map<String, Object>> agent =
        AgentActions.defineCustomAgent(registry, config, gatedEchoFn(started, gate));

    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(detachInput("yo")), c -> {});

    // Client-managed: no store → detach N/A; processed normally with inline state, no DETACHED.
    assertNotNull(out.get("state"));
    assertFalse(out.get("state").isNull());
    assertFalse("detached".equals(out.get("finishReason").asText()));
  }

  // ── behavior 20: a detached turn whose AgentFn throws finalizes to FAILED ─────

  @Test
  void testDetachedTurnFailureTransitionsToFailed() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("dtcFail").store(store).build();

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch gate = new CountDownLatch(1);
    AgentFn<Map<String, Object>> gatedThrowingFn =
        (sess, fnCtx) -> {
          started.countDown();
          if (!gate.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("gate not released in time");
          }
          throw new RuntimeException("detached-boom");
        };
    Agent<Map<String, Object>> agent =
        AgentActions.defineCustomAgent(registry, config, gatedThrowingFn);

    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(detachInput("hi")), c -> {});
    assertEquals("detached", out.get("finishReason").asText());
    String snapshotId = out.get("snapshotId").asText();
    assertTrue(started.await(5, TimeUnit.SECONDS), "background turn should have started");

    SessionSnapshot<Map<String, Object>> pending =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertNotNull(pending);
    assertEquals(SnapshotStatus.PENDING, pending.getStatus());

    // Release the gate → the AgentFn throws; the background finalize must transition to FAILED,
    // never to COMPLETED.
    gate.countDown();
    SessionSnapshot<Map<String, Object>> failed = poll(agent, snapshotId, SnapshotStatus.FAILED);
    assertNotNull(failed);
    assertEquals(SnapshotStatus.FAILED, failed.getStatus());
    assertEquals(AgentFinishReason.FAILED, failed.getFinishReason());
    assertNotNull(failed.getError());
    assertEquals("detached-boom", failed.getError().getMessage());
    // Heartbeat cleared on terminal finalize, same as the successful path.
    assertTrue(
        failed.getHeartbeatAt() == null || failed.getHeartbeatAt().isEmpty(),
        "heartbeatAt should be cleared on finalize");
  }
}
