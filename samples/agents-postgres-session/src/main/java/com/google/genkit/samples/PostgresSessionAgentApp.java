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
import com.google.genkit.plugins.postgresql.session.PostgresSessionStore;
import com.google.genkit.plugins.postgresql.session.PostgresSessionStoreOptions;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Agent session persistence backed by <b>PostgreSQL</b>.
 *
 * <p>Defines a server-managed assistant whose conversation snapshots are stored in PostgreSQL via
 * {@link PostgresSessionStore}. Because the state lives in the database (not in the process), the
 * conversation survives restarts and can be resumed from any instance.
 *
 * <p>Point it at a local Postgres (see the README for a one-line Docker command):
 *
 * <pre>
 *   export POSTGRES_URL=jdbc:postgresql://localhost:5432/genkit
 *   export POSTGRES_USER=postgres
 *   export POSTGRES_PASSWORD=postgres
 *   export GEMINI_API_KEY=&lt;your-key&gt;
 * </pre>
 *
 * <p>Starts Jetty to expose the agent over HTTP and keep the process alive; also discoverable in
 * the Genkit Dev UI. {@code POST http://localhost:8083/assistant}.
 */
public class PostgresSessionAgentApp {

  public static void main(String[] args) throws Exception {
    // ── 1. Build Genkit with the beta agents API enabled ────────────────────
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().experimental(true).devMode(true).build())
            .plugin(GoogleGenAIPlugin.create())
            .build();

    // ── 2. Build the Postgres-backed session store (null if not configured) ──
    SessionStore<Map<String, Object>> store = buildStore();

    // ── 3. Define a server-managed agent using the store ────────────────────
    AgentConfig.Builder<Map<String, Object>> cfg =
        AgentConfig.<Map<String, Object>>builder()
            .name("assistant")
            .description("A helpful assistant with PostgreSQL-backed memory")
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
   * Builds a PostgreSQL-backed session store, or returns {@code null} (running client-managed) when
   * PostgreSQL is not configured / cannot be reached. Reads {@code POSTGRES_URL} (JDBC URL, default
   * {@code jdbc:postgresql://localhost:5432/genkit}), {@code POSTGRES_USER} (default {@code
   * postgres}), and {@code POSTGRES_PASSWORD} (default {@code postgres}).
   */
  private static SessionStore<Map<String, Object>> buildStore() {
    String url = System.getenv("POSTGRES_URL");
    if (url == null || url.isBlank()) {
      System.out.println(
          "PostgreSQL not configured (set POSTGRES_URL); running client-managed. See the README"
              + " for a one-line Docker command.");
      return null;
    }
    String user = envOrDefault("POSTGRES_USER", "postgres");
    String password = envOrDefault("POSTGRES_PASSWORD", "postgres");
    try {
      PGSimpleDataSource ds = new PGSimpleDataSource();
      ds.setUrl(url);
      ds.setUser(user);
      ds.setPassword(password);
      DataSource dataSource = ds;
      return new PostgresSessionStore<>(
          dataSource, PostgresSessionStoreOptions.builder().createTableIfNotExists(true).build());
    } catch (Exception e) {
      System.out.println(
          "PostgreSQL not reachable (" + e.getMessage() + "); running client-managed.");
      return null;
    }
  }

  private static String envOrDefault(String name, String fallback) {
    String v = System.getenv(name);
    return (v != null && !v.isBlank()) ? v : fallback;
  }

  /** Starts Jetty to serve the agent over HTTP and blocks until the process is stopped. */
  private static void serve(Genkit genkit) throws Exception {
    int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8083;
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
