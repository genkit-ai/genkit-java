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

package com.google.genkit.samples;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolInterruptException;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.ai.middleware.ToolParams;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sample that tests the middleware lifecycle during interrupt restart.
 *
 * <p>This validates Pavel's feedback on PR #125: when restarting an interrupted tool, the
 * middleware must follow the correct nested lifecycle. Specifically:
 *
 * <pre>
 * === Initial generate call (triggers tool4 interrupt mid-flow) ===
 *
 * generate - 1
 *     model
 *     tool1
 *     tool2
 *     tool3
 *     generate - 2
 *         model
 *         tool4  // <--- INTERRUPT
 *
 * === Restart of tool4 (correct lifecycle) ===
 *
 * generate - 1          // restart generate call
 *     tool4             // RESTART (through wrapTool middleware)
 *     generate - 2      // nested - NOT flat!
 *         model
 *         // done
 * </pre>
 *
 * <p>The WRONG (naive) implementation would flatten this to:
 *
 * <pre>
 * generate - 1
 *     tool4   // RESTART
 *     model   // flat - no nested generate
 *     // done
 * </pre>
 *
 * <p>This sample uses a lifecycle-tracking middleware that records every hook invocation, then
 * verifies the correct nesting after restart.
 *
 * <p>To run:
 *
 * <ol>
 *   <li>Set the OPENAI_API_KEY environment variable
 *   <li>Run: mvn exec:java
 *       -Dexec.mainClass="com.google.genkit.samples.MiddlewareInterruptRestartSample" -pl
 *       samples/middleware-v2
 * </ol>
 */
public class MiddlewareInterruptRestartSample {

  // =========================================================================
  // Lifecycle-tracking middleware
  // =========================================================================

  /**
   * Middleware that records every hook invocation as a structured log entry. Used to verify the
   * correct nesting of generate/model/tool calls.
   */
  static class LifecycleTracker extends BaseGenerationMiddleware {

    private final List<String> log;
    private final AtomicInteger depth = new AtomicInteger(0);

    LifecycleTracker(List<String> log) {
      this.log = log;
    }

    @Override
    public String name() {
      return "lifecycle-tracker";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return new LifecycleTracker(log);
    }

    private String indent() {
      return "    ".repeat(depth.get());
    }

    @Override
    public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
        throws GenkitException {
      String entry = indent() + "generate - " + (params.getIteration() + 1);
      log.add(entry);
      System.out.println(entry);
      depth.incrementAndGet();
      try {
        return next.apply(ctx, params);
      } finally {
        depth.decrementAndGet();
      }
    }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
        throws GenkitException {
      String entry = indent() + "model";
      log.add(entry);
      System.out.println(entry);
      return next.apply(ctx, params);
    }

    @Override
    public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next)
        throws GenkitException {
      String toolName = params.getRequest().getName();
      String entry = indent() + toolName;
      log.add(entry);
      System.out.println(entry);
      return next.apply(ctx, params);
    }
  }

  // =========================================================================
  // Data classes
  // =========================================================================

  public static class ActionInput {
    private String action;

    public ActionInput() {}

    public String getAction() {
      return action;
    }

    public void setAction(String action) {
      this.action = action;
    }
  }

  // =========================================================================
  // Main
  // =========================================================================

  public static void main(String[] args) throws Exception {
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().devMode(false).build())
            .plugin(OpenAIPlugin.create())
            .build();

    // Define regular tools (tool1, tool2, tool3) with object input schemas (required by OpenAI)
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, String> tool1 =
        genkit.defineTool(
            "tool1",
            "First tool - runs task 1",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, input) -> "tool1-result");

    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, String> tool2 =
        genkit.defineTool(
            "tool2",
            "Second tool - runs task 2",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, input) -> "tool2-result");

    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, String> tool3 =
        genkit.defineTool(
            "tool3",
            "Third tool - runs task 3",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, input) -> "tool3-result");

    // Define tool4 as a restartable tool that interrupts on first call.
    // On restart (second call), it succeeds. This simulates a tool that needs
    // human confirmation before proceeding.
    final java.util.concurrent.atomic.AtomicBoolean tool4HasInterrupted =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, String> tool4 =
        genkit.defineTool(
            "tool4",
            "Fourth tool - requires confirmation, interrupts on first call",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("action", Map.of("type", "string")),
                "additionalProperties",
                false),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, input) -> {
              if (!tool4HasInterrupted.getAndSet(true)) {
                // First call: interrupt to request confirmation
                throw new ToolInterruptException(
                    Map.of("reason", "needs confirmation", "action", String.valueOf(input)));
              }
              // Restart call: proceed normally
              return "tool4-confirmed-result";
            });

    // Shared log across initial + restart calls
    List<String> lifecycleLog = Collections.synchronizedList(new ArrayList<>());

    GenerationMiddleware tracker = new LifecycleTracker(lifecycleLog);

    System.out.println("===========================================================");
    System.out.println("  Middleware Interrupt Restart Lifecycle Test");
    System.out.println("===========================================================");
    System.out.println();

    // ---------------------------------------------------------------
    // Step 1: Initial generate - model should call tools, tool4 interrupts
    // ---------------------------------------------------------------
    // Note: The actual model call pattern depends on the LLM response.
    // We ask it to call all 4 tools. The first 3 succeed, tool4 interrupts.
    System.out.println(">>> Step 1: Initial generate (expecting tool4 to interrupt)");
    System.out.println("-----------------------------------------------------------");

    ModelResponse response =
        genkit.generate(
            GenerateOptions.<Void>builder()
                .model("openai/gpt-4o-mini")
                .system(
                    "You are a task executor. When asked to run all tasks, you MUST call all 4 "
                        + "tools in order: tool1, tool2, tool3, tool4. Call them all at once.")
                .prompt("Run all tasks now.")
                .tools(List.of(tool1, tool2, tool3, tool4))
                .use(tracker)
                .maxTurns(5)
                .build());

    System.out.println();
    if (response.isInterrupted()) {
      System.out.println(">>> tool4 interrupted as expected!");
      System.out.println();

      // ---------------------------------------------------------------
      // Step 2: Restart tool4 with middleware - should fire wrapTool + nested wrapGenerate
      // ---------------------------------------------------------------
      System.out.println(">>> Step 2: Restart tool4 (expecting nested lifecycle)");
      System.out.println("-----------------------------------------------------------");

      // Find the interrupted tool request
      Part interruptPart = response.getInterrupts().get(0);
      ToolRequest interruptedRequest = interruptPart.getToolRequest();

      // Create restart request (re-execute tool4 with same input)
      ToolRequest restartRequest = new ToolRequest();
      restartRequest.setName(interruptedRequest.getName());
      restartRequest.setRef(interruptedRequest.getRef());
      restartRequest.setInput(interruptedRequest.getInput());

      ModelResponse resumedResponse =
          genkit.generate(
              GenerateOptions.<Void>builder()
                  .model("openai/gpt-4o-mini")
                  .messages(response.getMessages())
                  .tools(List.of(tool1, tool2, tool3, tool4))
                  .use(tracker)
                  .resume(ResumeOptions.builder().restart(restartRequest).build())
                  .maxTurns(5)
                  .build());

      System.out.println();
      System.out.println(">>> Restart completed. Final response:");
      System.out.println(resumedResponse.getText());
    } else {
      System.out.println(">>> No interrupt occurred (model didn't call tool4).");
      System.out.println(">>> Response: " + response.getText());
    }

    // ---------------------------------------------------------------
    // Print full lifecycle log
    // ---------------------------------------------------------------
    System.out.println();
    System.out.println("===========================================================");
    System.out.println("  Full Lifecycle Log");
    System.out.println("===========================================================");
    for (String entry : lifecycleLog) {
      System.out.println(entry);
    }

    System.out.println();
    System.out.println("===========================================================");
    System.out.println("  Verification");
    System.out.println("===========================================================");

    // Verify that the restart lifecycle shows nested generate calls
    // After restart, we expect to see at minimum:
    //   generate - 1     (restart iteration)
    //     tool4           (through wrapTool)
    //     generate - 2    (nested, NOT flat)
    //       model         (model call after tool4 completes)
    boolean foundRestartGenerate = false;
    boolean foundRestartTool = false;
    boolean foundNestedGenerate = false;
    boolean foundNestedModel = false;

    // Look at the restart portion of the log (after the initial call)
    boolean inRestartPhase = false;
    for (String entry : lifecycleLog) {
      // The restart phase starts with the second "generate - 1"
      if (!inRestartPhase && entry.trim().equals("generate - 1")) {
        if (foundRestartGenerate) {
          // This is the second "generate - 1", so we're in restart phase
          inRestartPhase = true;
          foundRestartGenerate = true;
          continue;
        }
        foundRestartGenerate = true;
      }
      if (inRestartPhase) {
        if (entry.trim().equals("tool4")) {
          foundRestartTool = true;
        }
        if (entry.trim().equals("generate - 2")) {
          foundNestedGenerate = true;
        }
        if (foundNestedGenerate && entry.trim().equals("model")) {
          foundNestedModel = true;
        }
      }
    }

    System.out.println("Restart fires wrapTool for tool4: " + foundRestartTool);
    System.out.println("Restart has nested generate:      " + foundNestedGenerate);
    System.out.println("Nested generate calls model:      " + foundNestedModel);

    if (foundRestartTool && foundNestedGenerate && foundNestedModel) {
      System.out.println();
      System.out.println("PASS: Restart follows correct nested lifecycle!");
    } else {
      System.out.println();
      System.out.println("FAIL: Restart lifecycle is flat (naive implementation).");
      System.out.println("   Expected: generate -> tool4 -> generate -> model");
    }
  }
}
