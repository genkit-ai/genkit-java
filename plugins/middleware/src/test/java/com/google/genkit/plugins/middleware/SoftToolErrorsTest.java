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

package com.google.genkit.plugins.middleware;

import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.errorMessage;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.isToolError;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.registerReplyModel;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.request;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.toolRequests;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.vTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.Genkit;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolFailException;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SoftToolErrorsTest {

  /** A tool that always fails with {@code message}. */
  private static Tool<MiddlewareTestSupport.VInput, String> failingTool(
      String name, String message) {
    return vTool(
        name,
        "always fails",
        in -> {
          throw new IllegalStateException(message);
        });
  }

  /**
   * Registers a model that requests the named tools, one per turn, then answers "done". It records
   * every tool response it receives in {@code got}.
   */
  private static void toolLoopModel(Genkit genkit, List<Part> got, String... names) {
    int[] turn = {0};
    registerReplyModel(
        genkit,
        "test/loop",
        req -> {
          Message last = req.getMessages().get(req.getMessages().size() - 1);
          if (last.getRole() == Role.TOOL) {
            got.addAll(last.getContent());
          }
          if (turn[0] == names.length) {
            return Message.model("done");
          }
          turn[0]++;
          return toolRequests(request(names[turn[0] - 1], Map.of()));
        });
  }

  private static ModelResponse generate(
      Genkit genkit, List<Tool<?, ?>> tools, GenerationMiddleware... use) {
    return genkit.generate(
        GenerateOptions.builder().model("test/loop").prompt("go").tools(tools).use(use).build());
  }

  @Test
  void softToolErrorsReturnsErrorsOnEveryTurn() {
    Genkit genkit = Genkit.builder().build();
    List<Part> got = new ArrayList<>();
    toolLoopModel(genkit, got, "flaky", "missing", "flaky");

    ModelResponse resp =
        generate(genkit, List.of(failingTool("flaky", "upstream timeout")), new SoftToolErrors());
    assertEquals("done", resp.getText());
    assertEquals(3, got.size());
    String[] want = {"upstream timeout", "tool \"missing\" not found", "upstream timeout"};
    for (int i = 0; i < want.length; i++) {
      String msg = errorMessage(got.get(i));
      assertTrue(msg.contains(want[i]), "response " + i + " error = " + msg);
    }
    // The model reads the tool's own words.
    assertEquals("upstream timeout", errorMessage(got.get(0)));
  }

  @Test
  void softToolErrorsLimitedToListedTools() {
    Genkit genkit = Genkit.builder().build();
    List<Part> got = new ArrayList<>();
    toolLoopModel(genkit, got, "covered", "uncovered");

    GenkitException err =
        assertThrows(
            GenkitException.class,
            () ->
                generate(
                    genkit,
                    List.of(failingTool("covered", "boom"), failingTool("uncovered", "boom")),
                    SoftToolErrors.forTools("covered")));
    assertTrue(err.getMessage().contains("uncovered"), err.getMessage());
    assertEquals(1, got.size(), "want the covered tool's error alone");
    assertFalse(errorMessage(got.get(0)).isEmpty());
  }

  @Test
  void softToolErrorsCombineAcrossInstances() {
    Genkit genkit = Genkit.builder().build();
    List<Part> got = new ArrayList<>();
    toolLoopModel(genkit, got, "a", "b");

    generate(
        genkit,
        List.of(failingTool("a", "boom"), failingTool("b", "boom")),
        SoftToolErrors.forTools("a"),
        SoftToolErrors.forTools("b"));
    assertEquals(2, got.size());
  }

  @Test
  void softToolErrorsKeepsApprovalInterrupts() {
    Genkit genkit = Genkit.builder().build();
    List<Part> got = new ArrayList<>();
    toolLoopModel(genkit, got, "dangerous");

    ModelResponse resp =
        generate(genkit, List.of(vTool("dangerous")), new SoftToolErrors(), new ToolApproval());
    assertEquals(FinishReason.INTERRUPTED, resp.getFinishReason());
  }

  @Test
  void toolFailExceptionAnswersTheCallWithoutAPolicy() {
    Genkit genkit = Genkit.builder().build();
    List<Part> got = new ArrayList<>();
    toolLoopModel(genkit, got, "lookup");
    Tool<MiddlewareTestSupport.VInput, String> lookup =
        vTool(
            "lookup",
            "finds a record",
            in -> {
              throw new ToolFailException("no record named x; list the records first");
            });

    ModelResponse resp =
        genkit.generate(
            GenerateOptions.builder()
                .model("test/loop")
                .prompt("go")
                .tools(List.of(lookup))
                .build());
    assertEquals("done", resp.getText());
    assertTrue(isToolError(got.get(0)));
    assertEquals("no record named x; list the records first", errorMessage(got.get(0)));
  }

  @Test
  void hookErrorsStillFailTheGeneration() {
    Genkit genkit = Genkit.builder().build();
    List<Part> got = new ArrayList<>();
    toolLoopModel(genkit, got, "tool");
    GenerationMiddleware failingHook =
        new com.google.genkit.ai.middleware.BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "test/failingHook";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public Part wrapTool(
              com.google.genkit.core.ActionContext ctx,
              com.google.genkit.ai.middleware.ToolParams params,
              com.google.genkit.ai.middleware.ToolNext next) {
            throw new GenkitException("hook refused");
          }
        };
    assertThrows(
        GenkitException.class,
        () -> generate(genkit, List.of(vTool("tool")), new SoftToolErrors(), failingHook));
  }

  @Test
  void configCoversEveryToolWhenEmpty() {
    assertTrue(new SoftToolErrors().coversToolErrors("anything"));
    assertTrue(SoftToolErrors.forTools("a").coversToolErrors("a"));
    assertFalse(SoftToolErrors.forTools("a").coversToolErrors("b"));
  }
}
