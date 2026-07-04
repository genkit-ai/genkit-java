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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionRunResult;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test proving the full execution-context propagation chain that powers the Dev UI
 * "Execution context" panel:
 *
 * <pre>
 * reflection request body context
 *   → ActionContext.context
 *   → (run path) withSpanContext keeps it
 *   → AgentFnContext.context()
 *   → GenkitBeta.buildAgentFn → GenerateOptions.context
 *   → Genkit.generate's tool-executing ActionContext
 *   → Tool handler reads ctx.getContext()
 * </pre>
 */
class ExecutionContextTest {

  private static final Map<String, Object> AUTH_CONTEXT = Map.of("auth", Map.of("user", "alice"));

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static Genkit experimentalGenkit() {
    return new Genkit(GenkitOptions.builder().experimental(true).build());
  }

  private static JsonNode initJson() {
    return JsonUtils.toJsonNode(new com.google.genkit.ai.agent.AgentInit<Map<String, Object>>());
  }

  private static BufferedInputSource<JsonNode> inputSourceWith(String userText) {
    BufferedInputSource<JsonNode> src = new BufferedInputSource<>();
    src.offer(JsonUtils.toJsonNode(AgentInput.builder().message(Message.user(userText)).build()));
    src.end();
    return src;
  }

  /**
   * A fake model that, on the first turn, requests the {@code whoami} tool; on the next turn (once
   * a TOOL message carrying the tool's output is present in the history) it echoes the tool output
   * as its final text reply. This drives the real generate tool-execution loop so we can prove the
   * tool observes the injected execution context.
   */
  private static Model toolCallingModel(String name, String toolName) {
    return new Model() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public ModelInfo getInfo() {
        return new ModelInfo();
      }

      @Override
      public boolean supportsStreaming() {
        return true;
      }

      @Override
      public ModelResponse run(ActionContext ctx, ModelRequest request) {
        return run(ctx, request, null);
      }

      @Override
      public ModelResponse run(
          ActionContext ctx, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
        // Look for a TOOL message carrying the tool output from a previous turn.
        String toolOutput = findToolOutput(request);
        if (toolOutput != null) {
          String reply = "tool said: " + toolOutput;
          if (streamCallback != null) {
            ModelResponseChunk chunk = new ModelResponseChunk();
            chunk.setContent(List.of(Part.text(reply)));
            streamCallback.accept(chunk);
          }
          Candidate candidate = new Candidate(Message.model(reply), FinishReason.STOP);
          ModelResponse response = new ModelResponse(List.of(candidate));
          response.setFinishReason(FinishReason.STOP);
          response.setRequest(request);
          return response;
        }

        // First turn: ask to call the tool.
        Part toolRequestPart = Part.toolRequest(new ToolRequest(toolName, Map.of()));
        Message assistant = new Message(Role.MODEL, List.of(toolRequestPart));
        Candidate candidate = new Candidate(assistant, FinishReason.STOP);
        ModelResponse response = new ModelResponse(List.of(candidate));
        response.setFinishReason(FinishReason.STOP);
        response.setRequest(request);
        return response;
      }

      private String findToolOutput(ModelRequest request) {
        if (request.getMessages() == null) {
          return null;
        }
        for (Message m : request.getMessages()) {
          if (m.getRole() == Role.TOOL && m.getContent() != null) {
            for (Part p : m.getContent()) {
              ToolResponse tr = p.getToolResponse();
              if (tr != null && tr.getOutput() != null) {
                return String.valueOf(tr.getOutput());
              }
            }
          }
        }
        return null;
      }
    };
  }

  // ── Layer 1: reflection ActionContext → AgentFnContext (custom agent) ─────────

  @Test
  void customAgentReadsContextFromAgentFnContext() throws Exception {
    Genkit genkit = experimentalGenkit();

    CustomAgentConfig<Map<String, Object>> config =
        CustomAgentConfig.<Map<String, Object>>builder().name("ctxAgent").build();

    // The AgentFn reads the injected execution context off its AgentFnContext and returns the
    // nested auth.user value as the turn's message.
    Agent<Map<String, Object>> agent =
        genkit
            .beta()
            .defineCustomAgent(
                config,
                (runner, ctx) -> {
                  assertNotNull(ctx.context(), "AgentFnContext must carry the run ActionContext");
                  Map<String, Object> userContext = ctx.context().getContext();
                  assertNotNull(userContext, "user context must propagate to the agent");
                  @SuppressWarnings("unchecked")
                  Map<String, Object> auth = (Map<String, Object>) userContext.get("auth");
                  return AgentResult.builder()
                      .message(Message.model(String.valueOf(auth.get("user"))))
                      .build();
                });

    // Simulate the reflection server: build an ActionContext WITH the user context, then drive the
    // bidi action through runBidiJsonWithTelemetry (which internally calls withSpanContext).
    ActionContext ctx =
        ActionContext.builder().registry(genkit.getRegistry()).context(AUTH_CONTEXT).build();

    ActionRunResult<JsonNode> result =
        agent.runBidiJsonWithTelemetry(ctx, initJson(), inputSourceWith("hi"), null);

    JsonNode out = result.getResult();
    assertEquals("alice", out.get("message").get("content").get(0).get("text").asText());
  }

  // ── Layer 2: full chain → generate → tool reads ctx.getContext() ──────────────

  @Test
  void toolObservesExecutionContextDuringAgentGenerate() throws Exception {
    Genkit genkit = experimentalGenkit();
    genkit.registerModel(toolCallingModel("toolModel", "whoami"));

    // Tool handler reads the execution context off its ActionContext.
    Tool<Map<String, Object>, String> whoami =
        genkit.defineTool(
            "whoami",
            "returns the authenticated user from the execution context",
            (ActionContext ctx, Map<String, Object> input) -> {
              Map<String, Object> userContext = ctx.getContext();
              if (userContext == null) {
                return "NO_CONTEXT";
              }
              @SuppressWarnings("unchecked")
              Map<String, Object> auth = (Map<String, Object>) userContext.get("auth");
              return auth == null ? "NO_AUTH" : String.valueOf(auth.get("user"));
            },
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            String.class);

    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("toolAgent")
            .model("toolModel")
            .system("You are helpful.")
            .tools(List.of(whoami))
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().defineAgent(config);

    ActionContext ctx =
        ActionContext.builder().registry(genkit.getRegistry()).context(AUTH_CONTEXT).build();

    ActionRunResult<JsonNode> result =
        agent.runBidiJsonWithTelemetry(ctx, initJson(), inputSourceWith("who am I?"), null);

    JsonNode out = result.getResult();
    // The model echoes the tool output; the tool output is the auth.user from the execution
    // context. Proves the full chain delivered "alice" all the way into the tool handler.
    assertEquals("tool said: alice", out.get("message").get("content").get(0).get("text").asText());
  }
}
