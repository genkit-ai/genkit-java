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

import static com.google.genkit.plugins.systemone.StateBuilderTest.model;
import static com.google.genkit.plugins.systemone.StateBuilderTest.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.Document;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.OutputConfig;
import com.google.genkit.ai.OutputFormat;
import com.google.genkit.ai.Part;
import com.google.genkit.core.ActionDesc;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.plugins.systemone.TestTypes.Dept;
import com.google.genkit.plugins.systemone.TestTypes.Triage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SystemOnePluginTest {

  static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();
  static final String MODEL = "typesafe/jev-latest";

  private final List<FakeServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    servers.forEach(FakeServer::close);
  }

  FakeServer fake() {
    FakeServer f = new FakeServer();
    servers.add(f);
    return f;
  }

  /** The TypeSafe plugin pointed at a fake endpoint. */
  static SystemOne testTypeSafe(String baseUrl) {
    return SystemOne.typeSafe().apiKey("test-key").baseUrl(baseUrl).env(name -> null);
  }

  /** A Genkit with the TypeSafe plugin pointed at the fake endpoint. */
  static Genkit newGenkit(FakeServer fake) {
    return Genkit.builder().plugin(testTypeSafe(fake.url())).build();
  }

  /** Runs a generate call with the model and the output type set, and returns the response. */
  static ModelResponse decide(Genkit g, String model, Consumer<GenerateOptions.Builder<?>> opts) {
    GenerateOptions.Builder<?> b =
        GenerateOptions.builder().model(model).output(OutputConfig.fromClass(Triage.class));
    opts.accept(b);
    return g.generate(b.build());
  }

  static <T> T output(ModelResponse resp, Class<T> type) {
    return JsonUtils.fromJson(resp.getText(), type);
  }

  /** The messages of an error and its causes. */
  static String messages(Throwable e) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      sb.append(t.getMessage()).append(" | ");
    }
    return sb.toString();
  }

  /** The first Genkit status in an error's causes. */
  static String status(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof GenkitException ge && ge.getErrorCode() != null) {
        return ge.getErrorCode();
      }
    }
    return null;
  }

  static void assertRefused(Runnable call, String want) {
    RuntimeException e = assertThrows(RuntimeException.class, call::run);
    assertTrue(messages(e).contains(want), "want \"" + want + "\" in: " + messages(e));
  }

  @Test
  void generateData() throws Exception {
    // The documented call: the model, the state, and the type. The questions ride on the output
    // schema, so no format is named.
    FakeServer fake = fake();
    Genkit g = newGenkit(fake);
    ModelResponse resp =
        decide(
            g,
            "typesafe/jev-1.13.0",
            b -> b.prompt("I was charged twice and need the duplicate refunded today."));
    Triage out = output(resp, Triage.class);

    // What went over the wire.
    Map<String, Object> body = fake.last().body();
    assertEquals("jev-1.13.0", body.get("model"));
    assertEquals(
        "I was charged twice and need the duplicate refunded today.",
        body.get("state"),
        "a single text part is the string state, nothing added");
    assertEquals(
        MAPPER.readTree(Wire.json(TestTypes.triageQuestions())),
        MAPPER.readTree(Wire.json(body.get("questions"))));

    // What came back, typed.
    assertEquals(Dept.BILLING, out.department().choice());
    assertEquals(0.6, out.department().confidence());
    assertEquals(0.8, out.department().probabilities().get(Dept.BILLING), 1e-9);
    assertEquals(0.93, out.isUrgent().probability());
    assertEquals(1.3, out.frustration().score());
    assertEquals("Very angry", out.frustration().legend().get("2"));
    assertEquals("Concerned but civil", out.frustration().label());

    // What the trace and the caller see on the response.
    assertEquals(312, resp.getUsage().getInputTokens());
    assertEquals(48, resp.getUsage().getOutputTokens());
    assertEquals(360, resp.getUsage().getTotalTokens());
    Info info = SystemOne.responseInfo(resp);
    assertEquals("jev-1.13.0", info.model());
    assertEquals("billing", info.answers().get("department").get("choice"));
    assertEquals(FinishReason.STOP, resp.getFinishReason());

    // A response that travelled as JSON, as a flow's output or a trace does, keeps the same info.
    ModelResponse decoded = JsonUtils.fromJson(JsonUtils.toJson(resp), ModelResponse.class);
    info = SystemOne.responseInfo(decoded);
    assertEquals("jev-1.13.0", info.model());
    assertEquals("billing", info.answers().get("department").get("choice"));

    // The typed call returns the output class directly.
    Triage typed =
        g.generate(
            GenerateOptions.builder()
                .model("typesafe/jev-1.13.0")
                .prompt("charged twice")
                .outputClass(Triage.class)
                .build());
    assertEquals(Dept.BILLING, typed.department().choice());
    Triage object = g.generateObject("typesafe/jev-1.13.0", "charged twice", Triage.class);
    assertEquals(Dept.BILLING, object.department().choice());
  }

  @Test
  void stateShapes() {
    FakeServer fake = fake();
    Genkit g = newGenkit(fake);
    Consumer<Consumer<GenerateOptions.Builder<?>>> run = opts -> decide(g, MODEL, opts);

    // object
    run.accept(
        b -> b.messages(List.of(user(Part.data(Map.of("ticket", "hi", "tier", "business"))))));
    assertEquals(Map.of("ticket", "hi", "tier", "business"), fake.last().body().get("state"));

    // history: the system message is instructions, not a record of the state.
    run.accept(b ->
        b.messages(
            List.of(
                Message.system("Be terse."),
                Message.user("Hi"),
                Message.model("Hello. How can I help?"),
                Message.user("My card was charged twice."))));
    assertEquals(
        List.of(
            Map.of("role", "user", "content", "Hi"),
            Map.of("role", "model", "content", "Hello. How can I help?"),
            Map.of("role", "user", "content", "My card was charged twice.")),
        fake.last().body().get("state"));

    // history with loop plumbing: output instructions a loop injected for another model's turn
    // reach neither the state nor the questions.
    Part plumbing = Part.text("Output should be in JSON format.");
    plumbing.setMetadata(Map.of("purpose", "output"));
    run.accept(b ->
        b.messages(
            List.of(
                new Message(com.google.genkit.ai.Role.SYSTEM, new ArrayList<>(List.of(plumbing))),
                user(Part.text("My card was charged twice."), plumbing),
                Message.model("{\"refund\": true}"))));
    assertEquals(
        List.of(
            Map.of("role", "user", "content", "My card was charged twice."),
            Map.of("role", "model", "content", "{\"refund\": true}")),
        fake.last().body().get("state"));
    assertFalse(Wire.json(fake.last().body().get("questions")).contains("JSON format"));

    // documents
    run.accept(b ->
        b.prompt("Is the refund policy 30 days?")
            .docs(
                List.of(
                    Document.fromText("Refunds within 30 days.", Map.of("id", "policy-1")),
                    Document.fromText("Shipping takes a week."),
                    new Document(
                        new ArrayList<>(List.of(Part.data(Map.of("sku", "A-1", "stock", 3))))))));
    assertEquals(
        Map.of(
            "messages",
            List.of(Map.of("role", "user", "content", "Is the refund policy 30 days?")),
            "context",
            List.of(
                Map.of("content", "Refunds within 30 days.", "metadata", Map.of("id", "policy-1")),
                "Shipping takes a week.",
                Map.of("sku", "A-1", "stock", 3))),
        fake.last().body().get("state"));

    // document with media
    assertRefused(
        () ->
            run.accept(b ->
                b.prompt("Is this the product?")
                    .docs(
                        List.of(
                            new Document(
                                new ArrayList<>(
                                    List.of(
                                        Part.media("image/png", "data:image/png;base64,AAAA"))))))),
        "media");

    // stateJSON
    run.accept(b ->
        b.prompt("{\"ticket\": \"hi\"}")
            .config(Config.builder().stateJson(true).build().toGenerationConfig()));
    assertEquals(Map.of("ticket", "hi"), fake.last().body().get("state"));

    // extra
    run.accept(b ->
        b.prompt("hi")
            .config(Config.builder().extra("session_id", "s-1").build().toGenerationConfig()));
    assertEquals("s-1", fake.last().body().get("session_id"));
  }

  record Mood(
      @JsonProperty("mood") @JsonPropertyDescription("How does the customer feel?") String mood) {}

  @Test
  void refusals() {
    FakeServer fake = fake();
    Genkit g = newGenkit(fake);

    // no output type
    assertRefused(
        () -> g.generate(GenerateOptions.builder().model(MODEL).prompt("hi").build()),
        "output type");
    // plain output type
    assertRefused(
        () ->
            g.generate(
                GenerateOptions.builder()
                    .model(MODEL)
                    .prompt("hi")
                    .outputClass(Mood.class)
                    .build()),
        "not a question");
    assertEquals(0, fake.calls(), "the endpoint was called for a type that is not a question set");
    // media
    assertRefused(
        () ->
            decide(
                g,
                MODEL,
                b ->
                    b.messages(
                        List.of(user(Part.media("image/png", "data:image/png;base64,AAAA"))))),
        "media");
    // stateJSON on prose
    assertRefused(
        () ->
            decide(
                g,
                MODEL,
                b ->
                    b.prompt("not json")
                        .config(Config.builder().stateJson(true).build().toGenerationConfig())),
        "not JSON");
    // tools
    com.google.genkit.ai.Tool<Map<String, Object>, String> tool =
        g.defineTool(
            "lookup",
            "Looks something up",
            Map.of("type", "object"),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, in) -> "found");
    assertRefused(
        () -> decide(g, MODEL, b -> b.prompt("hi").tools(List.of(tool))), "calls no tools");
    assertEquals(0, fake.calls());
  }

  @Test
  void enumFormat() {
    FakeServer fake = fake();
    Genkit g = newGenkit(fake);

    // The enum option carries no description, so the question gets the default instructions.
    ModelResponse resp =
        g.generate(
            GenerateOptions.builder()
                .model(MODEL)
                .output(OutputConfig.enums("technical", "billing"))
                .prompt("My card was charged twice.")
                .build());
    assertEquals("billing", resp.getText(), "the chosen option");
    Map<String, Object> q =
        Wire.map(Wire.map(fake.last().body().get("questions")).get(Questions.ENUM_QUESTION_ID));
    assertEquals(Wire.KIND_CHOICE, q.get("type"));
    assertFalse(String.valueOf(q.get("instructions")).isEmpty());

    // A schema with a description names the instructions.
    OutputConfig described = new OutputConfig(OutputFormat.ENUM);
    described.setSchema(
        Map.of(
            "description",
            "Which team should handle this?",
            "enum",
            List.of("technical", "billing")));
    g.generate(
        GenerateOptions.builder()
            .model(MODEL)
            .output(described)
            .prompt("My card was charged twice.")
            .build());
    q = Wire.map(Wire.map(fake.last().body().get("questions")).get(Questions.ENUM_QUESTION_ID));
    assertEquals("Which team should handle this?", q.get("instructions"));

    // The system message is the question, and it leaves the state alone.
    g.generate(
        GenerateOptions.builder()
            .model(MODEL)
            .system("Which team should handle this?")
            .output(OutputConfig.enums("technical", "billing"))
            .prompt("My card was charged twice.")
            .build());
    q = Wire.map(Wire.map(fake.last().body().get("questions")).get(Questions.ENUM_QUESTION_ID));
    assertEquals("Which team should handle this?", q.get("instructions"));
    assertEquals("My card was charged twice.", fake.last().body().get("state"));
  }

  @Test
  void systemMessageIsInstructions() {
    FakeServer fake = fake();
    Genkit g = newGenkit(fake);

    // preamble on every question
    decide(
        g,
        MODEL,
        b -> b.system("The state is a support ticket.").prompt("My card was charged twice."));
    Map<String, Object> body = fake.last().body();
    assertEquals("My card was charged twice.", body.get("state"));
    Wire.map(body.get("questions"))
        .forEach((id, raw) ->
            assertEquals(
                "The state is a support ticket.\n\n"
                    + TestTypes.triageQuestions().get(id).instructions,
                Wire.map(raw).get("instructions")));

    // system alone is not state
    assertRefused(
        () -> decide(g, MODEL, b -> b.messages(List.of(Message.system("The state is a ticket.")))),
        "no state");

    // system is text only
    assertRefused(
        () ->
            decide(
                g,
                MODEL,
                b ->
                    b.messages(
                        List.of(
                            new Message(
                                com.google.genkit.ai.Role.SYSTEM,
                                new ArrayList<>(List.of(Part.data(Map.of("tier", "business"))))),
                            Message.user("hi")))),
        "text only");
  }

  @Test
  void runtimeQuestionsThroughGenerate() {
    // Options known only at run time: the schema is built from data, and the answers come back
    // as a map of Answer.
    FakeServer fake = fake();
    Genkit g = newGenkit(fake);
    List<ChoiceOption> options = new ArrayList<>();
    for (String[] tool :
        List.of(
            new String[] {"search", "Look something up on the web"},
            new String[] {"calendar", "Read or change the user's calendar"})) {
      options.add(ChoiceOption.of(tool[0], tool[1]));
    }
    ModelResponse resp =
        g.generate(
            GenerateOptions.builder()
                .model(MODEL)
                .output(
                    OutputConfig.json(
                        SystemOne.schema(
                            Map.of(
                                "tool",
                                new ChoiceQuestion("Which tool serves the request?", options),
                                "personal",
                                NoulQuestion.of("Does the request involve the user's own data?")))))
                .prompt("What is on my calendar tomorrow?")
                .build());
    Map<String, Object> tool = Wire.map(Wire.map(fake.last().body().get("questions")).get("tool"));
    assertEquals(
        "{\"calendar\":\"Read or change the user's calendar\",\"search\":\"Look something up on"
            + " the web\"}",
        Wire.json(tool.get("criteria")));
    Map<String, Answer> answers = SystemOne.answers(resp);
    // The fake picks the first option by name.
    assertEquals("calendar", answers.get("tool").choice());
    assertEquals(0.6, answers.get("tool").confidence());
    assertEquals(0.93, answers.get("personal").probability());
  }

  private static List<String> names(SystemOne p) {
    List<String> names = new ArrayList<>();
    for (ActionDesc desc : p.listActions()) {
      assertEquals(ActionType.MODEL, desc.getType());
      names.add(desc.getName());
    }
    return names;
  }

  private static SystemOne plugin(Genkit g, String name) {
    return (SystemOne) g.getRegistry().lookupPlugin(name);
  }

  @Test
  void listActions() {
    // listed by the API: the listing adds to the models known ahead, without repeats.
    FakeServer listed = fake();
    listed.models =
        "{\"models\":[{\"name\":\"jev-1.13.0\",\"description\":\"Current\"},{\"name\":\"jev-1.14.0-preview\"}]}";
    assertEquals(
        List.of("typesafe/jev-1.13.0", "typesafe/jev-1.14.0-preview", "typesafe/jev-latest"),
        names(plugin(newGenkit(listed), "typesafe")));

    // listing unavailable
    assertEquals(
        List.of("typesafe/jev-1.13.0", "typesafe/jev-latest"),
        names(plugin(newGenkit(fake()), "typesafe")));

    // listing kept: the Dev UI lists actions often; the server is asked once.
    FakeServer kept = fake();
    kept.models = "{\"models\":[{\"name\":\"jev-1.13.0\"},{\"name\":\"\"}]}";
    SystemOne p = plugin(newGenkit(kept), "typesafe");
    names(p);
    assertEquals(List.of("typesafe/jev-1.13.0", "typesafe/jev-latest"), names(p));
    assertEquals(1, kept.listings().size(), "the server was asked for one listing");

    // the registry lists the models too, for the Dev UI.
    Genkit withRegistry = newGenkit(kept);
    assertTrue(
        withRegistry.getRegistry().listActions(ActionType.MODEL).stream()
            .anyMatch(a -> a.getName().equals("typesafe/jev-latest")));

    // models read at init: keys may carry the prefix, and a change after init has no effect.
    FakeServer srv = fake();
    SystemOne pinned =
        testTypeSafe(srv.url())
            .models(Map.of("typesafe/jev-1.13.0", SystemOne.ModelSpec.labeled("Pinned")));
    Genkit.builder().plugin(pinned).build();
    pinned.model("jev-1.14.0", SystemOne.ModelSpec.DEFAULT);
    pinned.provider("renamed");
    assertEquals(List.of("typesafe/jev-1.13.0"), names(pinned));
    assertEquals("typesafe/jev-1.13.0", pinned.modelName("jev-1.13.0"), "the name init read");
    assertTrue(
        JsonUtils.toJson(pinned.listActions().get(0).getMetadata())
            .contains("\"label\":\"Pinned\""),
        "the label from the prefixed key was not used");

    // resolves any id
    Genkit any = newGenkit(fake());
    assertNotNull(any.getModel("typesafe/jev-9.9.9"), "an unlisted version did not resolve");
    assertEquals("typesafe/jev-latest", SystemOne.typeSafe().modelName("jev-latest"));
    assertEquals(
        "typesafe/jev-latest",
        SystemOne.typeSafe().modelName("typesafe/jev-latest"),
        "the full name is taken as models takes it");
    // A name of another plugin's does not resolve here.
    assertNull(plugin(any, "typesafe").resolveAction(ActionType.MODEL, "other/jev"));
    assertNull(plugin(any, "typesafe").resolveAction(ActionType.EMBEDDER, "typesafe/jev"));
  }

  @Test
  void initRequiresAKey() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> SystemOne.typeSafe().env(name -> null).init());
    assertTrue(e.getMessage().contains("TYPESAFE_API_KEY"), e.getMessage());
  }

  @Test
  void initReadsTheEnvironment() {
    FakeServer fake = fake();
    Map<String, String> env =
        Map.of("TYPESAFE_API_KEY", "env-key", "TYPESAFE_BASE_URL", fake.url());
    SystemOne p = SystemOne.typeSafe().env(env::get);
    Genkit g = Genkit.builder().plugin(p).build();
    decide(g, MODEL, b -> b.prompt("hi"));
    assertEquals("Bearer env-key", fake.last().headers().getFirst("Authorization"));
  }

  @Test
  void initRequires() {
    Object[][] cases = {
      {"a provider", new SystemOne().baseUrl("http://localhost:11434"), "provider"},
      {"a base URL", new SystemOne().provider("local"), "baseUrl"},
      {
        "one key per model",
        new SystemOne()
            .provider("local")
            .baseUrl("http://localhost:11434")
            .model("d1", SystemOne.ModelSpec.DEFAULT)
            .model("local/d1", SystemOne.ModelSpec.DEFAULT),
        "twice"
      },
    };
    for (Object[] c : cases) {
      IllegalStateException e =
          assertThrows(IllegalStateException.class, () -> ((SystemOne) c[1]).init(), (String) c[0]);
      assertTrue(e.getMessage().contains((String) c[2]), c[0] + ": " + e.getMessage());
    }
    // Genkit reports a plugin that cannot start.
    GenkitException e =
        assertThrows(
            GenkitException.class,
            () -> Genkit.builder().plugin(new SystemOne().provider("local")).build());
    assertTrue(messages(e).contains("baseUrl"), messages(e));
  }

  @Test
  void genericServer() {
    // A server with no constructor: the settings are the whole setup, and a local one takes no
    // key.
    FakeServer fake = fake();
    Genkit g =
        Genkit.builder()
            .plugin(new SystemOne().provider("local").baseUrl(fake.url() + "/decisions/"))
            .plugin(new SystemOne().provider("custom").baseUrl(fake.url()).path("decide"))
            .plugin(new SystemOne().provider("whole").baseUrl(fake.url() + "/endpoint").path("/"))
            .build();

    decide(g, "local/vendor/d1", b -> b.prompt("hi"));
    FakeServer.Received req = fake.last();
    assertEquals("/decisions/v1/systemone", req.rawPath(), "the default path under the base URL");
    assertEquals("vendor/d1", req.body().get("model"), "the server's ID as given");
    assertNull(req.headers().get("Authorization"), "no authorization without a key");

    // A server's ID is kept whole, even one that starts with the provider.
    decide(g, "local/local/clef", b -> b.prompt("hi"));
    assertEquals("local/clef", fake.last().body().get("model"));

    // A path without a leading slash still joins the base URL.
    decide(g, "custom/d1", b -> b.prompt("hi"));
    assertEquals("/decide", fake.last().rawPath());

    // A path of "/" posts to the base URL itself.
    decide(g, "whole/d1", b -> b.prompt("hi"));
    assertEquals("/endpoint", fake.last().rawPath());
  }

  @Test
  void wireHooks() {
    // A server whose wire is not the native one is served from the settings: route wraps the
    // request, and unwrap takes the response out of the server's envelope.
    FakeServer fake = fake();
    fake.wrap = reply -> Map.of("result", reply);
    Genkit g =
        Genkit.builder()
            .plugin(
                new SystemOne()
                    .provider("wrapped")
                    .baseUrl(fake.url())
                    .path("/run")
                    .route((model, body) -> {
                      body.remove("model");
                      return new SystemOne.Routed("/" + model, Map.of("input", body));
                    })
                    .unwrap(body -> MAPPER.readTree(body).get("result").toString()))
            .build();
    Triage out = output(decide(g, "wrapped/d1", b -> b.prompt("hi")), Triage.class);
    FakeServer.Received req = fake.last();
    assertEquals("/run/d1", req.rawPath(), "the route's path after the path");
    Map<String, Object> input = Wire.map(req.body().get("input"));
    assertEquals("hi", input.get("state"));
    assertNull(input.get("model"));
    assertEquals(Dept.BILLING, out.department().choice(), "the envelope was not unwrapped");
  }

  @Test
  void extraCannotReplaceTheRequest() {
    FakeServer fake = fake();
    Genkit g = newGenkit(fake);
    for (String field : List.of("model", "state", "questions", "images", "audio", "videos")) {
      assertRefused(
          () ->
              decide(
                  g,
                  MODEL,
                  b ->
                      b.prompt("hi")
                          .config(Config.builder().extra(field, "x").build().toGenerationConfig())),
          field);
    }
    assertEquals(0, fake.calls(), "a request with a reserved extra field was sent");
  }

  @Test
  void promptFile() {
    // A prompt file renders the state as JSON, and its config parses it; the decision type is
    // the output class, so no output format needs naming.
    FakeServer fake = fake();
    Genkit g =
        Genkit.builder()
            .options(GenkitOptions.builder().promptDir("/systemone-prompts").build())
            .plugin(testTypeSafe(fake.url()))
            .build();
    Triage out = g.prompt("triage").generate(Map.of("ticket", "charged twice"), Triage.class);
    assertEquals(Dept.BILLING, out.department().choice());
    assertEquals(1.3, out.frustration().score());
    assertEquals(Map.of("ticket", "charged twice"), fake.last().body().get("state"));
  }

  @Test
  void openRouter() {
    FakeServer fake = fake();
    fake.models =
        "{\"data\":[{\"id\":\"liquid/d1\",\"name\":\"Liquid: d1\"},{\"id\":\"typesafe/jev-1.13\",\"name\":\"TypeSafe: Jev 1.13\"}]}";
    fake.usage = Map.of("input_tokens", 10, "output_tokens", 0, "cost", 0.0004);
    SystemOne p = SystemOne.openRouter().apiKey("test-key").baseUrl(fake.url());
    Genkit g = Genkit.builder().plugin(p).build();

    // Any ID OpenRouter serves goes out as it is, alias marks included.
    for (String id : List.of("liquid/d1", "~typesafe/jev-latest")) {
      ModelResponse resp = decide(g, "openrouter-decisions/" + id, b -> b.prompt("hi"));
      FakeServer.Received req = fake.last();
      assertEquals("/api/alpha/decisions", req.rawPath());
      assertEquals(id, req.body().get("model"));
      assertEquals(0.0004, resp.getUsage().getCustom().get("cost"), "the gateway's cost");
    }

    // Its models are text only, so an image is refused before sending.
    int calls = fake.calls();
    for (String id : List.of("cloudflare/clef-flash", "liquid/d1")) {
      assertRefused(
          () ->
              decide(
                  g,
                  "openrouter-decisions/" + id,
                  b ->
                      b.messages(
                          List.of(
                              user(
                                  Part.text("hi"),
                                  Part.media("image/png", "data:image/png;base64,iVBORw0KGgo="))))),
          "media");
    }
    assertEquals(calls, fake.calls(), "an image was sent to OpenRouter");

    // The listing is OpenRouter's models filtered to decision models, by ID, with the known
    // models beside them.
    List<String> known =
        List.of(
            "openrouter-decisions/cloudflare/clef",
            "openrouter-decisions/cloudflare/clef-flash",
            "openrouter-decisions/liquid/d1",
            "openrouter-decisions/typesafe/jev-1.13",
            "openrouter-decisions/~typesafe/jev-latest");
    fake.models =
        "{\"data\":[{\"id\":\"liquid/d1\",\"name\":\"Liquid: d1\"},{\"id\":\"upstage/solar-decide\"}]}";
    TreeSet<String> want = new TreeSet<>(known);
    want.add("openrouter-decisions/upstage/solar-decide");
    assertEquals(List.copyOf(want), names(p));
    List<String> listings = fake.listings();
    assertEquals(
        "/api/v1/models?output_modalities=decisions",
        listings.get(listings.size() - 1),
        "OpenRouter's models filtered to decision models");

    // With the listing down, the Dev UI still shows the known models.
    FakeServer down = fake();
    SystemOne offline = SystemOne.openRouter().apiKey("test-key").baseUrl(down.url());
    Genkit.builder().plugin(offline).build();
    assertEquals(known, names(offline));
  }

  @Test
  void openRouterRequiresAKey() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> SystemOne.openRouter().env(name -> null).init());
    assertTrue(e.getMessage().contains("OPENROUTER_API_KEY"), e.getMessage());
  }

  @Test
  void media() {
    FakeServer fake = fake();
    Genkit g =
        Genkit.builder()
            .plugin(
                new SystemOne()
                    .provider("local")
                    .baseUrl(fake.url())
                    .images(SystemOne.MediaSupport.SUPPORTED)
                    .model(
                        "text-model",
                        SystemOne.ModelSpec.DEFAULT.withImages(SystemOne.MediaSupport.UNSUPPORTED))
                    .model(
                        "omni-model",
                        SystemOne.ModelSpec.DEFAULT
                            .withAudio(SystemOne.MediaSupport.SUPPORTED)
                            .withVideo(SystemOne.MediaSupport.SUPPORTED)))
            .plugin(
                // A server not known to take images.
                new SystemOne()
                    .provider("proxy")
                    .baseUrl(fake.url())
                    .model(
                        "vision-model",
                        SystemOne.ModelSpec.DEFAULT.withImages(SystemOne.MediaSupport.SUPPORTED))
                    .model("labeled", SystemOne.ModelSpec.labeled("Labeled")))
            .build();
    String png = "data:image/png;base64,iVBORw0KGgo=";
    Consumer<GenerateOptions.Builder<?>> photo =
        b ->
            b.messages(
                List.of(user(Part.text("Is the parcel damaged?"), Part.media("image/png", png))));

    // A model with no description on a plugin that takes images is sent the image, ahead of the
    // state.
    decide(g, "local/vision-model", photo);
    Map<String, Object> body = fake.last().body();
    assertEquals(List.of(png), body.get("images"));
    assertEquals("Is the parcel damaged?", body.get("state"));

    // A model described as text only refuses it before sending.
    int calls = fake.calls();
    assertRefused(() -> decide(g, "local/text-model", photo), "media");
    assertEquals(calls, fake.calls(), "an image was sent to a text-only model");

    // By default a plugin refuses images, for every model but the ones that opt in; an entry
    // that only sets a label keeps the default.
    for (String m : List.of("proxy/new-model", "proxy/labeled")) {
      assertRefused(() -> decide(g, m, photo), "media");
    }
    decide(g, "proxy/vision-model", photo);

    // Each kind is set apart: audio and video go to a model that reads them, in their own
    // fields, and a model that reads images only refuses them.
    String wav = "data:audio/wav;base64,UklGRg==";
    String mp4 = "data:video/mp4;base64,AAAAIGZ0eXA=";
    Consumer<GenerateOptions.Builder<?>> clip =
        b ->
            b.messages(
                List.of(
                    user(
                        Part.text("Is the fan running?"),
                        Part.media("audio/wav", wav),
                        Part.media("video/mp4", mp4))));
    decide(g, "local/omni-model", clip);
    body = fake.last().body();
    assertEquals(List.of(wav), body.get("audio"));
    assertEquals(List.of(mp4), body.get("videos"));
    assertNull(body.get("images"));
    assertRefused(() -> decide(g, "local/vision-model", clip), "audio/wav media");
  }

  @Test
  void historyWithModelTurnsIsMultiTurn() {
    // A model turn of media alone keeps its role.
    FakeServer fake = fake();
    Genkit g =
        Genkit.builder()
            .plugin(
                new SystemOne()
                    .provider("local")
                    .baseUrl(fake.url())
                    .images(SystemOne.MediaSupport.SUPPORTED))
            .build();
    decide(
        g,
        "local/m",
        b ->
            b.messages(
                List.of(
                    user(Part.text("Look")),
                    model(Part.media("image/png", "data:image/png;base64,iVBORw0KGgo=")))));
    assertEquals(
        List.of(Map.of("role", "user", "content", "Look"), Map.of("role", "model", "content", "")),
        fake.last().body().get("state"));
  }

  @Test
  void configFromAGenerateActionRequest() {
    // The /util/generate action sends the config with its custom fields nested.
    Config c =
        Config.from(
            Map.of("custom", Map.of("stateJSON", true, "extra", Map.of("session_id", "s-1"))));
    assertTrue(c.stateJson());
    assertEquals(Map.of("session_id", "s-1"), c.extra());
    Map<String, Object> flat = new HashMap<>();
    flat.put("stateJSON", true);
    assertTrue(Config.from(flat).stateJson());
    assertFalse(Config.from(null).stateJson());
  }
}
