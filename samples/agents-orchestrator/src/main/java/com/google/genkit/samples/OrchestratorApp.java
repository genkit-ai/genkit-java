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
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.middleware.Agents;
import com.google.genkit.plugins.middleware.AgentsOptions;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator agent sample demonstrating sub-agent delegation via the middleware.
 *
 * <p>This mirrors the JavaScript {@code orchestrator-agent.ts} testapp. Two specialised sub-agents
 * ({@code researcher} and {@code coder}) are defined first; the orchestrator is given delegation
 * tools produced by {@link Agents#delegationTools(AgentsOptions)} and a system-prompt fragment
 * produced by {@link Agents#systemPromptFragment(AgentsOptions)} so it can dispatch tasks to them.
 *
 * <p>Two run modes:
 *
 * <ul>
 *   <li><b>Serve (default)</b> — starts the Jetty plugin to expose the agents over HTTP and keep
 *       the process alive. Run under the Genkit CLI to test the agents in the <b>Dev UI</b>: {@code
 *       genkit start -- mvn -q -pl samples/agents-orchestrator exec:java}. The agents are also
 *       reachable at {@code POST http://localhost:8080/<name>} (override the port with the {@code
 *       PORT} env var).
 *   <li><b>Demo</b> — runs the in-process orchestrator delegation demo once and exits. Requires
 *       {@code OPENAI_API_KEY}: {@code mvn -q -pl samples/agents-orchestrator exec:java
 *       -Dexec.args=demo}.
 * </ul>
 */
public class OrchestratorApp {

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

    // ── 2. Define the researcher sub-agent ──────────────────────────────────
    //
    // A specialised agent that researches topics.
    @SuppressWarnings("unused")
    Agent<Map<String, Object>> researcher =
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("researcher")
                    .description("Researches topics and returns a summary")
                    .system(
                        "You are a knowledgeable researcher. "
                            + "When given a topic, provide a concise, factual summary. "
                            + "Focus on key facts, recent developments, and practical implications.")
                    .model("openai/gpt-4o-mini")
                    .build());

    // ── 3. Define the coder sub-agent ────────────────────────────────────────
    //
    // A specialised agent that writes code based on a description.
    @SuppressWarnings("unused")
    Agent<Map<String, Object>> coder =
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("coder")
                    .description("Writes code based on a description or research summary")
                    .system(
                        "You are an expert software engineer. "
                            + "When given a task description or research summary, write clean, "
                            + "well-commented code. Prefer Java when no language is specified. "
                            + "Include usage examples in comments.")
                    .model("openai/gpt-4o-mini")
                    .build());

    // ── 4. Build delegation tools via the middleware Agents factory ─────────
    //
    // AgentsOptions lists the registered agent names the orchestrator can
    // delegate to. Agents.delegationTools() produces one Tool per sub-agent
    // (named "delegate_to_<agentName>" by default).
    // Agents.systemPromptFragment() builds a <sub-agents> XML block that
    // explains the tools to the model.
    AgentsOptions delegationOpts =
        AgentsOptions.builder().agents("researcher", "coder").maxDelegations(5).build();

    List<Tool<?, ?>> delegationTools = Agents.delegationTools(delegationOpts);
    String subAgentFragment = Agents.systemPromptFragment(delegationOpts);

    String baseSystem =
        "You are an orchestrator agent that breaks complex tasks into sub-tasks "
            + "and delegates them to specialised agents. "
            + "First research the topic, then produce code based on the findings.";

    // ── 5. Define the orchestrator agent ────────────────────────────────────
    Agent<Map<String, Object>> orchestrator =
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("orchestrator")
                    .description(
                        "Orchestrator that delegates tasks to researcher and coder sub-agents")
                    .system(baseSystem + "\n" + subAgentFragment)
                    .tools(new ArrayList<>(delegationTools))
                    .model("openai/gpt-4o-mini")
                    .build());

    int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
    JettyPlugin jetty = JettyPlugin.create(port);
    jetty.init(genkit.getRegistry());
    System.out.println("Serving agents on http://localhost:" + port);
    System.out.println("  orchestrator -> POST http://localhost:" + port + "/orchestrator");
    System.out.println("  researcher   -> POST http://localhost:" + port + "/researcher");
    System.out.println("  coder        -> POST http://localhost:" + port + "/coder");
    System.out.println(
        "Tip: run under `genkit start -- mvn -q -pl samples/agents-orchestrator exec:java` to"
            + " open the Dev UI, or pass `demo` to run the one-shot delegation demo.");
    try {
      //
      // The module must compile even without an API key. Real calls only execute
      // when OPENAI_API_KEY is set so CI / offline builds succeed.
      String apiKey = System.getenv("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
        System.out.println(
            "OPENAI_API_KEY is not set — agents defined successfully but skipping live calls.");
        System.out.println("Set OPENAI_API_KEY and re-run with `demo` to see the orchestrator.");
        return;
      }

      System.out.println("=== Orchestrator Agent ===");
      AgentChat<Map<String, Object>> chat = orchestrator.chat();

      AgentResponse<Map<String, Object>> response =
          chat.send("Research the bubble sort algorithm and then write Java code to implement it.");
      System.out.println("Orchestrator response:\n" + response.text());

      jetty.start(); // blocks until the process is stopped (Ctrl-C)
    } catch (Exception e) {
      // The most common failure here is a port collision (something else — often
      // another
      // dev server — is already listening on the port). Fail loudly with an
      // actionable hint
      // instead of leaving the misleading "Serving agents on ..." message above
      // standing while
      // the agents are actually unreachable.
      System.err.println();
      System.err.println(
          "ERROR: could not start the agent HTTP server on port " + port + ": " + e.getMessage());
      System.err.println(
          "Port "
              + port
              + " is likely already in use. Free it (e.g. `lsof -nP -iTCP:"
              + port
              + " -sTCP:LISTEN` then kill the process), or run on a different port, e.g.:  PORT="
              + (port + 1)
              + " mvn -q -pl samples/agents-orchestrator exec:java");
      throw e;
    }
    return;
  }
}
