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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.Registry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests proving the 4 core-runtime fixes:
 *
 * <ol>
 *   <li>Fix 1 (custom-AgentFn half): {@code ctx.resume()} carries the real {@code respond}/{@code
 *       restart} parts on a resume turn (see {@code GenkitBetaTest} / {@code
 *       AgentConformanceTest}'s "interrupt resume state accumulation" for the generate-backed
 *       half).
 *   <li>Fix 2: {@code agent.abort(snapshotId)} flips {@code AgentFnContext.isAborted()} for a
 *       still-running DETACHED turn.
 *   <li>Fix 3: {@code AgentSessionContext.current()} is bound to the real {@link Session} during a
 *       running turn.
 *   <li>Fix 4: a custom agent's registered metadata includes a generated {@code stateSchema} for a
 *       non-trivial POJO state type.
 * </ol>
 */
class CoreFixesTest {

  private Registry registry;
  private ActionContext ctx;

  @BeforeEach
  void setUp() {
    registry = new DefaultRegistry();
    ctx = new ActionContext(registry);
  }

  // ── Fix 1: ctx.resume() reaches a custom AgentFn ─────────────────────────────

  @Test
  void customAgentFnObservesResumeRespondParts() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("resumeFn").store(store).build();

    AtomicReference<ToolResume> observedResume = new AtomicReference<>();
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          if (fnCtx.resume() == null) {
            // Turn 1: model "interrupts".
            Part toolRequestPart = new Part();
            ToolRequest tr = new ToolRequest();
            tr.setName("confirmAction");
            tr.setRef("ref-1");
            toolRequestPart.setToolRequest(tr);
            return AgentResult.builder()
                .message(new Message(Role.MODEL, List.of(toolRequestPart)))
                .finishReason(AgentFinishReason.INTERRUPTED)
                .build();
          }
          // Turn 2 (resume): record what ctx.resume() actually carried.
          observedResume.set(fnCtx.resume());
          return AgentResult.builder()
              .message(Message.model("resumed"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };

    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, fn);
    AgentChat<Map<String, Object>> chat = agent.chat(ctx);

    chat.send("please confirm");

    Part respondPart = new Part();
    respondPart.setToolResponse(new ToolResponse("ref-1", "confirmAction", Map.of("ok", true)));
    chat.resume(List.of(respondPart));

    ToolResume resume = observedResume.get();
    assertNotNull(resume, "expected the custom AgentFn to observe a non-null ctx.resume()");
    assertNotNull(resume.getRespond(), "expected resume.getRespond() to be populated");
    assertEquals(1, resume.getRespond().size());
    ToolResponse tresp = resume.getRespond().get(0).getToolResponse();
    assertNotNull(tresp, "expected the respond part to carry a real ToolResponse");
    assertEquals("confirmAction", tresp.getName());
    assertEquals("ref-1", tresp.getRef());
    assertEquals(Map.of("ok", true), tresp.getOutput());
  }

  @Test
  void nonResumeTurnHasNullCtxResume() throws Exception {
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("noResume").build();
    AtomicReference<ToolResume> observed = new AtomicReference<>();
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          observed.set(fnCtx.resume());
          return AgentResult.builder()
              .message(Message.model("ok"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, fn);
    agent.chat(ctx).send("hi");

    // observed.get() was actually set (fn ran); a plain (non-resume) turn must see ctx.resume()
    // == null, not accidentally reuse a stale ToolResume.
    assertEquals(null, observed.get());
  }

  // ── Fix 2: abort() signals a running DETACHED turn ───────────────────────────

  @Test
  void abortSignalsRunningDetachedTurn() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("abortable").store(store).build();

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch fnFinished = new CountDownLatch(1);
    AtomicBoolean observedAborted = new AtomicBoolean(false);
    AgentFn<Map<String, Object>> gatedFn =
        (sess, fnCtx) -> {
          started.countDown();
          try {
            long deadline = System.currentTimeMillis() + 5000;
            while (!fnCtx.isAborted() && System.currentTimeMillis() < deadline) {
              Thread.sleep(10);
            }
            observedAborted.set(fnCtx.isAborted());
            return AgentResult.builder()
                .message(Message.model(fnCtx.isAborted() ? "stopped" : "timed-out"))
                .finishReason(
                    fnCtx.isAborted() ? AgentFinishReason.ABORTED : AgentFinishReason.STOP)
                .build();
          } finally {
            // Signal that THIS function has genuinely observed/reacted to the abort (or timed
            // out), independent of when the store-level snapshot status flips: Agent.abort()
            // flips the stored status synchronously in the calling thread, which can race ahead
            // of this background thread noticing the AtomicBoolean flip on its next poll tick.
            fnFinished.countDown();
          }
        };

    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, gatedFn);

    com.fasterxml.jackson.databind.JsonNode initJson =
        com.google.genkit.core.JsonUtils.toJsonNode(new AgentInit<Map<String, Object>>());
    com.google.genkit.core.BufferedInputSource<JsonNode> inputs =
        new com.google.genkit.core.BufferedInputSource<>();
    AgentInput detachInput = AgentInput.builder().message(Message.user("go")).detach(true).build();
    inputs.offer(com.google.genkit.core.JsonUtils.toJsonNode(detachInput));
    inputs.end();

    JsonNode out = agent.runBidiJson(ctx, initJson, inputs, c -> {});
    String snapshotId = out.get("snapshotId").asText();
    assertNotNull(snapshotId);
    assertFalse(snapshotId.isEmpty());

    // The background turn is definitely running now (it counted down `started`), and is
    // definitely still blocked in its poll loop (it only exits on abort or a 5s timeout).
    assertTrue(started.await(5, TimeUnit.SECONDS), "background turn should have started");

    SnapshotStatus statusAfterAbort = agent.abort(snapshotId);
    assertEquals(SnapshotStatus.ABORTED, statusAfterAbort);

    // Wait for the AgentFn itself to finish reacting (NOT for the store's snapshot status, which
    // Agent.abort() flips synchronously in the calling thread — that would race ahead of the
    // background thread's next poll tick and prove nothing about whether the fn actually saw the
    // signal). This is the crux of Fix 2: the running function's own isAborted() check must have
    // observed true.
    assertTrue(fnFinished.await(5, TimeUnit.SECONDS), "the gated AgentFn should have finished");
    assertTrue(
        observedAborted.get(),
        "the running AgentFn must have observed ctx.isAborted() == true and reacted to it");

    // The store-level status must also reflect the abort once the background turn finalizes.
    SessionSnapshot<Map<String, Object>> finalSnap = pollForTerminal(agent, snapshotId, 5000);
    assertNotNull(finalSnap);
    assertEquals(SnapshotStatus.ABORTED, finalSnap.getStatus());
  }

  @Test
  void abortOfUnknownSnapshotIdIsANoOpForRegistry() {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("abortUnknown").store(store).build();
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("x"))
                .finishReason(AgentFinishReason.STOP)
                .build();
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, fn);

    // No such snapshot exists at all (never registered, never persisted): abort() must not throw.
    SnapshotStatus result = agent.abort("does-not-exist");
    assertEquals(null, result);
  }

  private SessionSnapshot<Map<String, Object>> pollForTerminal(
      Agent<Map<String, Object>> agent, String snapshotId, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    SessionSnapshot<Map<String, Object>> snap = null;
    while (System.currentTimeMillis() < deadline) {
      snap = agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
      if (snap != null
          && (snap.getStatus() == SnapshotStatus.COMPLETED
              || snap.getStatus() == SnapshotStatus.FAILED
              || snap.getStatus() == SnapshotStatus.ABORTED)) {
        return snap;
      }
      Thread.sleep(20);
    }
    return snap;
  }

  // ── Fix 3: AgentSessionContext is bound during a running turn ───────────────

  @Test
  void agentSessionContextIsBoundDuringForegroundTurn() throws Exception {
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("ctxBound").build();

    AtomicReference<Session<?>> observed = new AtomicReference<>();
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          observed.set(AgentSessionContext.current());
          return AgentResult.builder()
              .message(Message.model("ok"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, fn);

    // Not bound before/after any turn runs.
    assertEquals(null, AgentSessionContext.current());

    agent.chat(ctx).send("hi");

    assertNotNull(observed.get(), "expected AgentSessionContext.current() to be non-null mid-turn");

    // Unbound again after the turn completes.
    assertEquals(null, AgentSessionContext.current());
  }

  @Test
  void agentSessionContextIsBoundDuringDetachedTurn() throws Exception {
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("ctxBoundDetached")
            .store(store)
            .build();

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Session<?>> observed = new AtomicReference<>();
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          observed.set(AgentSessionContext.current());
          done.countDown();
          return AgentResult.builder()
              .message(Message.model("ok"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        };
    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, fn);

    JsonNode initJson =
        com.google.genkit.core.JsonUtils.toJsonNode(new AgentInit<Map<String, Object>>());
    com.google.genkit.core.BufferedInputSource<JsonNode> inputs =
        new com.google.genkit.core.BufferedInputSource<>();
    AgentInput detachInput = AgentInput.builder().message(Message.user("go")).detach(true).build();
    inputs.offer(com.google.genkit.core.JsonUtils.toJsonNode(detachInput));
    inputs.end();

    agent.runBidiJson(ctx, initJson, inputs, c -> {});
    assertTrue(done.await(5, TimeUnit.SECONDS), "detached turn should have completed");

    assertNotNull(
        observed.get(),
        "expected AgentSessionContext.current() to be non-null during detached turn");
  }

  // ── Fix 4: stateSchema populated for a custom agent with a POJO state type ──

  /** Non-trivial POJO state type with named fields, used to prove schema generation. */
  public static class ReviewState {
    private String status;
    private List<String> reviewers;

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public List<String> getReviewers() {
      return reviewers;
    }

    public void setReviewers(List<String> reviewers) {
      this.reviewers = reviewers;
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void customAgentMetadataIncludesStateSchemaForPojoStateType() {
    CustomAgentConfig<ReviewState> config =
        CustomAgentConfig.<ReviewState>builder()
            .name("typedCustom")
            .stateType(ReviewState.class)
            .build();
    AgentFn<ReviewState> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("x"))
                .finishReason(AgentFinishReason.STOP)
                .build();

    Agent<ReviewState> agent = AgentActions.defineCustomAgent(registry, config, fn);

    Map<String, Object> metadata = agent.getMetadata();
    assertNotNull(metadata);
    Map<String, Object> agentMeta = (Map<String, Object>) metadata.get("agent");
    assertNotNull(agentMeta, "expected an \"agent\" sub-map in metadata");

    Object stateSchemaObj = agentMeta.get("stateSchema");
    assertNotNull(stateSchemaObj, "expected a generated stateSchema for the ReviewState POJO");
    Map<String, Object> stateSchema = (Map<String, Object>) stateSchemaObj;
    Map<String, Object> properties = (Map<String, Object>) stateSchema.get("properties");
    assertNotNull(properties);
    assertTrue(properties.containsKey("status"));
    assertTrue(properties.containsKey("reviewers"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void customAgentMetadataOmitsStateSchemaForMapStateType() {
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("mapStateAgent").build();
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("x"))
                .finishReason(AgentFinishReason.STOP)
                .build();

    Agent<Map<String, Object>> agent = AgentActions.defineCustomAgent(registry, config, fn);

    Map<String, Object> agentMeta = (Map<String, Object>) agent.getMetadata().get("agent");
    assertNotNull(agentMeta);
    assertFalse(
        agentMeta.containsKey("stateSchema"),
        "no stateType was configured (defaults to a dynamic Map), so no stateSchema is expected");
  }

  @Test
  void agentFnContextNewConstructorDoesNotBreakOldOnes() {
    // Fix 1's new constructor overload must not have broken the pre-existing ones (used by other
    // callers). Exercise all four directly.
    AgentFnContext c1 = new AgentFnContext(chunk -> {}, new AtomicBoolean(false));
    assertFalse(c1.isAborted());
    assertEquals(null, c1.resume());

    AgentFnContext c2 = new AgentFnContext(chunk -> {}, new AtomicBoolean(false), ctx);
    assertSame(ctx, c2.context());
    assertEquals(null, c2.resume());

    ToolResume tr = ToolResume.builder().build();
    AgentFnContext c3 = new AgentFnContext(chunk -> {}, new AtomicBoolean(false), ctx, tr);
    assertSame(tr, c3.resume());
  }
}
