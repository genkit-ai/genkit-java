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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentRef;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** TDD tests for {@link GenkitBeta} (Task 5.3). */
class GenkitBetaTest {

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** A fake echo model that streams one chunk and returns "echo: <last-user-text>". */
  private static Model echoModel(String name) {
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
        String userText = "";
        if (request.getMessages() != null) {
          for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            Message m = request.getMessages().get(i);
            if (m.getRole() == Role.USER) {
              userText = m.getText();
              break;
            }
          }
        }
        String reply = "echo: " + userText;
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
    };
  }

  private static Genkit experimentalGenkit() {
    Genkit genkit = new Genkit(GenkitOptions.builder().experimental(true).build());
    genkit.registerModel(echoModel("echoModel"));
    return genkit;
  }

  /**
   * A model that records the SYSTEM-role text of the last request it received (so tests can assert
   * exactly what system prompt reached the model), then replies "ok".
   */
  private static final class RecordingModel implements Model {
    private final String name;
    private volatile String lastSystem;

    RecordingModel(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public ModelInfo getInfo() {
      ModelInfo info = new ModelInfo();
      ModelInfo.ModelCapabilities caps = new ModelInfo.ModelCapabilities();
      caps.setSystemRole(true);
      caps.setMultiturn(true);
      info.setSupports(caps);
      return info;
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
      if (request.getMessages() != null) {
        for (Message m : request.getMessages()) {
          if (m.getRole() == Role.SYSTEM) {
            lastSystem = m.getText();
            break;
          }
        }
      }
      if (streamCallback != null) {
        ModelResponseChunk chunk = new ModelResponseChunk();
        chunk.setContent(List.of(Part.text("ok")));
        streamCallback.accept(chunk);
      }
      Candidate candidate = new Candidate(Message.model("ok"), FinishReason.STOP);
      ModelResponse response = new ModelResponse(List.of(candidate));
      response.setFinishReason(FinishReason.STOP);
      response.setRequest(request);
      return response;
    }
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

  // ── gating: defineAgent ──────────────────────────────────────────────────────

  @Test
  void defineAgentWithoutExperimentalThrows() {
    Genkit genkit = new Genkit(GenkitOptions.builder().build());
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder().name("a1").build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> genkit.beta().defineAgent(config));
    assertTrue(
        ex.getMessage().toLowerCase().contains("experimental"),
        "message should mention experimental, got: " + ex.getMessage());
  }

  // ── gating: defineCustomAgent ────────────────────────────────────────────────

  @Test
  void defineCustomAgentWithoutExperimentalThrows() {
    Genkit genkit = new Genkit(GenkitOptions.builder().build());
    com.google.genkit.ai.agent.CustomAgentConfig<Map<String, Object>> config =
        com.google.genkit.ai.agent.CustomAgentConfig.<Map<String, Object>>builder()
            .name("custom1")
            .build();

    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () ->
                genkit
                    .beta()
                    .defineCustomAgent(
                        config,
                        (runner, ctx) ->
                            AgentResult.builder().message(Message.model("x")).build()));
    assertTrue(ex.getMessage().toLowerCase().contains("experimental"));
  }

  @Test
  void defineCustomAgentWithExperimentalRegisters() {
    Genkit genkit = experimentalGenkit();
    com.google.genkit.ai.agent.CustomAgentConfig<Map<String, Object>> config =
        com.google.genkit.ai.agent.CustomAgentConfig.<Map<String, Object>>builder()
            .name("custom2")
            .build();

    Agent<Map<String, Object>> agent =
        genkit
            .beta()
            .defineCustomAgent(
                config, (runner, ctx) -> AgentResult.builder().message(Message.model("x")).build());

    assertNotNull(agent);
    assertNotNull(genkit.getRegistry().lookupAction("/agent/custom2"));
  }

  // ── defineAgent registration ─────────────────────────────────────────────────

  @Test
  void defineAgentWithExperimentalRegisters() {
    Genkit genkit = experimentalGenkit();
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("helper")
            .description("a helper agent")
            .model("echoModel")
            .system("You are helpful.")
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().defineAgent(config);

    assertNotNull(agent);
    assertNotNull(genkit.getRegistry().lookupAction("/agent/helper"));
  }

  // ── one turn through the agent bidi action ───────────────────────────────────

  @Test
  void defineAgentRunsOneTurn() throws Exception {
    Genkit genkit = experimentalGenkit();
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("turnAgent")
            .model("echoModel")
            .system("You are helpful.")
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().defineAgent(config);

    ActionContext ctx = new ActionContext(genkit.getRegistry());
    List<JsonNode> chunks = new ArrayList<>();
    JsonNode out = agent.runBidiJson(ctx, initJson(), inputSourceWith("hi"), chunks::add);

    assertNotNull(out);
    // The final message is the model's reply.
    assertEquals("echo: hi", out.get("message").get("content").get(0).get("text").asText());

    // A turnEnd chunk was observed, and a model chunk was streamed.
    boolean sawTurnEnd = chunks.stream().anyMatch(c -> c.has("turnEnd"));
    assertTrue(sawTurnEnd, "expected a turnEnd chunk");
    boolean sawModelChunk = chunks.stream().anyMatch(c -> c.has("modelChunk"));
    assertTrue(sawModelChunk, "expected a modelChunk");
  }

  // ── definePromptAgent (first-cut: uses config.system like defineAgent) ────────

  @Test
  void definePromptAgentWithoutExperimentalThrows() {
    Genkit genkit = new Genkit(GenkitOptions.builder().build());
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder().name("p1").build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> genkit.beta().definePromptAgent(config));
    assertTrue(ex.getMessage().toLowerCase().contains("experimental"));
  }

  @Test
  void definePromptAgentWithExperimentalRegistersAndRuns() throws Exception {
    Genkit genkit = experimentalGenkit();
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("promptAgent")
            .model("echoModel")
            .system("You are helpful.")
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().definePromptAgent(config);
    assertNotNull(genkit.getRegistry().lookupAction("/agent/promptAgent"));

    ActionContext ctx = new ActionContext(genkit.getRegistry());
    JsonNode out = agent.runBidiJson(ctx, initJson(), inputSourceWith("yo"), c -> {});
    assertEquals("echo: yo", out.get("message").get("content").get(0).get("text").asText());
  }

  // ── definePromptAgent renders its template with promptInput PER TURN ──────────

  /**
   * Proves the prompt-backed agent renders its Handlebars template with {@code promptInput} on each
   * turn: the {@code topicAgent.prompt} template is {@code "You are an expert on {{topic}}. Answer
   * questions about {{topic}} for {{userName}}."}. With {@code promptInput = {topic: "wombats",
   * userName: "Ada"}}, the SYSTEM text that actually reaches the model must contain the
   * interpolated values, and must NOT contain the raw {@code {{topic}}} placeholder.
   */
  @Test
  void definePromptAgentRendersPromptInputPerTurn() throws Exception {
    Genkit genkit = new Genkit(GenkitOptions.builder().experimental(true).build());
    RecordingModel model = new RecordingModel("echoModel");
    genkit.registerModel(model);

    Map<String, Object> promptInput = new java.util.HashMap<>();
    promptInput.put("topic", "wombats");
    promptInput.put("userName", "Ada");

    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("topicAgent")
            .model("echoModel")
            .promptName("topicAgent")
            .promptInput(promptInput)
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().definePromptAgent(config);

    ActionContext ctx = new ActionContext(genkit.getRegistry());
    agent.runBidiJson(ctx, initJson(), inputSourceWith("hi"), c -> {});

    assertNotNull(model.lastSystem, "the model should have received a SYSTEM message");
    assertTrue(
        model.lastSystem.contains("You are an expert on wombats"),
        "system should interpolate promptInput.topic, got: " + model.lastSystem);
    assertTrue(
        model.lastSystem.contains("for Ada"),
        "system should interpolate promptInput.userName, got: " + model.lastSystem);
    assertFalse(
        model.lastSystem.contains("{{"),
        "system must be RENDERED, not the raw template, got: " + model.lastSystem);
  }

  /**
   * Proves the render context also folds in the current session state: with an empty {@code
   * promptInput} but a session custom-state map carrying {@code {topic: "quokkas", userName:
   * "Grace"}}, those state values interpolate into the rendered system prompt for the turn.
   */
  @Test
  void definePromptAgentRendersSessionStatePerTurn() throws Exception {
    Genkit genkit = new Genkit(GenkitOptions.builder().experimental(true).build());
    RecordingModel model = new RecordingModel("echoModel");
    genkit.registerModel(model);

    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("topicAgent")
            .model("echoModel")
            .promptName("topicAgent")
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().definePromptAgent(config);

    Map<String, Object> customState = new java.util.HashMap<>();
    customState.put("topic", "quokkas");
    customState.put("userName", "Grace");
    com.google.genkit.ai.agent.SessionState<Map<String, Object>> state =
        com.google.genkit.ai.agent.SessionState.<Map<String, Object>>builder()
            .custom(customState)
            .build();
    JsonNode initWithState =
        JsonUtils.toJsonNode(
            com.google.genkit.ai.agent.AgentInit.<Map<String, Object>>builder()
                .state(state)
                .build());

    ActionContext ctx = new ActionContext(genkit.getRegistry());
    agent.runBidiJson(ctx, initWithState, inputSourceWith("hi"), c -> {});

    assertNotNull(model.lastSystem, "the model should have received a SYSTEM message");
    assertTrue(
        model.lastSystem.contains("You are an expert on quokkas"),
        "system should interpolate session state topic, got: " + model.lastSystem);
    assertTrue(
        model.lastSystem.contains("for Grace"),
        "system should interpolate session state userName, got: " + model.lastSystem);
  }

  // ── finish reason mapping ────────────────────────────────────────────────────

  @Test
  void agentTurnUsesStopFinishReason() throws Exception {
    Genkit genkit = experimentalGenkit();
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder().name("frAgent").model("echoModel").build();
    Agent<Map<String, Object>> agent = genkit.beta().defineAgent(config);

    ActionContext ctx = new ActionContext(genkit.getRegistry());
    JsonNode out = agent.runBidiJson(ctx, initJson(), inputSourceWith("hi"), c -> {});
    assertEquals(AgentFinishReason.STOP.getValue(), out.get("finishReason").asText());
    assertFalse(out.get("message").isNull());
  }

  // ── isExperimental flag exposed on Genkit ────────────────────────────────────

  @Test
  void isExperimentalReflectsOptions() {
    assertFalse(new Genkit(GenkitOptions.builder().build()).isExperimental());
    assertTrue(new Genkit(GenkitOptions.builder().experimental(true).build()).isExperimental());
  }

  // ── description exposed on the agent's ref() ─────────────────────────────────

  @Test
  void defineAgentExposesDescriptionOnRef() {
    Genkit genkit = experimentalGenkit();
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("describedAgent")
            .description("some text")
            .model("echoModel")
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().defineAgent(config);

    AgentRef ref = agent.ref();
    assertEquals("describedAgent", ref.getName());
    assertEquals("some text", ref.getDescription());
  }

  // ── metadata: description + stateManagement/abortable ────────────────────────

  /**
   * Client-managed (no store): {@code defineAgent} should surface the description and a {@code
   * stateManagement=client} / {@code abortable=false} sub-map under the {@code "agent"} metadata
   * key. This config does not set a {@code stateType}, so no {@code stateSchema} is expected either
   * (see {@link #defineAgentMetadataIncludesStateSchemaForTypedState} for the populated case).
   */
  @Test
  @SuppressWarnings("unchecked")
  void defineAgentMetadataReflectsDescriptionAndClientManagedState() {
    Genkit genkit = experimentalGenkit();
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("metaAgentClient")
            .description("client managed agent")
            .model("echoModel")
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().defineAgent(config);

    Map<String, Object> metadata = agent.getMetadata();
    assertNotNull(metadata);
    assertEquals("client managed agent", metadata.get("description"));

    Map<String, Object> agentMeta = (Map<String, Object>) metadata.get("agent");
    assertNotNull(agentMeta, "expected an \"agent\" sub-map in metadata");
    assertEquals("client", agentMeta.get("stateManagement"));
    assertEquals(false, agentMeta.get("abortable"));
    assertFalse(
        agentMeta.containsKey("stateSchema"),
        "no stateType was configured, so no stateSchema should be generated");

    assertFalse(agent.serverManaged());
  }

  /**
   * Server-managed (store configured with an {@link InMemorySessionStore}, which also implements
   * {@code SnapshotSubscriber}): {@code stateManagement} flips to {@code "server"} and {@code
   * abortable} flips to {@code true}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void defineAgentMetadataReflectsServerManagedState() {
    Genkit genkit = experimentalGenkit();
    AgentConfig<Map<String, Object>> config =
        AgentConfig.<Map<String, Object>>builder()
            .name("metaAgentServer")
            .description("server managed agent")
            .model("echoModel")
            .store(new InMemorySessionStore<>())
            .build();

    Agent<Map<String, Object>> agent = genkit.beta().defineAgent(config);

    Map<String, Object> metadata = agent.getMetadata();
    Map<String, Object> agentMeta = (Map<String, Object>) metadata.get("agent");
    assertNotNull(agentMeta);
    assertEquals("server", agentMeta.get("stateManagement"));
    assertEquals(true, agentMeta.get("abortable"));

    assertTrue(agent.serverManaged());
  }

  /** POJO custom-state type used to prove {@code stateSchema} generation end-to-end. */
  public static class TypedState {
    private String status;
    private int counter;

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public int getCounter() {
      return counter;
    }

    public void setCounter(int counter) {
      this.counter = counter;
    }
  }

  /**
   * Fix 4: when a {@code defineAgent} config specifies a non-trivial POJO {@code stateType}, the
   * registered agent's metadata must include a generated {@code stateSchema} (under the {@code
   * "agent"} sub-map, sibling to {@code stateManagement}/{@code abortable}) describing that type's
   * properties. {@code defineAgent} delegates to {@code AgentActions.defineCustomAgent} under the
   * hood (see {@code GenkitBeta.defineGenerateBackedAgent}), so this also exercises the
   * generate-backed path, not just the low-level {@code defineCustomAgent} entry point.
   */
  @Test
  @SuppressWarnings("unchecked")
  void defineAgentMetadataIncludesStateSchemaForTypedState() {
    Genkit genkit = experimentalGenkit();
    AgentConfig<TypedState> config =
        AgentConfig.<TypedState>builder()
            .name("typedStateAgent")
            .model("echoModel")
            .stateType(TypedState.class)
            .build();

    Agent<TypedState> agent = genkit.beta().defineAgent(config);

    Map<String, Object> metadata = agent.getMetadata();
    Map<String, Object> agentMeta = (Map<String, Object>) metadata.get("agent");
    assertNotNull(agentMeta);

    Object stateSchemaObj = agentMeta.get("stateSchema");
    assertNotNull(stateSchemaObj, "expected a generated stateSchema for a typed custom state");
    Map<String, Object> stateSchema = (Map<String, Object>) stateSchemaObj;

    Object propertiesObj = stateSchema.get("properties");
    assertNotNull(propertiesObj, "expected stateSchema.properties to be present");
    Map<String, Object> properties = (Map<String, Object>) propertiesObj;
    assertTrue(properties.containsKey("status"), "expected a 'status' property in the schema");
    assertTrue(properties.containsKey("counter"), "expected a 'counter' property in the schema");
  }
}
