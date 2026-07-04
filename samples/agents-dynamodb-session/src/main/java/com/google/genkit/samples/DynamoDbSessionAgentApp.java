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
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.plugins.awsbedrock.AwsBedrockPlugin;
import com.google.genkit.plugins.awsbedrock.session.DynamoDbSessionStore;
import com.google.genkit.plugins.awsbedrock.session.DynamoDbSessionStoreOptions;
import com.google.genkit.plugins.jetty.JettyPlugin;
import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Agent session persistence backed by <b>Amazon DynamoDB</b>.
 *
 * <p>Defines a server-managed assistant whose conversation snapshots are stored in DynamoDB via
 * {@link DynamoDbSessionStore}, and uses an AWS Bedrock model for generation — a self-contained,
 * single-cloud sample.
 *
 * <p>Point it at <a href="https://hub.docker.com/r/amazon/dynamodb-local">DynamoDB Local</a> for
 * development:
 *
 * <pre>
 *   docker run -p 8000:8000 amazon/dynamodb-local
 *   export DYNAMODB_LOCAL_ENDPOINT=http://localhost:8000
 * </pre>
 *
 * or at real AWS via the default credential chain (the {@code genkit-sessions} table must exist).
 * Live model calls always require AWS credentials for Bedrock.
 *
 * <p>Two run modes:
 *
 * <ul>
 *   <li><b>Serve (default)</b> — starts Jetty to expose the agent over HTTP and keep the process
 *       alive; also discoverable in the Genkit Dev UI. {@code POST
 *       http://localhost:8080/assistant}.
 *   <li><b>Demo</b> — runs a two-turn conversation then reads the persisted snapshot back from
 *       DynamoDB to prove server-side persistence: {@code mvn -q -pl
 *       samples/agents-dynamodb-session exec:java -Dexec.args=demo}.
 * </ul>
 */
public class DynamoDbSessionAgentApp {

  public static void main(String[] args) throws Exception {
    // ── 1. Build Genkit with the beta agents API + AWS Bedrock model plugin ──
    //
    // Newer Claude models on Bedrock (Sonnet 4.6, Opus 4.x, Sonnet 5, ...) cannot be invoked with
    // on-demand throughput — they require a cross-region *inference profile* ID (a geo prefix like
    // "us.", "eu.", "jp.", "au."). We register the US profile ID as a custom model so it can be
    // referenced below. Change the prefix to match your region, or use a base model that supports
    // on-demand invocation in your region.
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().experimental(true).devMode(true).build())
            .plugin(AwsBedrockPlugin.create().customModel("us.anthropic.claude-sonnet-4-6"))
            .build();

    // ── 2. Build the DynamoDB-backed session store (null if not configured) ──
    SessionStore<Map<String, Object>> store = buildStore();

    // ── 3. Define a server-managed agent using the store ────────────────────
    AgentConfig.Builder<Map<String, Object>> cfg =
        AgentConfig.<Map<String, Object>>builder()
            .name("assistant")
            .description("A helpful assistant with DynamoDB-backed memory")
            .system(
                "You are a helpful assistant. Keep answers concise and remember what the user tells you.")
            .model("aws-bedrock/us.anthropic.claude-sonnet-4-6");
    if (store != null) {
      cfg.store(store);
    }
    Agent<Map<String, Object>> assistant = genkit.beta().defineAgent(cfg.build());

    // ── 4. Default mode: serve over HTTP + keep the process alive ───────────
    boolean runDemo = args.length > 0 && "demo".equalsIgnoreCase(args[0]);
    if (!runDemo) {
      serve(genkit);
      return;
    }

    // ── 5. Demo mode: two turns + read-back proof ────────────────────────────
    if (store == null) {
      System.out.println(
          "DynamoDB is not configured (set DYNAMODB_LOCAL_ENDPOINT for local, or AWS credentials"
              + " with an existing table) — cannot demonstrate server-side persistence.");
      return;
    }
    try {
      System.out.println("=== DynamoDB-backed session agent ===");
      AgentChat<Map<String, Object>> chat = assistant.chat();

      AgentResponse<Map<String, Object>> r1 =
          chat.send("My name is Ada Lovelace. Please remember it.");
      System.out.println("Turn 1: " + r1.text());
      AgentResponse<Map<String, Object>> r2 = chat.send("What is my name?");
      System.out.println("Turn 2: " + r2.text());

      String sessionId = chat.sessionId();
      System.out.println("Session id: " + sessionId);

      SessionSnapshot<Map<String, Object>> persisted =
          store.getSnapshot(GetSnapshotOptions.builder().sessionId(sessionId).build());
      int messageCount =
          (persisted != null
                  && persisted.getState() != null
                  && persisted.getState().getMessages() != null)
              ? persisted.getState().getMessages().size()
              : 0;
      System.out.println("Messages persisted in DynamoDB for this session: " + messageCount);
    } catch (Exception e) {
      System.err.println(
          "Demo failed. It needs AWS credentials for Bedrock and a reachable DynamoDB "
              + "(set DYNAMODB_LOCAL_ENDPOINT for local): "
              + e.getMessage());
    }
  }

  /**
   * Builds a DynamoDB-backed session store. Building the client is offline; the table is
   * auto-created only when running against DynamoDB Local ({@code DYNAMODB_LOCAL_ENDPOINT} set), so
   * serve mode starts without touching AWS.
   */
  private static SessionStore<Map<String, Object>> buildStore() {
    try {
      String localEndpoint = System.getenv("DYNAMODB_LOCAL_ENDPOINT");
      String region = System.getenv("AWS_REGION");
      if (region == null || region.isBlank()) {
        region = "us-east-1";
      }
      DynamoDbClient client;
      boolean autoCreate = false;
      if (localEndpoint != null && !localEndpoint.isBlank()) {
        client =
            DynamoDbClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(localEndpoint))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        autoCreate = true; // safe to auto-create against DynamoDB Local
      } else {
        client = DynamoDbClient.builder().region(Region.of(region)).build();
      }
      return new DynamoDbSessionStore<>(
          client, DynamoDbSessionStoreOptions.builder().createTableIfNotExists(autoCreate).build());
    } catch (Exception e) {
      System.out.println(
          "DynamoDB not configured (" + e.getMessage() + "); running client-managed.");
      return null;
    }
  }

  /** Starts Jetty to serve the agent over HTTP and blocks until the process is stopped. */
  private static void serve(Genkit genkit) throws Exception {
    int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
    JettyPlugin jetty = JettyPlugin.create(port);
    jetty.init(genkit.getRegistry());
    System.out.println("Serving the assistant on http://localhost:" + port);
    System.out.println("  assistant -> POST http://localhost:" + port + "/assistant");
    System.out.println(
        "Tip: run under `genkit start -- mvn -q exec:java`"
            + " to open the Dev UI, or pass `demo` for the persistence demo.");
    try {
      jetty.start(); // blocks until the process is stopped (Ctrl-C)
    } catch (Exception e) {
      System.err.println(
          "ERROR: could not start the HTTP server on port " + port + ": " + e.getMessage());
      System.err.println("Port " + port + " is likely already in use. Free it or set PORT.");
      throw e;
    }
  }
}
