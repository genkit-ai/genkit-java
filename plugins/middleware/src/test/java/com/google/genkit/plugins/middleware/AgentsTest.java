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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.AgentSessionContext;
import com.google.genkit.ai.agent.Artifact;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.agent.Session;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.Registry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for {@link Agents} sub-agent delegation (Stage 6). */
class AgentsTest {

  private Registry registry;
  private ActionContext ctx;

  @BeforeEach
  void setUp() {
    registry = new DefaultRegistry();
    ctx = new ActionContext(registry);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** Registers a client-managed sub-agent whose turn returns a fixed assistant message. */
  private void defineFixedAgent(String name, String responseText) {
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model(responseText))
                .finishReason(AgentFinishReason.STOP)
                .build();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name(name).build();
    AgentActions.defineCustomAgent(registry, config, fn);
  }

  /** Registers a sub-agent that also produces a named artifact. */
  private void defineArtifactAgent(String name, String responseText, String artifactContent) {
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model(responseText))
                .artifacts(
                    Collections.singletonList(
                        Artifact.builder()
                            .name("report")
                            .parts(Collections.singletonList(Part.text(artifactContent)))
                            .build()))
                .finishReason(AgentFinishReason.STOP)
                .build();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name(name).build();
    AgentActions.defineCustomAgent(registry, config, fn);
  }

  @SuppressWarnings("unchecked")
  private static com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> tool(
      List<com.google.genkit.ai.Tool<?, ?>> tools, String name) {
    return (com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput>)
        tools.stream().filter(t -> name.equals(t.getName())).findFirst().orElseThrow();
  }

  private static Session<Map<String, Object>> newSession() {
    return new Session<>(SessionState.<Map<String, Object>>builder().build());
  }

  // ── tests ──────────────────────────────────────────────────────────────────────

  @Test
  void delegationToolNamesUsePrefix() {
    defineFixedAgent("researcher", "result");
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("researcher").build());
    assertEquals(1, tools.size());
    assertEquals("delegate_to_researcher", tools.get(0).getName());
  }

  @Test
  void emptyPrefixUsesBareName() {
    defineFixedAgent("researcher", "result");
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("researcher").toolPrefix("").build());
    assertEquals("researcher", tools.get(0).getName());
  }

  @Test
  void delegateRunsSubAgentAndReturnsText() {
    defineFixedAgent("researcher", "I found X in the archives.");
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("researcher").build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_researcher");

    Agents.DelegateInput in = new Agents.DelegateInput();
    in.task = "find X";
    Agents.DelegateOutput out = delegate.run(ctx, in);

    assertEquals("I found X in the archives.", out.response);
  }

  @Test
  void maxDelegationsCapReturnsLimitMessage() {
    defineFixedAgent("researcher", "ok");
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(
            AgentsOptions.builder().agents("researcher").maxDelegations(1).build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_researcher");

    Agents.DelegateInput in = new Agents.DelegateInput();
    in.task = "t";

    Agents.DelegateOutput first = delegate.run(ctx, in);
    assertEquals("ok", first.response);

    Agents.DelegateOutput second = delegate.run(ctx, in);
    assertTrue(
        second.response.toLowerCase().contains("delegation limit reached"),
        "expected limit message, got: " + second.response);
  }

  @Test
  void unknownAgentReturnsErrorText() {
    // No agent registered under this name.
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("ghost").build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_ghost");

    Agents.DelegateInput in = new Agents.DelegateInput();
    in.task = "t";
    Agents.DelegateOutput out = delegate.run(ctx, in);
    assertTrue(out.response.toLowerCase().contains("not registered"), out.response);
  }

  @Test
  void inlineArtifactsAreNamespacedMergedAndContentIncluded() {
    defineArtifactAgent("researcher", "done", "the body of the report");
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(
            AgentsOptions.builder()
                .agents("researcher")
                .artifactStrategy(ArtifactStrategy.INLINE)
                .build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_researcher");

    Session<Map<String, Object>> parent = newSession();
    AgentSessionContext.run(
        parent,
        () -> {
          Agents.DelegateInput in = new Agents.DelegateInput();
          in.task = "write report";
          Agents.DelegateOutput out = delegate.run(ctx, in);

          assertEquals("done", out.response);
          assertNotNull(out.artifacts);
          assertEquals(1, out.artifacts.size());
          // namespaced as <invocationId>/report
          assertTrue(out.artifacts.get(0).name.endsWith("/report"), out.artifacts.get(0).name);
          // INLINE includes content
          assertEquals("the body of the report", out.artifacts.get(0).content);
        });

    // merged into the parent session, namespaced
    assertEquals(1, parent.getArtifacts().size());
    assertTrue(parent.getArtifacts().get(0).getName().endsWith("/report"));
  }

  @Test
  void sessionStrategyMergesButOmitsContent() {
    defineArtifactAgent("researcher", "done", "secret body");
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(
            AgentsOptions.builder()
                .agents("researcher")
                .artifactStrategy(ArtifactStrategy.SESSION)
                .build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_researcher");

    Session<Map<String, Object>> parent = newSession();
    AgentSessionContext.run(
        parent,
        () -> {
          Agents.DelegateInput in = new Agents.DelegateInput();
          in.task = "write report";
          Agents.DelegateOutput out = delegate.run(ctx, in);
          assertEquals(1, out.artifacts.size());
          // SESSION: name present, content omitted
          assertTrue(out.artifacts.get(0).name.endsWith("/report"));
          org.junit.jupiter.api.Assertions.assertNull(out.artifacts.get(0).content);
        });

    assertEquals(1, parent.getArtifacts().size());
  }

  @Test
  void systemPromptFragmentListsTools() {
    String fragment =
        Agents.systemPromptFragment(AgentsOptions.builder().agents("researcher", "writer").build());
    assertTrue(fragment.contains("<sub-agents>"));
    assertTrue(fragment.contains("delegate_to_researcher"));
    assertTrue(fragment.contains("delegate_to_writer"));
  }

  @Test
  void historyLengthOptionForwardsPriorDelegationContext() {
    // Fixed behavior (see Agents/AgentsOptions javadoc and internal.Delegation): repeated
    // delegation to the SAME sub-agent within the SAME parent session now resumes that
    // (parent-session, sub-agent) pair's own accumulated sub-session, trimmed to historyLength.
    // The sub-agent is client-managed (no store), so history is forwarded via the internal
    // client-history ledger + AgentInit.state. Prove real forwarding by having the sub-agent
    // report exactly how many messages it can see on each call: call 1 sees only its own task (1);
    // call 2 sees call 1's task + response + its own task (3); call 3 sees all six messages so far
    // (call 1's task+response, call 2's task+response, its own task) = 5, still under the
    // historyLength=10 cap, so nothing is trimmed yet.
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("saw " + sess.getMessages().size() + " message(s)"))
                .finishReason(AgentFinishReason.STOP)
                .build();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("researcher").build();
    AgentActions.defineCustomAgent(registry, config, fn);

    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(
            AgentsOptions.builder().agents("researcher").historyLength(10).build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_researcher");

    // Bind a parent session so the derived sub-session key is stable and isolated from other
    // tests running against the same "researcher" agent name.
    Session<Map<String, Object>> parent = newSession();
    AgentSessionContext.run(
        parent,
        () -> {
          Agents.DelegateInput in = new Agents.DelegateInput();

          in.task = "task 1";
          assertEquals(
              "saw 1 message(s)",
              delegate.run(ctx, in).response,
              "first delegation call has no prior history: sees only its own task");

          in.task = "task 2";
          assertEquals(
              "saw 3 message(s)",
              delegate.run(ctx, in).response,
              "second call should see call 1's task+response plus its own task");

          in.task = "task 3";
          assertEquals(
              "saw 5 message(s)",
              delegate.run(ctx, in).response,
              "third call should see calls 1 and 2's task+response plus its own task");
        });
  }

  @Test
  void historyLengthOptionTrimsToConfiguredLength() {
    // historyLength caps how much prior context is forwarded: with historyLength=2, the sub-agent
    // should only ever see its own task plus at most 2 prior messages, never the full backlog.
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("saw " + sess.getMessages().size() + " message(s)"))
                .finishReason(AgentFinishReason.STOP)
                .build();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("trimmer").build();
    AgentActions.defineCustomAgent(registry, config, fn);

    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("trimmer").historyLength(2).build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_trimmer");

    Session<Map<String, Object>> parent = newSession();
    AgentSessionContext.run(
        parent,
        () -> {
          Agents.DelegateInput in = new Agents.DelegateInput();

          in.task = "task 1";
          assertEquals("saw 1 message(s)", delegate.run(ctx, in).response);

          in.task = "task 2";
          // Prior history (task 1 + response = 2 messages) trimmed to historyLength=2, plus the
          // new task message = 3.
          assertEquals("saw 3 message(s)", delegate.run(ctx, in).response);

          in.task = "task 3";
          // Accumulated history is now 4 messages (task1, resp1, task2, resp2); trimmed to the
          // trailing 2, plus the new task message = 3 (never grows past the cap + 1).
          assertEquals(
              "saw 3 message(s)",
              delegate.run(ctx, in).response,
              "history forwarded to the sub-agent must stay capped at historyLength, not grow"
                  + " unbounded");
        });
  }

  @Test
  void historyIsIsolatedPerParentSessionAndPerSubAgent() {
    // A different parent session delegating to the SAME sub-agent name must get an independent
    // sub-session (no bleed-over from another parent's conversation).
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("saw " + sess.getMessages().size() + " message(s)"))
                .finishReason(AgentFinishReason.STOP)
                .build();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("isolated").build();
    AgentActions.defineCustomAgent(registry, config, fn);

    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(
            AgentsOptions.builder().agents("isolated").historyLength(10).build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_isolated");

    Session<Map<String, Object>> parentA = newSession();
    AgentSessionContext.run(
        parentA,
        () -> {
          Agents.DelegateInput in = new Agents.DelegateInput();
          in.task = "A task 1";
          assertEquals("saw 1 message(s)", delegate.run(ctx, in).response);
          in.task = "A task 2";
          assertEquals("saw 3 message(s)", delegate.run(ctx, in).response);
        });

    Session<Map<String, Object>> parentB = newSession();
    AgentSessionContext.run(
        parentB,
        () -> {
          Agents.DelegateInput in = new Agents.DelegateInput();
          in.task = "B task 1";
          assertEquals(
              "saw 1 message(s)",
              delegate.run(ctx, in).response,
              "a different parent session must not see parent A's history for the same sub-agent");
        });
  }

  @Test
  void interruptedSubAgentThrowsStructuredToolInterrupt() {
    // Fixed behavior (see Agents/internal.Delegation javadoc): finishReason==INTERRUPTED now
    // causes the delegation tool to throw ToolInterruptException (the same exception type/
    // convention Tool.run re-throws for human-in-the-loop), carrying the sub-agent's interrupted
    // tool name/input as metadata, instead of collapsing to plain text. This lets the PARENT's own
    // generate/tool-calling loop pause too (Genkit.java's tool-execution loop catches
    // ToolInterruptException specifically and surfaces FinishReason.INTERRUPTED).
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          com.google.genkit.ai.ToolRequest toolRequest =
              new com.google.genkit.ai.ToolRequest(
                  "confirmAction", Map.of("action", "do the risky thing"));
          com.google.genkit.ai.Part toolRequestPart = new com.google.genkit.ai.Part();
          toolRequestPart.setToolRequest(toolRequest);
          Message interrupted =
              new Message(
                  com.google.genkit.ai.Role.MODEL,
                  List.of(
                      com.google.genkit.ai.Part.text("need approval to continue"),
                      toolRequestPart));
          return AgentResult.builder()
              .message(interrupted)
              .finishReason(AgentFinishReason.INTERRUPTED)
              .build();
        };
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("approver").build();
    AgentActions.defineCustomAgent(registry, config, fn);

    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("approver").build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_approver");

    Agents.DelegateInput in = new Agents.DelegateInput();
    in.task = "do the risky thing";

    com.google.genkit.ai.ToolInterruptException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.google.genkit.ai.ToolInterruptException.class, () -> delegate.run(ctx, in));

    assertTrue(
        thrown.getMessage().toLowerCase().contains("interrupted"),
        "expected a descriptive interrupted message, got: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains("need approval to continue"),
        "expected the sub-agent's message text folded into the exception message, got: "
            + thrown.getMessage());
    assertEquals(
        "confirmAction",
        thrown.getMetadata().get("name"),
        "expected the sub-agent's interrupted tool name to be carried as metadata");
    assertEquals(
        Map.of("action", "do the risky thing"),
        thrown.getMetadata().get("input"),
        "expected the sub-agent's interrupted tool input to be carried as metadata");
  }

  @Test
  void interruptedSubAgentWithNoToolRequestStillThrowsInterrupt() {
    // Even if the sub-agent's INTERRUPTED message carries no tool-request part (e.g. a
    // hand-rolled AgentFn that sets finishReason without populating toolRequests), the delegation
    // tool must still throw ToolInterruptException rather than falling back to descriptive text.
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("need approval to continue"))
                .finishReason(AgentFinishReason.INTERRUPTED)
                .build();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("bareapprover").build();
    AgentActions.defineCustomAgent(registry, config, fn);

    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("bareapprover").build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_bareapprover");

    Agents.DelegateInput in = new Agents.DelegateInput();
    in.task = "do the risky thing";

    com.google.genkit.ai.ToolInterruptException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.google.genkit.ai.ToolInterruptException.class, () -> delegate.run(ctx, in));
    assertTrue(thrown.getMessage().contains("need approval to continue"));
  }

  @Test
  void failingSubAgentThrowsGenkitException() {
    // Fixed behavior (see Agents/internal.Delegation javadoc): finishReason==FAILED now causes the
    // delegation tool to throw a GenkitException carrying the sub-agent's actual error message,
    // instead of collapsing to plain text. This propagates like any other tool exception in
    // Genkit's normal tool-calling loop (Genkit.java's generic catch folds it into a ToolResponse
    // error rather than a structured interrupt).
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          throw new RuntimeException("boom: downstream service unavailable");
        };
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("flaky").build();
    AgentActions.defineCustomAgent(registry, config, fn);

    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("flaky").build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_flaky");

    Agents.DelegateInput in = new Agents.DelegateInput();
    in.task = "do something";

    com.google.genkit.core.GenkitException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.google.genkit.core.GenkitException.class, () -> delegate.run(ctx, in));

    assertTrue(
        thrown.getMessage().toLowerCase().contains("failed"),
        "expected descriptive failure text in the exception message, got: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains("boom: downstream service unavailable"),
        "expected the underlying error message folded into the exception message, got: "
            + thrown.getMessage());
  }

  @Test
  void delegationToolsProducesOneToolPerConfiguredAgentWithDescriptiveText() {
    defineFixedAgent("researcher", "r");
    defineFixedAgent("writer", "w");
    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("researcher", "writer").build());

    assertEquals(2, tools.size());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> researcherTool =
        tool(tools, "delegate_to_researcher");
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> writerTool =
        tool(tools, "delegate_to_writer");

    assertTrue(researcherTool.getDesc().getDescription().contains("researcher"));
    assertTrue(writerTool.getDesc().getDescription().contains("writer"));
  }

  @Test
  void serverManagedSubAgentAlsoWorks() {
    // sub-agent with a session store (server-managed) – delegation should still get its text.
    InMemorySessionStore<Map<String, Object>> store = new InMemorySessionStore<>();
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("server says hi"))
                .finishReason(AgentFinishReason.STOP)
                .build();
    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("svragent").store(store).build();
    AgentActions.defineCustomAgent(registry, config, fn);

    List<com.google.genkit.ai.Tool<?, ?>> tools =
        Agents.delegationTools(AgentsOptions.builder().agents("svragent").build());
    com.google.genkit.ai.Tool<Agents.DelegateInput, Agents.DelegateOutput> delegate =
        tool(tools, "delegate_to_svragent");

    Agents.DelegateInput in = new Agents.DelegateInput();
    in.task = "ping";
    Agents.DelegateOutput out = delegate.run(ctx, in);
    assertEquals("server says hi", out.response);
  }
}
