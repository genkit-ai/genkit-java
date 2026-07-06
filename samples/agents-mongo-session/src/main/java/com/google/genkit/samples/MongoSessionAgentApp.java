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
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.mongodb.session.MongoSessionStore;
import com.google.genkit.plugins.mongodb.session.MongoSessionStoreOptions;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.Map;

/**
 * Agent session persistence backed by <b>MongoDB</b>.
 *
 * <p>Defines a server-managed assistant whose conversation snapshots are stored in MongoDB via
 * {@link MongoSessionStore}. Because the state lives in the database (not in the process), the
 * conversation survives restarts and can be resumed from any instance.
 *
 * <p>Point it at a local MongoDB (see the README for a one-line Docker command):
 *
 * <pre>
 *   export MONGO_URI=mongodb://localhost:27017
 *   export GEMINI_API_KEY=&lt;your-key&gt;
 * </pre>
 *
 * <p>Starts Jetty to expose the agent over HTTP and keep the process alive; also discoverable in
 * the Genkit Dev UI. {@code POST http://localhost:8084/assistant}.
 */
public class MongoSessionAgentApp {

  public static void main(String[] args) throws Exception {
    // ── 1. Build Genkit with the beta agents API enabled ────────────────────
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().experimental(true).devMode(true).build())
            .plugin(GoogleGenAIPlugin.create())
            .build();

    // ── 2. Build the MongoDB-backed session store (null if not configured) ───
    SessionStore<Map<String, Object>> store = buildStore();

    // ── 3. Define a server-managed agent using the store ────────────────────
    AgentConfig.Builder<Map<String, Object>> cfg =
        AgentConfig.<Map<String, Object>>builder()
            .name("assistant")
            .description("A helpful assistant with MongoDB-backed memory")
            .system(
                "You are a helpful assistant. Keep answers concise and remember what the user tells you.")
            .model("googleai/gemini-2.5-flash");
    if (store != null) {
      cfg.store(store);
    }
    genkit.beta().defineAgent(cfg.build());

    // ── 4. Serve over HTTP + keep the process alive ─────────────────────────
    serve(genkit);
  }

  /**
   * Builds a MongoDB-backed session store, or returns {@code null} (running client-managed) when
   * MongoDB is not configured / cannot be reached. Reads {@code MONGO_URI} (connection string, e.g.
   * {@code mongodb://localhost:27017}).
   */
  private static SessionStore<Map<String, Object>> buildStore() {
    String uri = System.getenv("MONGO_URI");
    if (uri == null || uri.isBlank()) {
      System.out.println(
          "MongoDB not configured (set MONGO_URI); running client-managed. See the README for a"
              + " one-line Docker command.");
      return null;
    }
    try {
      MongoClient client = MongoClients.create(uri);
      return new MongoSessionStore<>(client, MongoSessionStoreOptions.defaults());
    } catch (Exception e) {
      System.out.println("MongoDB not reachable (" + e.getMessage() + "); running client-managed.");
      return null;
    }
  }

  /** Starts Jetty to serve the agent over HTTP and blocks until the process is stopped. */
  private static void serve(Genkit genkit) throws Exception {
    int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8084;
    JettyPlugin jetty = JettyPlugin.create(port);
    jetty.init(genkit.getRegistry());
    System.out.println("Serving the assistant on http://localhost:" + port);
    System.out.println("  assistant -> POST http://localhost:" + port + "/assistant");
    System.out.println("Tip: run under `genkit start -- mvn -q exec:java` to open the Dev UI.");
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
