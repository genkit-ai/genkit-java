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

package com.google.genkit.conformance.agent;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ToolInterruptException;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.Artifact;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.agent.internal.AgentActions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the harness's Genkit instance with the agents, tools and programmable model that the
 * {@code tests/specs/agent.yaml} conformance suite drives.
 *
 * <p>This mirrors {@code setupHarness} in the JS ({@code js/ai/tests/agents_spec_test.ts}) and Go
 * ({@code go/ai/exp/agents_conformance_test.go}) reference harnesses: the same single dynamic
 * (free-form {@code Map}) custom-state type serves every agent (prompt agents ignore custom state;
 * the custom-state agents manipulate it).
 *
 * <p>A fresh {@link Fixtures} is created per test case (the JS/Go harnesses rebuild the registry
 * before every test) so server-managed stores never leak snapshots across cases.
 */
final class Fixtures {

  /**
   * Safety cap (ms) on how long {@code customAgentBlocking} blocks a turn, so a missing abort
   * signal can never hang the suite. The abort-pending cases observe the PENDING snapshot and abort
   * well within this window.
   */
  private static final long BLOCKING_SAFETY_MILLIS = 3000L;

  private final Genkit genkit;
  private final ProgrammableModel programmableModel;
  private final Map<String, Agent<Map<String, Object>>> agents = new HashMap<>();

  Fixtures() {
    this.genkit = new Genkit(GenkitOptions.builder().experimental(true).build());
    this.programmableModel = new ProgrammableModel("programmableModel");
    genkit.registerModel(programmableModel);
    registerTools();
    registerPromptAgents();
    registerCustomAgents();
  }

  Genkit genkit() {
    return genkit;
  }

  ProgrammableModel programmableModel() {
    return programmableModel;
  }

  Agent<Map<String, Object>> agent(String name) {
    return agents.get(name);
  }

  // ── tools ──────────────────────────────────────────────────────────────────────

  private com.google.genkit.ai.Tool<?, ?> testTool;
  private com.google.genkit.ai.Tool<?, ?> interruptTool;
  private com.google.genkit.ai.Tool<?, ?> restartTool;

  private void registerTools() {
    // testTool: {} -> "tool called"
    testTool =
        genkit.defineTool(
            "testTool",
            "A simple test tool",
            (ctx, in) -> "tool called",
            Object.class,
            String.class);

    // interruptTool: always pauses the turn, returning the tool request to the client for
    // external resolution (resume.respond).
    interruptTool =
        genkit.defineTool(
            "interruptTool",
            "An interrupt tool",
            (ctx, in) -> {
              throw new ToolInterruptException();
            },
            Object.class,
            Object.class);

    // restartTool: interrupts on first call; succeeds when restarted with resumed metadata. A
    // restart-aware tool reads ctx.isResumed()/getResumed() to distinguish the first (interrupting)
    // call from a re-invocation after resume.restart, exactly like a real human-in-the-loop tool.
    restartTool =
        genkit.defineTool(
            "restartTool",
            "A tool that requires confirmation before executing",
            (ctx, in) -> {
              if (ctx.isResumed()) {
                // Re-invoked via resume.restart with the client's approval payload in getResumed().
                return "restarted: " + ctx.getResumed();
              }
              throw new ToolInterruptException(Map.of("requiresConfirmation", true));
            },
            Object.class,
            Object.class);
  }

  // ── prompt-backed agents (use the programmable model) ────────────────────────────

  private void registerPromptAgents() {
    register(
        "promptAgent",
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("promptAgent")
                    .model("programmableModel")
                    .build()));

    register(
        "promptAgentWithStore",
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("promptAgentWithStore")
                    .model("programmableModel")
                    .store(new InMemorySessionStore<>())
                    .build()));

    register(
        "promptAgentWithTools",
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("promptAgentWithTools")
                    .model("programmableModel")
                    .tools(List.of(testTool))
                    .build()));

    register(
        "promptAgentWithInterrupt",
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("promptAgentWithInterrupt")
                    .model("programmableModel")
                    .tools(List.of(interruptTool))
                    .store(new InMemorySessionStore<>())
                    .build()));

    register(
        "promptAgentWithRestartTool",
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("promptAgentWithRestartTool")
                    .model("programmableModel")
                    .tools(List.of(restartTool))
                    .store(new InMemorySessionStore<>())
                    .build()));
  }

  // ── custom agents (deterministic, via defineCustomAgent) ─────────────────────────

  private void registerCustomAgents() {
    // customAgentBlocking: server-managed, blocks until the caller requests cancellation. The
    // conformance suite only ever drives it detached + abort (the abort flips the snapshot status
    // in the store directly). A bounded safety timeout guarantees the harness can never hang even
    // though the in-process abort does not currently propagate the signal to a running turn fn.
    register(
        "customAgentBlocking",
        defineCustom(
            "customAgentBlocking",
            true,
            (sess, ctx) -> {
              long deadline = System.currentTimeMillis() + BLOCKING_SAFETY_MILLIS;
              while (!ctx.isAborted() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
              }
              return AgentResult.builder()
                  .message(Message.model("unblocked"))
                  .finishReason(AgentFinishReason.STOP)
                  .build();
            }));

    // customAgentFailing: server-managed, fails during processing.
    register(
        "customAgentFailing",
        defineCustom(
            "customAgentFailing",
            true,
            (sess, ctx) -> {
              throw new RuntimeException("intentional failure");
            }));

    // customAgentWithArtifacts: client-managed, streams and dedupes artifacts.
    register(
        "customAgentWithArtifacts",
        defineCustom(
            "customAgentWithArtifacts",
            false,
            (sess, ctx) -> {
              sess.addArtifacts(artifact("doc1", "v1"));
              sess.addArtifacts(artifact("doc1", "v2"));
              sess.addArtifacts(artifact("doc2", "other"));
              return done();
            }));

    // customAgentWithCustomState: client-managed, increments custom.counter.
    register(
        "customAgentWithCustomState", defineCustom("customAgentWithCustomState", false, COUNTER));

    // customAgentWithMultiCustomState: client-managed, three sequential custom-state updates.
    register(
        "customAgentWithMultiCustomState",
        defineCustom(
            "customAgentWithMultiCustomState",
            false,
            (sess, ctx) -> {
              sess.updateCustom(prev -> mapOf("counter", 1, "status", "working"));
              sess.updateCustom(
                  prev -> {
                    Map<String, Object> out = new HashMap<>(prev);
                    out.put("counter", 2);
                    return out;
                  });
              sess.updateCustom(
                  prev -> {
                    Map<String, Object> out = new HashMap<>(prev);
                    out.put("status", "done");
                    return out;
                  });
              return done();
            }));

    // customAgentWithArtifactsStore: server-managed, adds a numbered artifact per invocation.
    register(
        "customAgentWithArtifactsStore",
        defineCustom(
            "customAgentWithArtifactsStore",
            true,
            (sess, ctx) -> {
              int count = sess.getArtifacts().size() + 1;
              sess.addArtifacts(artifact("doc" + count, "content" + count));
              return done();
            }));

    // customAgentWithCustomStateStore: server-managed counter agent.
    register(
        "customAgentWithCustomStateStore",
        defineCustom("customAgentWithCustomStateStore", true, COUNTER));
  }

  /** Counter agent func: increments custom.counter by 1 each turn (default 0 -> 1). */
  private static final AgentFn<Map<String, Object>> COUNTER =
      (sess, ctx) -> {
        Map<String, Object> prev = sess.getCustom();
        long counter = 0;
        if (prev != null && prev.get("counter") instanceof Number n) {
          counter = n.longValue();
        }
        long next = counter + 1;
        sess.updateCustom(p -> mapOf("counter", next));
        return done();
      };

  // ── helpers ──────────────────────────────────────────────────────────────────────

  private Agent<Map<String, Object>> defineCustom(
      String name, boolean serverManaged, AgentFn<Map<String, Object>> fn) {
    CustomAgentConfig.Builder<Map<String, Object>> cfg =
        CustomAgentConfig.<Map<String, Object>>builder().name(name);
    if (serverManaged) {
      cfg.store(new InMemorySessionStore<>());
    }
    return AgentActions.defineCustomAgent(genkit.getRegistry(), cfg.build(), fn);
  }

  private void register(String name, Agent<Map<String, Object>> agent) {
    agents.put(name, agent);
  }

  private static AgentResult done() {
    return AgentResult.builder()
        .message(Message.model("done"))
        .finishReason(AgentFinishReason.STOP)
        .build();
  }

  private static Artifact artifact(String name, String text) {
    return Artifact.builder().name(name).parts(List.of(Part.text(text))).build();
  }

  private static Map<String, Object> mapOf(String k, Object v) {
    Map<String, Object> m = new HashMap<>();
    m.put(k, v);
    return m;
  }

  private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
    Map<String, Object> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }
}
