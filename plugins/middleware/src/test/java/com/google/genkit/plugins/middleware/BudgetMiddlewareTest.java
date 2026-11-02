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

import static com.google.genkit.plugins.middleware.CompressionTestSupport.map;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.model;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.req;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.response;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.toolCallMsg;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.InterruptedCall;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.Usage;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentOutput;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Tests {@link BudgetMiddleware}. */
class BudgetMiddlewareTest {

  private final Genkit genkit = new Genkit(GenkitOptions.builder().experimental(true).build());

  private static Usage inputTokens(int n) {
    Usage u = new Usage();
    u.setInputTokens(n);
    return u;
  }

  private static Usage cost(double v) {
    Usage u = new Usage();
    u.setCustom(new LinkedHashMap<>(Map.of("cost", v)));
    return u;
  }

  private static BudgetMiddleware budget(Usage limit) {
    return new BudgetMiddleware(new BudgetMiddleware.Options(limit));
  }

  private static BudgetMiddleware budget(Usage limit, String scope, boolean interrupt) {
    BudgetMiddleware.Options o = new BudgetMiddleware.Options(limit);
    o.scope = scope;
    o.interrupt = interrupt;
    return new BudgetMiddleware(o);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Tool<Map<String, Object>, Object> tool(
      String name, java.util.function.BiFunction<ActionContext, Map<String, Object>, Object> fn) {
    Class<Map<String, Object>> in = (Class) Map.class;
    return genkit.defineTool(name, name, fn, in, Object.class);
  }

  /**
   * Defines a model that asks for the "step" tool until the conversation holds steps tool
   * responses, then answers "done". Each call reports usage (10 input tokens when null) and is
   * counted in calls. Returns the step tool.
   */
  private Tool<Map<String, Object>, Object> defineStepModel(
      int steps, AtomicInteger calls, Usage usage) {
    Usage u = usage != null ? usage : inputTokens(10);
    Tool<Map<String, Object>, Object> step = tool("step", (ctx, in) -> "stepped");
    genkit.registerModel(
        model(
            "test/steps",
            (call, req) -> {
              calls.incrementAndGet();
              int responses = 0;
              for (Message msg : req.getMessages()) {
                for (Part p : msg.getContent()) {
                  if (p != null && p.getToolResponse() != null) {
                    responses++;
                  }
                }
              }
              ModelResponse resp =
                  responses < steps
                      ? response(toolCallMsg(req("step", null, map("v", "x"))), null)
                      : response(Message.model("done"), null);
              resp.setFinishReason(FinishReason.STOP);
              resp.setUsage(Usage.sum(u));
              return resp;
            },
            new ArrayList<>()));
    return step;
  }

  private ModelResponse generate(
      String model, Consumer<GenerateOptions.Builder<Void>> opts, GenerationMiddleware... use) {
    GenerateOptions.Builder<Void> b = GenerateOptions.<Void>builder().model(model).use(use);
    opts.accept(b);
    return genkit.generate(b.build());
  }

  @Test
  void stopsTheRunBeforeTheTurnPastItsLimit() {
    AtomicInteger calls = new AtomicInteger();
    Tool<Map<String, Object>, Object> step = defineStepModel(100, calls, null);
    BudgetExceededException e =
        assertThrows(
            BudgetExceededException.class,
            () ->
                generate(
                    "test/steps",
                    b -> b.prompt("go").tools(List.of(step)),
                    budget(inputTokens(25))));
    // 10 and 20 are under 25, so the second and third turns run; 30 is not, so the fourth never
    // does.
    assertEquals(3, calls.get());
    assertEquals("ABORTED", e.getErrorCode());
    assertEquals(FinishReason.ABORTED, e.getResponse().getFinishReason());
  }

  /** A hook can call the model more than once per turn. Each of those calls is billed. */
  @Test
  void countsCallsAHookMakes() {
    AtomicInteger calls = new AtomicInteger();
    Tool<Map<String, Object>, Object> step = defineStepModel(100, calls, null);
    GenerationMiddleware twice =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "test/twice";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams p, ModelNext next) {
            next.apply(ctx, p);
            return next.apply(ctx, p);
          }
        };
    assertThrows(
        BudgetExceededException.class,
        () ->
            generate(
                "test/steps",
                b -> b.prompt("go").tools(List.of(step)),
                budget(inputTokens(20)),
                twice));
    assertEquals(2, calls.get(), "the first turn's two calls spend the budget");
  }

  /** Any field the limit sets is a cap, a provider's custom cost included. */
  @Test
  void capsCustomUsage() {
    AtomicInteger calls = new AtomicInteger();
    Tool<Map<String, Object>, Object> step = defineStepModel(100, calls, cost(0.5));
    assertThrows(
        BudgetExceededException.class,
        () -> generate("test/steps", b -> b.prompt("go").tools(List.of(step)), budget(cost(1))));
    assertEquals(2, calls.get());
  }

  /** A limit that is not a finite number can never be reached, so it is rejected by name. */
  @Test
  void rejectsANonFiniteLimit() {
    for (double v : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
      Usage limit = inputTokens(10);
      limit.setCustom(new LinkedHashMap<>(Map.of("cost", v)));
      GenkitException e = assertThrows(GenkitException.class, () -> budget(limit).newInstance());
      assertEquals("INVALID_ARGUMENT", e.getErrorCode());
      assertTrue(e.getMessage().contains("custom.cost"), e.getMessage());
    }
  }

  /**
   * A sub-agent's calls stay out of its parent's reported usage, but a budget on the parent's run
   * counts them, so delegation cannot spend around it.
   */
  @Test
  void runScopeCountsSubagents() {
    genkit.registerModel(
        model(
            "test/child",
            (call, req) -> response(Message.model("answered"), 10),
            new ArrayList<>()));
    AgentFn<Map<String, Object>> childFn =
        (sess, fnCtx) -> {
          ModelResponse r =
              genkit.generate(
                  GenerateOptions.<Void>builder()
                      .model("test/child")
                      .messages(sess.getMessages())
                      .build());
          return AgentResult.builder().message(r.getMessage()).build();
        };
    Agent<Map<String, Object>> child =
        genkit
            .beta()
            .defineCustomAgent(
                CustomAgentConfig.<Map<String, Object>>builder().name("child").build(), childFn);
    Tool<Map<String, Object>, Object> ask =
        tool(
            "ask",
            (ctx, in) -> {
              AgentOutput<Map<String, Object>> out =
                  child.runBidi(ctx, new AgentInit<>(), inputs(Message.user("question")), null);
              return out.getMessage().getText();
            });
    AtomicInteger calls = new AtomicInteger();
    genkit.registerModel(
        model(
            "test/parent",
            (call, req) -> {
              calls.incrementAndGet();
              return response(toolCallMsg(req("ask", null, map("v", "x"))), 10);
            },
            new ArrayList<>()));

    assertThrows(
        BudgetExceededException.class,
        () ->
            generate(
                "test/parent", b -> b.prompt("go").tools(List.of(ask)), budget(inputTokens(15))));
    // The parent's 10 plus the child's 10 reach 15 before the second turn.
    assertEquals(1, calls.get());
  }

  private static BufferedInputSource<AgentInput> inputs(Message... messages) {
    BufferedInputSource<AgentInput> src = new BufferedInputSource<>();
    for (Message m : messages) {
      src.offer(AgentInput.builder().message(m).build());
    }
    src.end();
    return src;
  }

  private AgentFn<Map<String, Object>> generatingFn(
      Tool<Map<String, Object>, Object> step, BudgetMiddleware b) {
    return (sess, fnCtx) -> {
      GenerateOptions.Builder<Void> opts =
          GenerateOptions.<Void>builder().model("test/steps").messages(sess.getMessages()).use(b);
      if (step != null) {
        opts.tools(List.of(step));
      }
      ModelResponse r = genkit.generate(opts.build());
      return AgentResult.builder().message(r.getMessage()).build();
    };
  }

  /**
   * A session budget carries across invocations: the next invocation of a session that is over its
   * limit stops before it calls the model.
   */
  @Test
  void sessionScope() {
    AtomicInteger calls = new AtomicInteger();
    Tool<Map<String, Object>, Object> step = defineStepModel(2, calls, null);
    Agent<Map<String, Object>> agent =
        genkit
            .beta()
            .defineCustomAgent(
                CustomAgentConfig.<Map<String, Object>>builder()
                    .name("budgeted")
                    .store(new InMemorySessionStore<>())
                    .build(),
                generatingFn(step, budget(inputTokens(15), BudgetMiddleware.SCOPE_SESSION, false)));
    ActionContext ctx = new ActionContext(genkit.getRegistry());

    // 10, then 20: the third turn would start over the limit.
    AgentOutput<Map<String, Object>> out =
        agent.runBidi(ctx, new AgentInit<>(), inputs(Message.user("go")), null);
    assertEquals(AgentFinishReason.ABORTED, out.getFinishReason(), String.valueOf(out.getError()));

    int before = calls.get();
    out =
        agent.runBidi(
            ctx,
            AgentInit.<Map<String, Object>>builder().sessionId(out.getSessionId()).build(),
            inputs(Message.user("again")),
            null);
    assertEquals(AgentFinishReason.ABORTED, out.getFinishReason(), String.valueOf(out.getError()));
    assertEquals(before, calls.get());
  }

  @Test
  void rejectsAnUnenforceableSessionBudgetInAClientManagedAgent() {
    // The caller sends the state back, usage included, so it could reset the count; only a store
    // makes it hold.
    AtomicInteger calls = new AtomicInteger();
    defineStepModel(0, calls, null);
    Agent<Map<String, Object>> agent =
        genkit
            .beta()
            .defineCustomAgent(
                CustomAgentConfig.<Map<String, Object>>builder().name("clientManaged").build(),
                generatingFn(null, budget(inputTokens(15), BudgetMiddleware.SCOPE_SESSION, false)));
    AgentOutput<Map<String, Object>> out =
        agent.runBidi(
            new ActionContext(genkit.getRegistry()),
            new AgentInit<>(),
            inputs(Message.user("go")),
            null);
    assertNotNull(out.getError());
    assertEquals("FAILED_PRECONDITION", out.getError().getStatus());
  }

  @Test
  void rejectsAnInterruptingSessionBudget() {
    // A later turn would start over the limit with no tool call to pause on.
    GenkitException e =
        assertThrows(
            GenkitException.class,
            () -> budget(inputTokens(15), BudgetMiddleware.SCOPE_SESSION, true).newInstance());
    assertEquals("INVALID_ARGUMENT", e.getErrorCode());
  }

  /**
   * With interrupt, a run that reaches its limit pauses at its tool calls and says what it spent
   * against which limit. A restart that does not approve stops the run; an approval continues it
   * with a fresh count.
   */
  @Test
  void interruptPausesTheRun() {
    AtomicInteger calls = new AtomicInteger();
    Tool<Map<String, Object>, Object> step = defineStepModel(2, calls, null);
    BudgetMiddleware b = budget(inputTokens(15), null, true);

    ModelResponse resp = generate("test/steps", o -> o.prompt("go").tools(List.of(step)), b);
    List<Part> interrupts = resp.getInterrupts();
    assertEquals(FinishReason.INTERRUPTED, resp.getFinishReason());
    assertEquals(1, interrupts.size());
    InterruptedCall<BudgetMiddleware.Decision> hold =
        BudgetMiddleware.interrupted(interrupts.get(0))
            .orElseThrow(() -> new AssertionError("not the budget's hold"));
    BudgetMiddleware.Exceeded data = BudgetMiddleware.exceeded(hold);
    assertEquals(20.0, data.spent.get("inputTokens"));
    assertEquals(15.0, data.limit.get("inputTokens"));
    List<Message> history = resp.getMessages();

    // A restart that does not approve stops the run before the held call runs, with the reason.
    int before = calls.get();
    BudgetExceededException refused =
        assertThrows(
            BudgetExceededException.class,
            () ->
                generate(
                    "test/steps",
                    o ->
                        o.messages(history)
                            .tools(List.of(step))
                            .resume(
                                ResumeOptions.builder()
                                    .part(
                                        hold.restart(
                                            BudgetMiddleware.Decision.refuse("too costly")))
                                    .build()),
                    b));
    assertTrue(refused.getMessage().contains("too costly"), refused.getMessage());
    assertEquals(FinishReason.ABORTED, refused.getResponse().getFinishReason());
    assertEquals(before, calls.get());

    // A response stands in for the held call's output and refuses the hold, saying so.
    BudgetExceededException responded =
        assertThrows(
            BudgetExceededException.class,
            () ->
                generate(
                    "test/steps",
                    o ->
                        o.messages(history)
                            .tools(List.of(step))
                            .resume(ResumeOptions.builder().part(hold.respond("stepped")).build()),
                    b));
    assertTrue(responded.getMessage().contains("answered with a response"));
    assertEquals(before, calls.get());

    // Approving the budget's own hold renews the limit.
    ModelResponse approved =
        generate(
            "test/steps",
            o ->
                o.messages(history)
                    .tools(List.of(step))
                    .resume(
                        ResumeOptions.builder()
                            .part(hold.restart(BudgetMiddleware.Decision.approve()))
                            .build()),
            b);
    assertEquals("done", approved.getText());
  }

  /**
   * With interrupt, a run that goes over while its tools run has no tool call left to hold, so it
   * stops at the next check, and says it could not pause.
   */
  @Test
  void interruptCannotPauseSpendInATool() {
    genkit.registerModel(
        model(
            "test/inner",
            (call, req) -> response(Message.model("checked"), 100),
            new ArrayList<>()));
    Tool<Map<String, Object>, Object> check =
        tool(
            "check",
            (ctx, in) ->
                ((ModelResponse)
                        genkit.generate(
                            GenerateOptions.<Void>builder()
                                .model("test/inner")
                                .prompt("check")
                                .build()))
                    .getText());
    genkit.registerModel(
        model(
            "test/outer",
            (call, req) -> response(toolCallMsg(req("check", null, map())), 10),
            new ArrayList<>()));

    BudgetExceededException e =
        assertThrows(
            BudgetExceededException.class,
            () ->
                generate(
                    "test/outer",
                    o -> o.prompt("go").tools(List.of(check)),
                    budget(inputTokens(50), null, true)));
    assertTrue(e.getMessage().contains("could not pause"), e.getMessage());
    assertNotNull(e.getResponse());
    assertTrue(
        e.getResponse().getInterrupts() == null || e.getResponse().getInterrupts().isEmpty());
  }

  /** The conversation crosses the wire between calls, as a client's would. */
  private static List<Message> overWire(List<Message> msgs) {
    return JsonUtils.getObjectMapper()
        .convertValue(
            JsonUtils.parseJson(JsonUtils.toJson(msgs)), new TypeReference<List<Message>>() {});
  }

  private static Part only(ModelResponse resp, java.util.function.Predicate<Part> claim) {
    assertNotNull(resp);
    assertEquals(FinishReason.INTERRUPTED, resp.getFinishReason());
    assertEquals(1, resp.getInterrupts().size());
    assertTrue(claim.test(resp.getInterrupts().get(0)), "unexpected interrupt kind");
    return resp.getInterrupts().get(0);
  }

  /**
   * A resume continues the run's count, so answering another middleware's hold cannot renew the
   * budget: only answering the budget's own hold does, and a new turn of the conversation starts
   * from zero.
   */
  @Test
  void countsARunAcrossResumes() {
    AtomicInteger calls = new AtomicInteger();
    Tool<Map<String, Object>, Object> step = defineStepModel(1, calls, null); // 10 tokens a call
    BudgetMiddleware[] b = {budget(inputTokens(10), null, true)};
    ToolApproval.ToolCallDecision approve = ToolApproval.ToolCallDecision.approve();
    java.util.function.Function<Consumer<GenerateOptions.Builder<Void>>, ModelResponse> gen =
        opts ->
            generate(
                "test/steps", o -> opts.accept(o.tools(List.of(step))), new ToolApproval(), b[0]);
    java.util.function.Predicate<Part> isApproval =
        p -> ToolApproval.toolApprovalInterrupted(p).isPresent();
    java.util.function.Predicate<Part> isBudget = p -> BudgetMiddleware.interrupted(p).isPresent();

    // The first turn spends the whole budget, and the approval gate holds its call before the
    // budget sees it.
    ModelResponse resp = gen.apply(o -> o.prompt("go"));
    InterruptedCall<ToolApproval.ToolCallDecision> approval =
        ToolApproval.toolApprovalInterrupted(only(resp, isApproval)).orElseThrow();

    // Approving it resumes the same run, still over its limit: the budget holds the call instead
    // of letting it run on a fresh count.
    List<Message> history = overWire(resp.getMessages());
    Part approvalRestart = approval.restart(approve);
    resp =
        gen.apply(
            o -> o.messages(history).resume(ResumeOptions.builder().part(approvalRestart).build()));
    Part held = only(resp, isBudget);
    assertEquals(1, calls.get(), "the approval must not buy another turn");

    // Approving the budget renews it, and the run finishes. The approval crosses the wire too, so
    // the budget reads it by its JSON shape.
    Part approved =
        overWire(
                List.of(
                    new Message(
                        Role.MODEL,
                        List.of(
                            BudgetMiddleware.interrupted(held)
                                .orElseThrow()
                                .restart(BudgetMiddleware.Decision.approve())))))
            .get(0)
            .getContent()
            .get(0);
    List<Message> history2 = overWire(resp.getMessages());
    resp =
        gen.apply(o -> o.messages(history2).resume(ResumeOptions.builder().part(approved).build()));
    assertEquals("done", resp.getText());

    // A new turn is a new run, whatever the last one spent.
    List<Message> history3 = overWire(resp.getMessages());
    history3.add(Message.user("again"));
    gen.apply(o -> o.messages(history3));

    // Without interrupt, the approved call runs and the run stops before the model call it would
    // lead to.
    calls.set(0);
    b[0] = budget(inputTokens(10));
    resp = gen.apply(o -> o.prompt("go"));
    Part approval2 =
        ToolApproval.toolApprovalInterrupted(only(resp, isApproval)).orElseThrow().restart(approve);
    List<Message> history4 = overWire(resp.getMessages());
    assertThrows(
        BudgetExceededException.class,
        () ->
            gen.apply(
                o -> o.messages(history4).resume(ResumeOptions.builder().part(approval2).build())));
    assertEquals(1, calls.get());
  }

  @Test
  void flattensUsageByJsonName() {
    Usage u = inputTokens(3);
    u.setCustom(new LinkedHashMap<>(Map.of("cost", 0.25)));
    Map<String, Double> f = BudgetMiddleware.usageFields(u);
    assertEquals(3.0, f.get("inputTokens"));
    assertEquals(0.25, f.get("custom.cost"));
    assertEquals(Map.of(), BudgetMiddleware.usageFields(null));
  }
}
