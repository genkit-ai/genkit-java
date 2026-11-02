/*
 * Copyright 2026 Google LLC
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.InterruptedCall;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolFailException;
import com.google.genkit.ai.ToolInterruptException;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.SoftToolErrorPolicy;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.ai.middleware.ToolParams;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Tests middleware-owned interrupts, restarts, and tool error policies in the generate loop. */
class MiddlewareInterruptTest {

  private static void model(Genkit genkit, Function<ModelRequest, Message> fn) {
    genkit.registerModel(
        new Model() {
          @Override
          public String getName() {
            return "test/model";
          }

          @Override
          public ModelInfo getInfo() {
            return new ModelInfo();
          }

          @Override
          public ModelResponse run(ActionContext ctx, ModelRequest request) {
            return run(ctx, request, null);
          }

          @Override
          public ModelResponse run(
              ActionContext ctx, ModelRequest request, Consumer<ModelResponseChunk> cb) {
            ModelResponse resp =
                new ModelResponse(List.of(new Candidate(fn.apply(request), FinishReason.STOP)));
            resp.setFinishReason(FinishReason.STOP);
            resp.setRequest(request);
            return resp;
          }
        });
  }

  /** Asks for the named tool until a tool message is in the request, then answers "done". */
  private static Function<ModelRequest, Message> askOnce(String tool) {
    return req -> {
      boolean answered = req.getMessages().stream().anyMatch(m -> m.getRole() == Role.TOOL);
      if (answered) {
        return Message.model("done");
      }
      ToolRequest r = new ToolRequest(tool, Map.of());
      r.setRef("1");
      return new Message(Role.MODEL, List.of(Part.toolRequest(r)));
    };
  }

  private static Tool<Map<String, Object>, String> tool(
      String name, Function<ActionContext, String> fn) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    Class<Map<String, Object>> cls = (Class) Map.class;
    return Tool.<Map<String, Object>, String>builder()
        .name(name)
        .description(name)
        .inputClass(cls)
        .handler((ctx, in) -> fn.apply(ctx))
        .build();
  }

  /** A middleware that holds every call until a restart answers it with {"ok": true}. */
  private static class Gate extends BaseGenerationMiddleware {
    final String name;
    final List<String> seen = new ArrayList<>();

    Gate(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public GenerationMiddleware newInstance() {
      return this;
    }

    @Override
    public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next) {
      seen.add(
          (params.isReleased() ? "released" : "fresh") + ":" + String.valueOf(ctx.getResumed()));
      if (params.isReleased()) {
        return next.apply(ctx, params);
      }
      if (ctx.getResumed() instanceof Map
          && Boolean.TRUE.equals(((Map<?, ?>) ctx.getResumed()).get("ok"))) {
        return next.apply(ctx, params);
      }
      throw new ToolInterruptException(Map.of("gate", name));
    }
  }

  private static ModelResponse generate(
      Genkit genkit,
      Tool<?, ?> tool,
      List<GenerationMiddleware> use,
      List<Message> history,
      Part... resume) {
    GenerateOptions.Builder<Object> b =
        GenerateOptions.builder().model("test/model").tools(List.of(tool)).use(use);
    if (history == null) {
      b.prompt("go");
    } else {
      b.messages(history);
      ResumeOptions.Builder r = ResumeOptions.builder();
      for (Part p : resume) {
        r.part(p);
      }
      b.resume(r.build());
    }
    return genkit.generate(b.build());
  }

  @Test
  void holdRecordsTheStageThatRaisedIt() {
    Genkit genkit = Genkit.builder().build();
    model(genkit, askOnce("t"));
    Gate outer = new Gate("test/outer");
    Gate inner = new Gate("test/inner");
    ModelResponse resp = generate(genkit, tool("t", ctx -> "ran"), List.of(outer, inner), null);

    Part held = resp.getInterrupts().get(0);
    // The outer gate holds first, so the inner one never runs.
    assertEquals("test/outer", held.getMetadata().get(InterruptedCall.INTERRUPTED_BY_KEY));
    assertNull(held.getMetadata().get(InterruptedCall.RELEASED_BY_KEY));
    assertTrue(InterruptedCall.ofMiddleware("test/outer", held).isPresent());
    assertFalse(InterruptedCall.ofMiddleware("test/inner", held).isPresent());
  }

  @Test
  void restartReachesOnlyTheStageThatAskedAndReleasedStagesPassThrough() {
    Genkit genkit = Genkit.builder().build();
    model(genkit, askOnce("t"));
    Gate outer = new Gate("test/outer");
    Gate inner = new Gate("test/inner");
    List<Object> toolResumes = new ArrayList<>();
    Tool<?, ?> t =
        tool(
            "t",
            ctx -> {
              toolResumes.add(ctx.getResumed());
              return "ran";
            });
    List<GenerationMiddleware> use = List.of(outer, inner);

    ModelResponse r1 = generate(genkit, t, use, null);
    Part held = r1.getInterrupts().get(0);
    InterruptedCall<Map<String, Object>> call =
        InterruptedCall.<Map<String, Object>>ofMiddleware("test/outer", held).orElseThrow();

    ModelResponse r2 = generate(genkit, t, use, r1.getMessages(), call.restart(Map.of("ok", true)));
    // The outer gate let it through, then the inner one held it.
    Part held2 = r2.getInterrupts().get(0);
    assertEquals(FinishReason.INTERRUPTED, r2.getFinishReason());
    assertEquals("test/inner", held2.getMetadata().get(InterruptedCall.INTERRUPTED_BY_KEY));
    assertEquals(List.of("test/outer"), held2.getMetadata().get(InterruptedCall.RELEASED_BY_KEY));

    InterruptedCall<Map<String, Object>> call2 =
        InterruptedCall.<Map<String, Object>>ofMiddleware("test/inner", held2).orElseThrow();
    ModelResponse r3 =
        generate(genkit, t, use, r2.getMessages(), call2.restart(Map.of("ok", true)));
    assertEquals("done", r3.getText());
    // The outer gate saw the second restart as released; the tool never saw any answer.
    assertTrue(outer.seen.contains("released:null"), outer.seen.toString());
    assertEquals(1, toolResumes.size());
    assertNull(toolResumes.get(0));
  }

  @Test
  void repeatedMiddlewareNamesGetSuffixes() {
    Genkit genkit = Genkit.builder().build();
    model(genkit, askOnce("t"));
    Gate pass =
        new Gate("test/gate") {
          @Override
          public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next) {
            return next.apply(ctx, params);
          }
        };
    ModelResponse resp =
        generate(genkit, tool("t", ctx -> "ran"), List.of(pass, new Gate("test/gate")), null);
    Part held = resp.getInterrupts().get(0);
    assertEquals("test/gate#2", held.getMetadata().get(InterruptedCall.INTERRUPTED_BY_KEY));
    assertEquals(List.of("test/gate"), held.getMetadata().get(InterruptedCall.RELEASED_BY_KEY));
    assertTrue(InterruptedCall.ofMiddleware("test/gate", held).isPresent());
  }

  @Test
  void restartWithoutRecordedStagesReachesEveryStage() {
    Genkit genkit = Genkit.builder().build();
    model(genkit, askOnce("t"));
    List<Object> toolResumes = new ArrayList<>();
    Tool<Map<String, Object>, String> t =
        tool(
            "t",
            ctx -> {
              toolResumes.add(ctx.getResumed());
              if (!ctx.isResumed()) {
                throw new ToolInterruptException();
              }
              return "ran";
            });
    ModelResponse r1 = generate(genkit, t, List.of(), null);
    Part asked = r1.getInterrupts().get(0);
    assertNull(asked.getMetadata().get(InterruptedCall.INTERRUPTED_BY_KEY));

    ModelResponse r2 =
        generate(genkit, t, List.of(), r1.getMessages(), t.restart(asked, Map.of("yes", true)));
    assertEquals("done", r2.getText());
    assertEquals(Map.of("yes", true), toolResumes.get(1));
  }

  @Test
  void bareRestartIsStillAResume() {
    Genkit genkit = Genkit.builder().build();
    model(genkit, askOnce("t"));
    List<Object> toolResumes = new ArrayList<>();
    Tool<Map<String, Object>, String> t =
        tool(
            "t",
            ctx -> {
              toolResumes.add(ctx.getResumed());
              if (!ctx.isResumed()) {
                throw new ToolInterruptException();
              }
              return "ran";
            });
    ModelResponse r1 = generate(genkit, t, List.of(), null);
    Part asked = r1.getInterrupts().get(0);
    // A restart built by hand, with no resume metadata.
    ModelResponse r2 =
        generate(genkit, t, List.of(), r1.getMessages(), Part.toolRequest(asked.getToolRequest()));
    assertEquals("done", r2.getText());
    assertEquals(Boolean.TRUE, toolResumes.get(1));
  }

  @Test
  void toolFailAnswersTheCall() {
    Genkit genkit = Genkit.builder().build();
    List<Part> seen = new ArrayList<>();
    model(
        genkit,
        req -> {
          Message last = req.getMessages().get(req.getMessages().size() - 1);
          if (last.getRole() == Role.TOOL) {
            seen.addAll(last.getContent());
          }
          return askOnce("t").apply(req);
        });
    ModelResponse resp =
        generate(
            genkit,
            tool(
                "t",
                ctx -> {
                  throw new ToolFailException("try another id");
                }),
            List.of(),
            null);
    assertEquals("done", resp.getText());
    assertEquals(Map.of("error", "try another id"), seen.get(0).getToolResponse().getOutput());
    assertEquals(true, seen.get(0).getMetadata().get("isError"));
  }

  /** A soft-error policy covering the named tools. */
  private static final class Policy extends BaseGenerationMiddleware
      implements SoftToolErrorPolicy {
    final List<String> tools;
    final boolean strict;

    Policy(boolean strict, String... tools) {
      this.strict = strict;
      this.tools = List.of(tools);
    }

    @Override
    public String name() {
      return "test/policy";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return this;
    }

    @Override
    public boolean coversToolErrors(String toolName) {
      return tools.contains(toolName);
    }

    @Override
    public boolean strictToolErrors() {
      return strict;
    }
  }

  @Test
  void toolErrorPolicies() {
    Tool<Map<String, Object>, String> failing =
        tool(
            "t",
            ctx -> {
              throw new IllegalStateException("boom");
            });

    // No policy: the error is returned to the model.
    Genkit g1 = Genkit.builder().build();
    model(g1, askOnce("t"));
    assertEquals("done", generate(g1, failing, List.of(), null).getText());

    // A strict policy that does not cover the tool: the generation fails.
    Genkit g2 = Genkit.builder().build();
    model(g2, askOnce("t"));
    GenkitException err =
        assertThrows(
            GenkitException.class,
            () -> generate(g2, failing, List.of(new Policy(true, "other")), null));
    assertTrue(err.getMessage().contains("tool \"t\" failed: boom"), err.getMessage());

    // A covering policy: the model reads the tool's own words.
    Genkit g3 = Genkit.builder().build();
    List<Part> seen = new ArrayList<>();
    model(
        g3,
        req -> {
          Message last = req.getMessages().get(req.getMessages().size() - 1);
          if (last.getRole() == Role.TOOL) {
            seen.addAll(last.getContent());
          }
          return askOnce("t").apply(req);
        });
    generate(g3, failing, List.of(new Policy(true, "t")), null);
    assertEquals(Map.of("error", "boom"), seen.get(0).getToolResponse().getOutput());
  }
}
