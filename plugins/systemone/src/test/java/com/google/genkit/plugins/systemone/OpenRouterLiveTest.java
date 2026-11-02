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

package com.google.genkit.plugins.systemone;

import static com.google.genkit.plugins.systemone.StateBuilderTest.user;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.decide;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.messages;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.output;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.Genkit;
import com.google.genkit.ai.Document;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.OutputConfig;
import com.google.genkit.ai.Part;
import com.google.genkit.core.ActionDesc;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.plugins.systemone.TestTypes.Dept;
import com.google.genkit.plugins.systemone.TestTypes.GuidedDept;
import com.google.genkit.plugins.systemone.TestTypes.GuidedTriage;
import com.google.genkit.plugins.systemone.TestTypes.Triage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs jev and d1 through OpenRouter, the gateway an ordinary key can reach. Skipped unless
 * OPENROUTER_API_KEY is set.
 */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class OpenRouterLiveTest {

  private static final String MODEL = "openrouter-decisions/~typesafe/jev-latest";
  private static Genkit g;

  @BeforeAll
  static void start() {
    g = Genkit.builder().plugin(SystemOne.openRouter()).build();
  }

  private static void log(String format, Object... args) {
    System.out.printf(format + "%n", args);
  }

  @Test
  void decision() {
    // The documented call: the model, the state, and the type.
    ModelResponse resp =
        decide(
            g,
            MODEL,
            b ->
                b.messages(
                    List.of(
                        user(
                            Part.data(
                                Map.of(
                                    "ticket",
                                    "I was charged twice for one order and I need the duplicate"
                                        + " refunded today.",
                                    "account_tier",
                                    "business"))))));
    Triage out = output(resp, Triage.class);
    log("answers: %s", JsonUtils.toJson(out));
    log("info: %s, usage: %s", SystemOne.responseInfo(resp), JsonUtils.toJson(resp.getUsage()));

    assertEquals(Dept.BILLING, out.department().choice(), "billing for a double charge");
    double mass = out.department().probabilities().values().stream().mapToDouble(d -> d).sum();
    assertEquals(1, mass, 0.05, "probabilities sum to about 1");
    assertTrue(out.department().confidence() > 0 && out.department().confidence() <= 1);
    assertTrue(out.isUrgent().probability() >= 0.5, "is_urgent over 0.5 for \"today\"");
    assertTrue(out.frustration().score() >= 0 && out.frustration().score() <= 2);
    assertEquals(3, out.frustration().legend().size());
    assertNotNull(resp.getUsage());
    assertTrue(resp.getUsage().getInputTokens() > 0);
    assertFalse(SystemOne.responseInfo(resp).model().isEmpty(), "no resolved model version");
    assertTrue(resp.getUsage().getCustom().get("cost") > 0, "the gateway's cost");
  }

  @Test
  void decisionWithPreamble() {
    Triage out =
        output(
            decide(
                g,
                MODEL,
                b ->
                    b.system(
                            "The state is a support ticket from a business customer of an online"
                                + " store.")
                        .messages(
                            List.of(
                                user(
                                    Part.data(
                                        Map.of(
                                            "ticket",
                                            "I was charged twice for one order and I need the"
                                                + " duplicate refunded today.")))))),
            Triage.class);
    log("answers with preamble: %s", JsonUtils.toJson(out));
    assertEquals(Dept.BILLING, out.department().choice());
    assertTrue(out.isUrgent().probability() >= 0.5);
  }

  @Test
  void guidance() {
    // Object criteria on an option, a level, and a side: the gateway takes them, and the legend
    // keeps the rubric's strings.
    ModelResponse resp =
        g.generate(
            GenerateOptions.builder()
                .model(MODEL)
                .output(OutputConfig.fromClass(GuidedTriage.class))
                .messages(
                    List.of(
                        user(
                            Part.data(
                                Map.of(
                                    "ticket",
                                    "THIS IS THE THIRD TIME. I was charged twice and I want my"
                                        + " money back TODAY.")))))
                .build());
    GuidedTriage out = output(resp, GuidedTriage.class);
    log("answers: %s", JsonUtils.toJson(out));
    assertEquals(GuidedDept.BILLING, out.department().choice());
    assertEquals("Very angry", out.frustration().legend().get("2"));
  }

  @Test
  void enumFormat() {
    ModelResponse resp =
        g.generate(
            GenerateOptions.builder()
                .model(MODEL)
                .system("Which team should handle this ticket?")
                .output(OutputConfig.enums("billing", "technical", "sales"))
                .prompt("The API returns 500 errors since this morning's deploy.")
                .build());
    log("enum: %s info: %s", resp.getText(), SystemOne.responseInfo(resp));
    assertEquals("technical", resp.getText());
  }

  record HandoffCheck(
      @JsonProperty("wants_human") @JsonPropertyDescription("Does the user ask to talk to a human?")
          Noul wantsHuman) {}

  @Test
  void historyAsState() {
    HandoffCheck out =
        g.generate(
            GenerateOptions.builder()
                .model(MODEL)
                .messages(
                    List.of(
                        Message.user("Hi, I cannot log in."),
                        Message.model("Let me help. Have you tried resetting your password?"),
                        Message.user("Yes, three times. Can I please just talk to a person?")))
                .outputClass(HandoffCheck.class)
                .build());
    log("wants_human: %s", out.wantsHuman().probability());
    assertTrue(out.wantsHuman().probability() >= 0.5);
  }

  @Test
  void pinnedMinorVersion() {
    ModelResponse resp =
        decide(
            g,
            "openrouter-decisions/typesafe/jev-1.13",
            b -> b.prompt("I was charged twice for one order."));
    log("jev-1.13 resolved to %s", SystemOne.responseInfo(resp).model());
  }

  @Test
  void previewAlias() {
    // The alias is forwarded as it is, so the gateway says whether it has a preview channel.
    try {
      ModelResponse resp =
          decide(
              g,
              "openrouter-decisions/~typesafe/jev-preview",
              b -> b.prompt("I was charged twice for one order."));
      log("jev-preview resolved to %s", SystemOne.responseInfo(resp).model());
    } catch (RuntimeException e) {
      assertEquals("INVALID_ARGUMENT", status(e), messages(e));
      assertTrue(messages(e).contains("jev-preview"), messages(e));
      log("no preview channel: %s", e.getMessage());
    }
  }

  @Test
  void anotherVendorsModel() {
    // The same decision type, through the same plugin, to Liquid AI's d1.
    ModelResponse resp =
        decide(
            g,
            "openrouter-decisions/liquid/d1",
            b ->
                b.prompt(
                    "I was charged twice for one order and I need the duplicate refunded today."));
    Triage out = output(resp, Triage.class);
    log("answers: %s, info: %s", JsonUtils.toJson(out), SystemOne.responseInfo(resp));
    assertEquals(Dept.BILLING, out.department().choice());
  }

  @Test
  void listing() {
    List<String> names = new ArrayList<>();
    SystemOne p = (SystemOne) g.getRegistry().lookupPlugin("openrouter-decisions");
    for (ActionDesc desc : p.listActions()) {
      names.add(desc.getName());
    }
    log("listed: %s", names);
    assertTrue(names.contains("openrouter-decisions/liquid/d1"), names.toString());
  }

  @Test
  void runtimeQuestions() {
    // Options from data, in the order given, with guidance on one; structured instructions
    // beside a system message.
    ModelResponse resp =
        g.generate(
            GenerateOptions.builder()
                .model(MODEL)
                .system("The state is a request a user made to an assistant.")
                .output(
                    OutputConfig.json(
                        SystemOne.schema(
                            Map.of(
                                "tool",
                                new ChoiceQuestion(
                                    "Which tool serves the request?",
                                    List.of(
                                        ChoiceOption.of(
                                            "web_search",
                                            "Look up facts, news, or prices on the web"),
                                        new ChoiceOption(
                                            "calendar",
                                            "Read or change the user's own calendar",
                                            Map.of(
                                                "examples",
                                                List.of(
                                                    "What meetings do I have on Friday?",
                                                    "Move my 3pm to 4pm."))),
                                        ChoiceOption.of("none", "No tool fits the request"))),
                                "effort",
                                new ScoreQuestion(
                                    Map.of(
                                        "question",
                                        "How much work does fulfilling the request take?",
                                        "field",
                                        Map.of(
                                            "name",
                                            "request",
                                            "description",
                                            "What the user asked for")),
                                    List.of(
                                        "A single lookup", "A few steps", "A multi-step project"),
                                    null),
                                "personal",
                                NoulQuestion.of(
                                    "Does the request involve the user's own data?",
                                    "Names the user's files, mail, or calendar",
                                    "Asks about the world at large")))))
                .messages(
                    List.of(
                        user(
                            Part.data(
                                Map.of("request", "What is on my calendar tomorrow morning?")))))
                .build());
    Map<String, Answer> answers = SystemOne.answers(resp);
    log("answers: %s", answers);
    assertEquals("calendar", answers.get("tool").choice());
    assertTrue(answers.get("personal").probability() >= 0.5);
    Answer effort = answers.get("effort");
    assertTrue(effort.score() >= 0 && effort.score() <= 2);
    assertEquals("A single lookup", effort.legend().get("0"));
  }

  @Test
  void stateJsonWithALargeNumber() {
    Triage out =
        output(
            decide(
                g,
                MODEL,
                b ->
                    b.config(Config.builder().stateJson(true).build().toGenerationConfig())
                        .prompt(
                            "{\"order_id\": 9007199254740993, \"ticket\": \"I was charged twice"
                                + " for this order.\"}")),
            Triage.class);
    assertEquals(Dept.BILLING, out.department().choice());
  }

  record StockCheck(
      @JsonProperty("in_stock")
          @JsonPropertyDescription(
              "Does the context show the item the user asks about as in stock?")
          Noul inStock) {}

  @Test
  void documentWithData() {
    StockCheck out =
        g.generate(
            GenerateOptions.builder()
                .model(MODEL)
                .prompt("Is the blue kettle available?")
                .docs(
                    List.of(
                        new Document(
                            new ArrayList<>(
                                List.of(Part.data(Map.of("item", "blue kettle", "stock", 12)))))))
                .outputClass(StockCheck.class)
                .build());
    log("in_stock: %s", out.inStock().probability());
    assertTrue(out.inStock().probability() >= 0.5, "the data document did not reach the state");
  }
}
