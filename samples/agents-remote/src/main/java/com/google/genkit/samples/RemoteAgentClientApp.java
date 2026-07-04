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

import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.client.RemoteAgent;
import com.google.genkit.client.RemoteAgentOptions;
import java.util.Map;

/**
 * Remote agent client sample.
 *
 * <p>This mirrors the JavaScript {@code remote-client.ts} testapp. It uses {@link RemoteAgent} to
 * connect to an agent served over HTTP (e.g. by the Jetty plugin). The client holds the
 * conversation history locally and sends it with every turn — the server itself can be stateless or
 * server-managed depending on how the served agent was configured.
 *
 * <h2>Running this sample</h2>
 *
 * <ol>
 *   <li>Start the server: run {@code samples/agents-weather} with the Jetty plugin serving the
 *       {@code weatherAgent} at {@code http://localhost:8080/weatherAgent}.
 *   <li>Set {@code AGENT_URL} (optional, defaults to {@code http://localhost:8080/weatherAgent}).
 *   <li>Set {@code REMOTE_AGENT_RUN=true} to enable the actual network call (omit for offline / CI
 *       builds).
 *   <li>{@code mvn exec:java -pl samples/agents-remote}
 * </ol>
 *
 * <h2>Architecture note</h2>
 *
 * <p>{@link RemoteAgent#chat(RemoteAgentOptions)} returns an {@link AgentChat} backed by an {@code
 * HttpAgentTransport}. Each {@code send()} posts a turn to {@code url} and (in client-managed mode)
 * reads back the updated session state so that subsequent turns include the full conversation
 * history. The companion endpoints {@code url/getSnapshot} and {@code url/abort} are derived
 * automatically from the base URL.
 */
public class RemoteAgentClientApp {

  /** Default agent URL — override via the {@code AGENT_URL} environment variable. */
  private static final String DEFAULT_AGENT_URL = "http://localhost:8080/weatherAgent";

  public static void main(String[] args) {
    // ── 1. Resolve the agent URL ─────────────────────────────────────────────
    //
    // Override with AGENT_URL env var to point at a different agent endpoint.
    String agentUrl = System.getenv("AGENT_URL");
    if (agentUrl == null || agentUrl.isBlank()) {
      agentUrl = DEFAULT_AGENT_URL;
    }
    System.out.println("Remote agent URL: " + agentUrl);

    // ── 2. Build RemoteAgentOptions ──────────────────────────────────────────
    //
    // RemoteAgent.chat() wraps an HttpAgentTransport configured with the options.
    // getSnapshotUrl and abortUrl default to url+"/getSnapshot" and url+"/abort".
    // serverManaged defaults to true — set false for client-managed mode.
    RemoteAgentOptions opts =
        RemoteAgentOptions.builder()
            .url(agentUrl)
            // serverManaged(false) // uncomment for client-managed remote agent
            .build();

    // ── 3. Create the remote chat client ─────────────────────────────────────
    //
    // No Genkit instance is needed on the client side — RemoteAgent is a
    // pure HTTP client that speaks the Genkit agent wire format.
    AgentChat<Map<String, Object>> chat = RemoteAgent.chat(opts);

    // ── 4. Guard live network calls behind an env flag ───────────────────────
    //
    // The module must compile and start even without a running server.
    // Set REMOTE_AGENT_RUN=true to actually send requests.
    String runFlag = System.getenv("REMOTE_AGENT_RUN");
    if (!"true".equalsIgnoreCase(runFlag)) {
      System.out.println(
          "REMOTE_AGENT_RUN is not set to 'true' — RemoteAgent client created successfully "
              + "but skipping live network calls.");
      System.out.println(
          "Start the weather agent server and set REMOTE_AGENT_RUN=true to run the demo.");
      return;
    }

    // ── 5. Multi-turn remote chat ────────────────────────────────────────────
    System.out.println("=== Remote agent client ===");

    try {
      AgentResponse<Map<String, Object>> turn1 = chat.send("What is the weather in Tokyo?");
      System.out.println("Turn 1: " + turn1.text());

      AgentResponse<Map<String, Object>> turn2 = chat.send("And in London?");
      System.out.println("Turn 2: " + turn2.text());
    } catch (RuntimeException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      System.err.println("Remote call to " + agentUrl + " failed: " + msg);
      if (msg.contains("404") || msg.contains("Cannot POST") || msg.contains("Express")) {
        // A 404 / "Cannot POST" (Express) reply means the URL is NOT the Java agent server —
        // some other process answered. Almost always a port mismatch / the Java server didn't bind.
        System.err.println(
            "That looks like a different server answered (e.g. an Express dev server), not the Java"
                + " agent endpoint. Make sure the weather sample is running AND that its port"
                + " matches this URL.");
        System.err.println(
            "  - Server: look for `Jetty server started on 0.0.0.0:<port>` in its logs (if the port"
                + " was busy it fails to bind).");
        System.err.println(
            "  - Then point this client at the same port, e.g. AGENT_URL=http://localhost:<port>/weatherAgent");
      }
      throw e;
    }
  }
}
