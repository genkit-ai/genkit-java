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
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import java.util.Map;

/**
 * Client-managed (stateless) agent sample.
 *
 * <p>This mirrors the JavaScript {@code weather-agent-stateless.ts} testapp. The agent is defined
 * WITHOUT a {@code .store(...)} call, so no session state is persisted server-side. Instead, {@link
 * AgentChat} automatically round-trips the full {@code SessionState} (messages + custom state) on
 * every {@code send()} call. The server processes each turn statelessly while the client holds the
 * conversation history.
 *
 * <p>To run:
 *
 * <ol>
 *   <li>Set {@code OPENAI_API_KEY} in the environment.
 *   <li>{@code mvn exec:java -pl samples/agents-stateless}
 * </ol>
 */
public class StatelessAgentApp {

  /** Simple input type for the weather tool. */
  public static class WeatherInput {
    private String location;

    public WeatherInput() {}

    public WeatherInput(String location) {
      this.location = location;
    }

    public String getLocation() {
      return location;
    }

    public void setLocation(String location) {
      this.location = location;
    }
  }

  /** Simple output type for the weather tool. */
  public static class WeatherOutput {
    private String report;

    public WeatherOutput() {}

    public WeatherOutput(String report) {
      this.report = report;
    }

    public String getReport() {
      return report;
    }

    public void setReport(String report) {
      this.report = report;
    }
  }

  public static void main(String[] args) {
    // ── 1. Build Genkit with experimental (agents) enabled ──────────────────
    Genkit genkit =
        Genkit.builder()
            .options(
                GenkitOptions.builder()
                    .experimental(true) // required for the beta agents API
                    .devMode(true)
                    .build())
            .plugin(OpenAIPlugin.create())
            .build();

    // ── 2. Define a mock weather tool ───────────────────────────────────────
    Tool<WeatherInput, WeatherOutput> getWeather =
        genkit.defineTool(
            "getWeather",
            "Returns current weather conditions for a given location",
            (ctx, input) -> {
              String location = input != null ? input.getLocation() : "unknown";
              return new WeatherOutput("Sunny and 22°C in " + location);
            },
            WeatherInput.class,
            WeatherOutput.class);

    // ── 3. Define a client-managed (stateless) agent ─────────────────────────
    //
    // Key: no .store(...) call. Without a SessionStore, the agent operates in
    // client-managed mode. AgentChat holds the full SessionState locally and
    // sends it with every turn so the model always sees the full conversation
    // history — without any server-side persistence. This is useful for
    // serverless environments or when you want the client to own session state.
    Agent<Map<String, Object>> statelessWeather =
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("statelessWeather")
                    .description("A helpful weather assistant (client-managed state)")
                    .system(
                        "You are a helpful weather assistant. "
                            + "Use the getWeather tool to answer questions about the weather. "
                            + "Remember previous questions in the conversation.")
                    .tools(getWeather)
                    .model("openai/gpt-4o-mini")
                    // No .store() → client-managed: AgentChat round-trips full state each turn.
                    .build());

    // ── 4. Guard live model calls behind an API-key check ───────────────────
    //
    // The module must compile even without an API key. Real calls only execute
    // when OPENAI_API_KEY is set so CI / offline builds succeed.
    String apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.out.println(
          "OPENAI_API_KEY is not set — agent defined successfully but skipping live calls.");
      System.out.println("Set OPENAI_API_KEY and re-run to see the stateless agent demo.");
      return;
    }

    // ── 5. Multi-turn chat with client-managed state ─────────────────────────
    //
    // The AgentChat object holds the full conversation history locally.
    // Each send() serialises the current state into the AgentInit and
    // the server processes the turn without reading or writing any store.
    System.out.println("=== Client-managed (stateless) weather agent ===");
    AgentChat<Map<String, Object>> chat = statelessWeather.chat();

    AgentResponse<Map<String, Object>> turn1 = chat.send("What is the weather in Tokyo?");
    System.out.println("Turn 1: " + turn1.text());

    // The full conversation history (including turn 1 and the tool call) is
    // automatically carried in the request — no server-side store is accessed.
    AgentResponse<Map<String, Object>> turn2 = chat.send("How does that compare to Paris?");
    System.out.println("Turn 2: " + turn2.text());

    AgentResponse<Map<String, Object>> turn3 = chat.send("Which city had warmer weather?");
    System.out.println("Turn 3: " + turn3.text());
  }
}
