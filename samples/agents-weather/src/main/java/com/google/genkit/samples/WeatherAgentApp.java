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
import com.google.genkit.ai.agent.FileSessionStore;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import java.util.Map;

/**
 * Canonical Genkit Agents sample — a weather assistant demonstrating:
 *
 * <ul>
 *   <li>Server-managed session state via {@link FileSessionStore} ({@code weatherAgent})
 *   <li>Client-managed (stateless) session state ({@code weatherAgentStateless})
 *   <li>Multi-turn chat with automatic history carry-forward
 *   <li>Streaming with {@code sendStream}
 *   <li>Tool definition via {@code genkit.defineTool}
 * </ul>
 *
 * <p>This mirrors the JavaScript {@code weather-agent.ts} / {@code weather-agent-stateless.ts}
 * testapps. The agents API is in beta — enable it with {@code
 * GenkitOptions.builder().experimental(true)}.
 *
 * <p>Two run modes:
 *
 * <ul>
 *   <li><b>Serve (default)</b> — starts the Jetty plugin to expose the agents over HTTP and keep
 *       the process alive. Run under the Genkit CLI to test the agents in the <b>Dev UI</b>: {@code
 *       genkit start -- mvn -q -pl samples/agents-weather exec:java}. The agents are also reachable
 *       at {@code POST http://localhost:8080/weatherAgent} (override the port with the {@code PORT}
 *       env var). No API key is needed to start the server, but live model calls still require
 *       {@code OPENAI_API_KEY}.
 *   <li><b>Demo</b> — runs the in-process multi-turn/streaming chat demo and exits. Requires {@code
 *       OPENAI_API_KEY}: {@code mvn -q -pl samples/agents-weather exec:java -Dexec.args=demo}.
 * </ul>
 */
public class WeatherAgentApp {

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

  public static void main(String[] args) throws Exception {
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
    //
    // In production you would call a real weather service here.
    // The tool signature uses the typed defineTool variant so JSON schema is
    // auto-generated from WeatherInput / WeatherOutput.
    Tool<WeatherInput, WeatherOutput> getWeather =
        genkit.defineTool(
            "getWeather",
            "Returns current weather conditions for a given location",
            (ctx, input) -> {
              // Simulated weather — swap for a real API call as needed.
              String location = input != null ? input.getLocation() : "unknown";
              return new WeatherOutput("Sunny and 22°C in " + location);
            },
            WeatherInput.class,
            WeatherOutput.class);

    // ── 3. Server-managed agent (state stored in .snapshots/) ───────────────
    //
    // The FileSessionStore persists conversation snapshots to disk so the agent
    // can resume across process restarts. Pass a store to .store(...) to enable
    // server-managed mode; omit it for client-managed (stateless) mode.
    Agent<Map<String, Object>> weatherAgent =
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("weatherAgent")
                    .description("A helpful weather assistant")
                    .system(
                        "You are a helpful weather assistant. "
                            + "Use the getWeather tool to answer questions about the weather.")
                    .tools(getWeather)
                    .model("openai/gpt-4o-mini")
                    .store(new FileSessionStore<>("./.snapshots"))
                    .build());

    // ── 4. Client-managed (stateless) agent ─────────────────────────────────
    //
    // No .store(...) → the agent does NOT persist state server-side.
    // Instead the full SessionState round-trips through AgentChat automatically:
    // each send() carries the current messages/state in the AgentInit so the
    // model always sees the full conversation history.
    Agent<Map<String, Object>> weatherAgentStateless =
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("weatherAgentStateless")
                    .description("A helpful weather assistant (client-managed state)")
                    .system(
                        "You are a helpful weather assistant. "
                            + "Use the getWeather tool to answer questions about the weather.")
                    .tools(getWeather)
                    .model("openai/gpt-4o-mini")
                    // No .store() → client-managed: AgentChat round-trips full state each turn.
                    .build());

    // ── 5. Default mode: serve the agents over HTTP + keep the process alive ─
    //
    // Run with NO args (e.g. under `genkit start`) to expose the agents:
    //   • The Genkit Dev UI discovers and runs them via the reflection server,
    //     which starts automatically because devMode(true) is set above. Under
    //     `genkit start` the runtime connects to the CLI and the agents show up
    //     in the browser's "Agents"/"Flows" surface, ready to chat.
    //   • Jetty also serves each agent over plain HTTP at POST /<name>
    //     (plus /getSnapshot and /abort companions for server-managed agents),
    //     so you can curl them or drive them with the remoteAgent client.
    //
    // jetty.start() blocks until the process is stopped — that is what keeps the
    // runtime alive for the Dev UI. Pass the "demo" argument to instead run the
    // in-process chat demo below (needs OPENAI_API_KEY).
    boolean runDemo = args.length > 0 && "demo".equalsIgnoreCase(args[0]);
    if (!runDemo) {
      int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
      JettyPlugin jetty = JettyPlugin.create(port);
      jetty.init(genkit.getRegistry());
      System.out.println("Serving agents on http://localhost:" + port);
      System.out.println(
          "  weatherAgent          -> POST http://localhost:" + port + "/weatherAgent");
      System.out.println(
          "  weatherAgentStateless -> POST http://localhost:" + port + "/weatherAgentStateless");
      System.out.println(
          "Tip: run under `genkit start -- mvn -q -pl samples/agents-weather exec:java` to open"
              + " the Dev UI, or pass `demo` to run the in-process chat demo.");
      try {
        jetty.start(); // blocks until the process is stopped (Ctrl-C)
      } catch (Exception e) {
        // The most common failure here is a port collision (something else — often another
        // dev server — is already listening on the port). Fail loudly with an actionable hint
        // instead of leaving the misleading "Serving agents on ..." message above standing while
        // the agents are actually unreachable.
        System.err.println();
        System.err.println(
            "ERROR: could not start the agent HTTP server on port " + port + ": " + e.getMessage());
        System.err.println(
            "Port "
                + port
                + " is likely already in use. Free it (e.g. `lsof -nP -iTCP:"
                + port
                + " -sTCP:LISTEN` then kill the process), or run on a different port and point the");
        System.err.println(
            "remote client at the same one, e.g.:  PORT="
                + (port + 1)
                + " mvn -q -pl samples/agents-weather exec:java");
        System.err.println(
            "  then:  AGENT_URL=http://localhost:"
                + (port + 1)
                + "/weatherAgent REMOTE_AGENT_RUN=true mvn -q -pl samples/agents-remote exec:java");
        throw e;
      }
      return;
    }

    // ── 6. Demo mode: in-process chat (requires OPENAI_API_KEY) ──────────────
    //
    // The module must compile even without an API key.  Real calls only execute
    // when OPENAI_API_KEY is set so CI / offline builds succeed.
    String apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.out.println(
          "OPENAI_API_KEY is not set — agents defined successfully but skipping live calls.");
      System.out.println("Set OPENAI_API_KEY and re-run to see the full demo.");
      return;
    }

    // ── 6. Multi-turn chat with server-managed state ─────────────────────────
    System.out.println("=== Server-managed agent ===");
    AgentChat<Map<String, Object>> chat = weatherAgent.chat();

    AgentResponse<Map<String, Object>> res1 = chat.send("What is the weather in London?");
    System.out.println("Turn 1: " + res1.text());

    AgentResponse<Map<String, Object>> res2 = chat.send("Now say that in French");
    System.out.println("Turn 2: " + res2.text());

    // ── 7. Streaming turn ────────────────────────────────────────────────────
    System.out.println("\n=== Streaming turn ===");
    System.out.print("Streaming: ");
    AgentResponse<Map<String, Object>> res3 =
        chat.sendStream(
            "Summarise in one sentence",
            chunk -> {
              // chunk.text() contains the incremental model token
              if (chunk.modelChunk() != null) {
                System.out.print(chunk.modelChunk().getText());
              }
            });
    System.out.println("\nFull response: " + res3.text());

    // ── 8. Client-managed (stateless) demo ──────────────────────────────────
    System.out.println("\n=== Client-managed (stateless) agent ===");
    AgentChat<Map<String, Object>> statelessChat = weatherAgentStateless.chat();

    AgentResponse<Map<String, Object>> sl1 = statelessChat.send("What is the weather in Tokyo?");
    System.out.println("Turn 1: " + sl1.text());

    // State is carried automatically by AgentChat — no server-side store needed.
    AgentResponse<Map<String, Object>> sl2 = statelessChat.send("Compare that to Paris");
    System.out.println("Turn 2: " + sl2.text());
  }
}
