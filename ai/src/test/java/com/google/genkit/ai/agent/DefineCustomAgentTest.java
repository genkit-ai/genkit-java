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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.Registry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for {@code defineCustomAgent} (Task 4.5). */
class DefineCustomAgentTest {

  private Registry registry;
  private ActionContext ctx;

  @BeforeEach
  void setUp() {
    registry = new DefaultRegistry();
    ctx = new ActionContext(registry);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** An AgentFn that echoes the user's text back as an assistant message. */
  private static AgentFn<Map<String, Object>> echoFn() {
    return (sess, fnCtx) -> {
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

  private static AgentInput userInput(String text) {
    return AgentInput.builder().message(Message.user(text)).build();
  }

  // ── Server-managed single turn ───────────────────────────────────────────────

  @Test
  void testServerManagedSingleTurn() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("svr")
            .description("server agent")
            .store(store)
            .build();

    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    // Registered actions present
    assertNotNull(registry.lookupAction("/agent/svr"));
    assertNotNull(registry.lookupAction("/agent-snapshot/svr"));
    assertNotNull(registry.lookupAction("/agent-abort/svr"));

    // metadata.agent reflects server state-management + abortable
    JsonNode agentMeta = JsonUtils.toJsonNode(agent.getMetadata().get("agent"));
    assertNotNull(agentMeta);
    assertEquals("server", agentMeta.get("stateManagement").asText());
    assertTrue(agentMeta.get("abortable").asBoolean());

    // Drive one turn
    List<JsonNode> chunks = new ArrayList<>();
    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(userInput("hi")), chunks::add);

    assertNotNull(out);
    // server-managed: snapshotId present, no inline state
    String snapshotId = out.get("snapshotId").asText();
    assertNotNull(snapshotId);
    assertFalse(snapshotId.isEmpty());
    assertTrue(out.get("state") == null || out.get("state").isNull());
    // message echoed
    assertEquals("echo: hi", out.get("message").get("content").get(0).get("text").asText());

    // a TurnEnd chunk emitted
    boolean sawTurnEnd = chunks.stream().anyMatch(c -> c.has("turnEnd"));
    assertTrue(sawTurnEnd, "expected a turnEnd chunk");
  }

  // ── Client-managed ───────────────────────────────────────────────────────────

  @Test
  void testClientManaged() throws Exception {
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("cli").build();

    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    // Only the agent action is registered
    assertNotNull(registry.lookupAction("/agent/cli"));
    assertNull(registry.lookupAction("/agent-snapshot/cli"));
    assertNull(registry.lookupAction("/agent-abort/cli"));

    JsonNode agentMeta = JsonUtils.toJsonNode(agent.getMetadata().get("agent"));
    assertEquals("client", agentMeta.get("stateManagement").asText());
    assertFalse(agentMeta.get("abortable").asBoolean());

    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(userInput("yo")), c -> {});

    // client-managed: inline state present, no snapshotId
    assertNotNull(out.get("state"));
    assertFalse(out.get("state").isNull());
    assertTrue(out.get("snapshotId") == null || out.get("snapshotId").isNull());
    // state contains messages (user + model)
    JsonNode msgs = out.get("state").get("messages");
    assertEquals(2, msgs.size());
  }

  // ── Resume by sessionId (server) ─────────────────────────────────────────────

  @Test
  void testResumeBySessionId() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("res").store(store).build();
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    // Turn 1
    JsonNode out1 =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(userInput("one")), c -> {});
    String sessionId = out1.get("sessionId").asText();
    assertNotNull(sessionId);

    // Turn 2: resume by sessionId
    AgentInit<Map<String, Object>> init2 =
        AgentInit.<Map<String, Object>>builder().sessionId(sessionId).build();
    JsonNode out2 =
        agent.runBidiJson(ctx, initJson(init2), inputSourceWith(userInput("two")), c -> {});

    String snapshotId = out2.get("snapshotId").asText();
    GetSnapshotRequest req = GetSnapshotRequest.builder().snapshotId(snapshotId).build();
    SessionSnapshot<Map<String, Object>> snap = agent.getSnapshotData(req);
    assertNotNull(snap);
    // 2 user + 2 model messages accumulated
    assertEquals(4, snap.getState().getMessages().size());
  }

  // ── Multi-turn in one invocation ─────────────────────────────────────────────

  @Test
  void testMultiTurnSingleInvocation() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("multi").store(store).build();
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    List<JsonNode> chunks = new ArrayList<>();
    JsonNode out =
        agent.runBidiJson(
            ctx,
            initJson(null),
            inputSourceWith(userInput("first"), userInput("second")),
            chunks::add);

    long turnEnds = chunks.stream().filter(c -> c.has("turnEnd")).count();
    assertEquals(2, turnEnds, "expected two turnEnd chunks");
    // Final output reflects the 2nd turn
    assertEquals("echo: second", out.get("message").get("content").get(0).get("text").asText());

    // 2 user + 2 model messages persisted
    GetSnapshotRequest req =
        GetSnapshotRequest.builder().sessionId(out.get("sessionId").asText()).build();
    SessionSnapshot<Map<String, Object>> snap = agent.getSnapshotData(req);
    assertEquals(4, snap.getState().getMessages().size());
  }

  // ── getSnapshot companion ────────────────────────────────────────────────────

  @Test
  void testGetSnapshotCompanion() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("gs").store(store).build();
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(userInput("hi")), c -> {});
    String snapshotId = out.get("snapshotId").asText();

    GetSnapshotRequest req = GetSnapshotRequest.builder().snapshotId(snapshotId).build();
    JsonNode snapJson = agent.getSnapshotDataAction().runJson(ctx, JsonUtils.toJsonNode(req), null);
    assertNotNull(snapJson);
    assertEquals(snapshotId, snapJson.get("snapshotId").asText());
    assertEquals("completed", snapJson.get("status").asText());
  }

  // ── abort companion ──────────────────────────────────────────────────────────

  @Test
  void testAbortCompanion() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("ab").store(store).build();
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    // Seed a PENDING snapshot directly in the store.
    String now = Instant.now().toString();
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder()
            .sessionId("seeded-session")
            .custom(new HashMap<>())
            .build();
    SessionSnapshot<Map<String, Object>> pending =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-pending")
            .sessionId("seeded-session")
            .createdAt(now)
            .updatedAt(now)
            .status(SnapshotStatus.PENDING)
            .state(state)
            .build();
    store.saveSnapshot("snap-pending", existing -> pending, SessionStoreOptions.empty());

    // abort companion: PENDING -> ABORTED
    AgentAbortRequest abortReq = AgentAbortRequest.builder().snapshotId("snap-pending").build();
    JsonNode resp1 = agent.abortAgentAction().runJson(ctx, JsonUtils.toJsonNode(abortReq), null);
    assertEquals("aborted", resp1.get("status").asText());
    assertEquals("snap-pending", resp1.get("snapshotId").asText());

    // calling again: terminal unchanged, still ABORTED
    JsonNode resp2 = agent.abortAgentAction().runJson(ctx, JsonUtils.toJsonNode(abortReq), null);
    assertEquals("aborted", resp2.get("status").asText());
  }

  // ── typed facades ────────────────────────────────────────────────────────────

  @Test
  void testTypedFacades() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("facade").store(store).build();
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    JsonNode out =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(userInput("hi")), c -> {});
    String snapshotId = out.get("snapshotId").asText();

    // getSnapshotData typed facade
    SessionSnapshot<Map<String, Object>> snap =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertNotNull(snap);
    assertEquals(snapshotId, snap.getSnapshotId());
    assertEquals(SnapshotStatus.COMPLETED, snap.getStatus());

    // abort typed facade: COMPLETED is terminal -> stays COMPLETED
    SnapshotStatus afterAbort = agent.abort(snapshotId);
    assertEquals(SnapshotStatus.COMPLETED, afterAbort);

    // ref()
    AgentRef ref = agent.ref();
    assertEquals("facade", ref.getName());

    // store()/serverManaged()
    assertSame(store, agent.store());
    assertTrue(agent.serverManaged());
  }

  // ── behavior 18: a user-defined SessionStore is genuinely exercised ─────────

  /**
   * A minimal, independent {@link SessionStore} implementation backed by a {@link
   * ConcurrentHashMap}, used to prove {@code defineCustomAgent} genuinely calls through to whatever
   * store implementation the caller supplies (not just the built-in {@link InMemorySessionStore}).
   */
  private static final class CountingSessionStore implements SessionStore<Map<String, Object>> {
    private final Map<String, SessionSnapshot<Map<String, Object>>> data =
        new ConcurrentHashMap<>();
    private final AtomicInteger saveCount = new AtomicInteger();
    private final AtomicInteger getCount = new AtomicInteger();

    @Override
    public SessionSnapshot<Map<String, Object>> getSnapshot(GetSnapshotOptions opts) {
      getCount.incrementAndGet();
      if (opts.getSnapshotId() != null) {
        return data.get(opts.getSnapshotId());
      }
      if (opts.getSessionId() != null) {
        // Single-snapshot-per-session in this minimal test double: return the newest.
        return data.values().stream()
            .filter(s -> opts.getSessionId().equals(s.getSessionId()))
            .reduce((a, b) -> b)
            .orElse(null);
      }
      return null;
    }

    @Override
    public String saveSnapshot(
        String snapshotId,
        SnapshotMutator<Map<String, Object>> mutator,
        SessionStoreOptions options) {
      saveCount.incrementAndGet();
      SessionSnapshot<Map<String, Object>> existing =
          snapshotId != null ? data.get(snapshotId) : null;
      SessionSnapshot<Map<String, Object>> result = mutator.apply(existing);
      if (result == null) {
        return null;
      }
      String finalId = snapshotId != null ? snapshotId : java.util.UUID.randomUUID().toString();
      result.setSnapshotId(finalId);
      if (result.getStatus() == null) {
        result.setStatus(SnapshotStatus.COMPLETED);
      }
      data.put(finalId, result);
      return finalId;
    }
  }

  @Test
  void testCustomSessionStoreIsUsed() throws Exception {
    CountingSessionStore customStore = new CountingSessionStore();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("customStore")
            .store(customStore)
            .build();
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, echoFn());

    // Turn 1: fresh session, no prior snapshot to read.
    JsonNode out1 =
        agent.runBidiJson(ctx, initJson(null), inputSourceWith(userInput("one")), c -> {});
    String sessionId = out1.get("sessionId").asText();
    String snapshotId1 = out1.get("snapshotId").asText();
    assertNotNull(sessionId);
    assertNotNull(snapshotId1);

    assertTrue(
        customStore.saveCount.get() > 0, "the custom store's saveSnapshot must have been called");
    assertEquals(1, customStore.saveCount.get());
    // The snapshot must genuinely be sitting in the custom store's own backing map.
    assertTrue(customStore.data.containsKey(snapshotId1));

    // Turn 2: resume by sessionId — this requires the custom store's getSnapshot to be called
    // and to genuinely return the turn-1 snapshot for the runtime to resume from.
    AgentInit<Map<String, Object>> init2 =
        AgentInit.<Map<String, Object>>builder().sessionId(sessionId).build();
    JsonNode out2 =
        agent.runBidiJson(ctx, initJson(init2), inputSourceWith(userInput("two")), c -> {});

    assertTrue(
        customStore.getCount.get() > 0, "the custom store's getSnapshot must have been called");
    assertEquals(2, customStore.saveCount.get(), "turn 2 must have saved a second snapshot");

    String snapshotId2 = out2.get("snapshotId").asText();
    assertNotEquals(snapshotId1, snapshotId2);

    // Resume genuinely worked: history accumulated across both turns (2 user + 2 model msgs),
    // sourced entirely from the custom store's own data.
    SessionSnapshot<Map<String, Object>> snap2 = customStore.data.get(snapshotId2);
    assertNotNull(snap2);
    assertEquals(4, snap2.getState().getMessages().size());
    assertEquals("echo: two", snap2.getState().getMessages().get(3).getContent().get(0).getText());
  }
}
