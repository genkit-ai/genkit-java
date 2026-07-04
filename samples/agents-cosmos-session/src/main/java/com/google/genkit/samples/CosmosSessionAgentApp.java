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

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPlugin;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPluginOptions;
import com.google.genkit.plugins.azurefoundry.session.CosmosSessionStore;
import com.google.genkit.plugins.azurefoundry.session.CosmosSessionStoreOptions;
import com.google.genkit.plugins.jetty.JettyPlugin;
import java.util.Map;

/**
 * Agent session persistence backed by <b>Azure Cosmos DB</b>.
 *
 * <p>Defines a server-managed assistant whose conversation snapshots are stored in Cosmos DB via
 * {@link CosmosSessionStore}, and uses an Azure AI Foundry model for generation — a self-contained,
 * single-cloud sample.
 *
 * <p>Point it at the <a
 * href="https://learn.microsoft.com/azure/cosmos-db/how-to-develop-emulator">Cosmos DB emulator</a>
 * or a real account:
 *
 * <pre>
 *   export COSMOS_ENDPOINT=https://localhost:8081
 *   export COSMOS_KEY=&lt;emulator-or-account-key&gt;
 * </pre>
 *
 * <p>Live model calls additionally require {@code AZURE_AI_FOUNDRY_ENDPOINT} (and credentials).
 *
 * <p>Two run modes:
 *
 * <ul>
 *   <li><b>Serve (default)</b> — starts Jetty to expose the agent over HTTP and keep the process
 *       alive; also discoverable in the Genkit Dev UI. {@code POST
 *       http://localhost:8082/assistant}.
 *   <li><b>Demo</b> — runs a two-turn conversation then reads the persisted snapshot back from
 *       Cosmos DB to prove server-side persistence: {@code mvn -q exec:java -Dexec.args=demo}.
 * </ul>
 */
public class CosmosSessionAgentApp {

  public static void main(String[] args) throws Exception {
    // The model reference for Azure OpenAI is the *deployment* name. Set it to your deployment via
    // AZURE_MODEL. Defaults to "gpt-4o-mini".
    String model = azureModel();

    // ── 1. Build Genkit with the beta agents API + Azure AI Foundry model ────
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().experimental(true).devMode(true).build())
            .plugin(buildAzurePlugin(model))
            .build();

    // ── 2. Build the Cosmos-backed session store (null if not configured) ────
    SessionStore<Map<String, Object>> store = buildStore();

    // ── 3. Define a server-managed agent using the store ────────────────────
    AgentConfig.Builder<Map<String, Object>> cfg =
        AgentConfig.<Map<String, Object>>builder()
            .name("assistant")
            .description("A helpful assistant with Cosmos DB-backed memory")
            .system(
                "You are a helpful assistant. Keep answers concise and remember what the user tells you.")
            .model("azure-foundry/" + model);
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
          "Cosmos DB is not configured (set COSMOS_ENDPOINT and COSMOS_KEY) — "
              + "cannot demonstrate server-side persistence.");
      return;
    }
    try {
      System.out.println("=== Cosmos DB-backed session agent ===");
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
      System.out.println("Messages persisted in Cosmos DB for this session: " + messageCount);
    } catch (Exception e) {
      System.err.println(
          "Demo failed. It needs a reachable Cosmos DB and Azure AI Foundry credentials: "
              + e.getMessage());
    }
  }

  /**
   * Returns the Azure model/deployment name from {@code AZURE_MODEL}, defaulting to gpt-4o-mini.
   */
  private static String azureModel() {
    String m = System.getenv("AZURE_MODEL");
    return (m != null && !m.isBlank()) ? m : "gpt-4o-mini";
  }

  /**
   * Builds the Azure AI Foundry plugin. The endpoint comes from {@code AZURE_AI_FOUNDRY_ENDPOINT};
   * a placeholder is used when it is unset so the sample still boots in serve mode (live calls then
   * require a real endpoint + key). The {@code model} (deployment) is registered as a custom model
   * when it isn't one of the plugin's built-in names.
   */
  private static AzureFoundryPlugin buildAzurePlugin(String model) {
    String endpoint = System.getenv("AZURE_AI_FOUNDRY_ENDPOINT");
    String apiKey = System.getenv("AZURE_API_KEY");
    String apiVersion = System.getenv("AZURE_API_VERSION"); // optional; must match your resource
    if (endpoint == null || endpoint.isBlank()) {
      endpoint = "https://placeholder.openai.azure.com";
    }
    AzureFoundryPluginOptions.Builder options =
        AzureFoundryPluginOptions.builder()
            .endpoint(endpoint)
            .apiKey(apiKey != null ? apiKey : "placeholder-key");
    // Azure returns 400 "API version not supported" when the api-version doesn't match the
    // resource. The plugin default is 2024-10-01-preview; override it via AZURE_API_VERSION
    // (e.g. 2024-10-21 for a GA Azure OpenAI resource) if your endpoint rejects the default.
    if (apiVersion != null && !apiVersion.isBlank()) {
      options.apiVersion(apiVersion);
    }
    AzureFoundryPlugin plugin = new AzureFoundryPlugin(options.build());
    // Azure OpenAI routes by deployment name; register non-built-in deployment names as custom
    // models (guarding against double-registration of a built-in name).
    if (!AzureFoundryPlugin.SUPPORTED_MODELS.contains(model)) {
      plugin.customModel(model);
    }
    return plugin;
  }

  /**
   * Builds a Cosmos DB-backed session store, or returns {@code null} (running client-managed) when
   * Cosmos DB is not configured / cannot be reached.
   */
  private static SessionStore<Map<String, Object>> buildStore() {
    String endpoint = System.getenv("COSMOS_ENDPOINT");
    String key = System.getenv("COSMOS_KEY");
    if (endpoint == null || endpoint.isBlank() || key == null || key.isBlank()) {
      System.out.println(
          "Cosmos DB not configured (set COSMOS_ENDPOINT and COSMOS_KEY); running client-managed.");
      return null;
    }
    try {
      CosmosClient client =
          new CosmosClientBuilder().endpoint(endpoint).key(key).gatewayMode().buildClient();
      return new CosmosSessionStore<>(
          client, CosmosSessionStoreOptions.builder().createIfNotExists(true).build());
    } catch (Exception e) {
      System.out.println(
          "Cosmos DB not reachable (" + e.getMessage() + "); running client-managed.");
      return null;
    }
  }

  /** Starts Jetty to serve the agent over HTTP and blocks until the process is stopped. */
  private static void serve(Genkit genkit) throws Exception {
    int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8082;
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
