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

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.OutputConfig;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionDesc;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.DynamicPlugin;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Tests the output handling of a generate call and the resolution of dynamic models. */
class GenerateOutputTest {

  /** A model that answers every request with the reply its function gives. */
  private static Model replying(String name, Function<ModelRequest, String> reply) {
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
      public ModelResponse run(ActionContext ctx, ModelRequest request) {
        Candidate candidate = new Candidate(Message.model(reply.apply(request)), FinishReason.STOP);
        ModelResponse response = new ModelResponse(new ArrayList<>(List.of(candidate)));
        response.setFinishReason(FinishReason.STOP);
        return response;
      }
    };
  }

  @Test
  void enumFormatRewritesTheTextAsTheValue() {
    Genkit genkit = Genkit.builder().build();
    genkit.registerModel(replying("test/enum", req -> "  \"blue\"  \n"));
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.builder()
                .model("test/enum")
                .prompt("Pick a color.")
                .output(OutputConfig.enums("red", "green", "blue"))
                .build());
    assertEquals("blue", resp.getText());
  }

  @Test
  void enumFormatRejectsAnotherValue() {
    Genkit genkit = Genkit.builder().build();
    genkit.registerModel(replying("test/enum", req -> "yellow"));
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                genkit.generate(
                    GenerateOptions.builder()
                        .model("test/enum")
                        .prompt("Pick a color.")
                        .output(OutputConfig.enums("red", "green", "blue"))
                        .build()));
    StringBuilder messages = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      messages.append(t.getMessage());
    }
    assertTrue(messages.toString().contains("not in list of valid enums"), messages.toString());
  }

  public static class Single {
    public Inner answer;
  }

  public static class Inner {
    public String value;
  }

  @Test
  void singleKeyOutputDeclaredByTheSchemaIsKept() {
    // A one-field output type is the model's answer as it is, not a wrapper to unwrap.
    Genkit genkit = Genkit.builder().build();
    genkit.registerModel(replying("test/json", req -> "{\"answer\":{\"value\":\"x\"}}"));
    Single out =
        genkit.generate(
            GenerateOptions.builder()
                .model("test/json")
                .prompt("Answer.")
                .outputClass(Single.class)
                .build());
    assertNotNull(out.answer);
    assertEquals("x", out.answer.value);

    // A wrapper the schema does not declare is still unwrapped.
    genkit.registerModel(
        replying("test/wrapped", req -> "{\"wrapper\":{\"answer\":{\"value\":\"y\"}}}"));
    out =
        genkit.generate(
            GenerateOptions.builder()
                .model("test/wrapped")
                .prompt("Answer.")
                .outputClass(Single.class)
                .build());
    assertEquals("y", out.answer.value);
  }

  /** A plugin that serves any model under its name, as a server with many models does. */
  static final class AnyModelPlugin implements DynamicPlugin {
    final List<String> resolved = new ArrayList<>();

    @Override
    public String getName() {
      return "anymodel";
    }

    @Override
    public List<Action<?, ?, ?>> init() {
      return List.of();
    }

    @Override
    public List<ActionDesc> listActions() {
      return List.of(replying("anymodel/listed", req -> "hi").getDesc());
    }

    @Override
    public Action<?, ?, ?> resolveAction(ActionType type, String name) {
      if (type != ActionType.MODEL || !name.startsWith("anymodel/")) {
        return null;
      }
      resolved.add(name);
      return replying(name, req -> "from " + name);
    }
  }

  @Test
  void dynamicPluginResolvesModelsByFullName() {
    AnyModelPlugin plugin = new AnyModelPlugin();
    Genkit genkit = Genkit.builder().plugin(plugin).build();
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.builder().model("anymodel/vendor/model-1").prompt("hi").build());
    assertEquals("from anymodel/vendor/model-1", resp.getText());
    // The resolved model is kept for later lookups.
    genkit.generate(
        GenerateOptions.builder().model("anymodel/vendor/model-1").prompt("hi").build());
    assertEquals(List.of("anymodel/vendor/model-1"), plugin.resolved);
    assertSame(plugin, genkit.getRegistry().lookupPlugin("anymodel"));
    assertTrue(
        genkit.getRegistry().listActions(ActionType.MODEL).stream()
            .anyMatch(a -> a.getName().equals("anymodel/listed")));
    assertThrows(GenkitException.class, () -> genkit.getModel("other/model"));
  }
}
