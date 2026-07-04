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

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.plugins.firebase.session.FirestoreSessionStore;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import java.util.Map;

/**
 * Agent session persistence backed by <b>Firestore</b>.
 *
 * <p>Defines a server-managed assistant whose conversation snapshots are stored in Firestore via
 * {@link FirestoreSessionStore}. Because the state lives in Firestore (not in the process), the
 * conversation survives restarts and can be resumed from any instance.
 *
 * <p>Point it at the Firestore emulator for local development:
 *
 * <pre>
 *   gcloud emulators firestore start --host-port=localhost:8080
 *   export FIRESTORE_EMULATOR_HOST=localhost:8080
 * </pre>
 *
 * or at a real project by setting {@code GCLOUD_PROJECT} (and application default credentials).
 *
 * <p>Two run modes:
 *
 * <ul>
 *   <li><b>Serve (default)</b> — starts Jetty to expose the agent over HTTP and keep the process
 *       alive; also discoverable in the Genkit Dev UI. {@code POST
 *       http://localhost:8080/assistant}.
 *   <li><b>Demo</b> — runs a two-turn conversation then reads the persisted snapshot back from
 *       Firestore to prove server-side persistence. Requires {@code GEMINI_API_KEY} and a
 *       configured Firestore: {@code mvn -q exec:java -Dexec.args=demo}.
 * </ul>
 */
public class FirestoreSessionAgentApp {

  public static void main(String[] args) throws Exception {
    // ── 1. Build Genkit with the beta agents API enabled ────────────────────
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().experimental(true).devMode(true).build())
            .plugin(GoogleGenAIPlugin.create())
            .build();

    // ── 2. Build the Firestore-backed session store (null if not configured) ─
    SessionStore<Map<String, Object>> store = buildStore();

    // ── 3. Define a server-managed agent using the store ────────────────────
    AgentConfig.Builder<Map<String, Object>> cfg =
        AgentConfig.<Map<String, Object>>builder()
            .name("assistant")
            .description("A helpful assistant with Firestore-backed memory")
            .system(
                "You are a helpful assistant. Keep answers concise and remember what the user tells you.")
            .model("googleai/gemini-2.5-flash");
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

    // ── 5. Demo mode: two turns + read-back proof (needs GEMINI_API_KEY) ─────
    String apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.out.println(
          "GEMINI_API_KEY is not set — agent defined successfully but skipping live calls.");
      return;
    }
    if (store == null) {
      System.out.println(
          "Firestore is not configured (set FIRESTORE_EMULATOR_HOST or GCLOUD_PROJECT) — "
              + "cannot demonstrate server-side persistence.");
      return;
    }

    System.out.println("=== Firestore-backed session agent ===");
    AgentChat<Map<String, Object>> chat = assistant.chat();

    AgentResponse<Map<String, Object>> r1 =
        chat.send("My name is Ada Lovelace. Please remember it.");
    System.out.println("Turn 1: " + r1.text());
    AgentResponse<Map<String, Object>> r2 = chat.send("What is my name?");
    System.out.println("Turn 2: " + r2.text());

    String sessionId = chat.sessionId();
    System.out.println("Session id: " + sessionId);

    // Read the latest snapshot straight from Firestore to prove it was persisted.
    SessionSnapshot<Map<String, Object>> persisted =
        store.getSnapshot(GetSnapshotOptions.builder().sessionId(sessionId).build());
    int messageCount =
        (persisted != null
                && persisted.getState() != null
                && persisted.getState().getMessages() != null)
            ? persisted.getState().getMessages().size()
            : 0;
    System.out.println("Messages persisted in Firestore for this session: " + messageCount);
  }

  /**
   * Builds a Firestore-backed session store, or returns {@code null} (running client-managed) when
   * Firestore is not configured. Construction is gated on a config signal because building a
   * Firestore client eagerly resolves application-default credentials (which can block probing the
   * metadata server when nothing is configured), so serve mode stays fast without configuration.
   */
  private static SessionStore<Map<String, Object>> buildStore() {
    String emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST");
    String projectId = System.getenv("GCLOUD_PROJECT");
    if (projectId == null || projectId.isBlank()) {
      projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
    }
    boolean hasEmulator = emulatorHost != null && !emulatorHost.isBlank();
    boolean hasProject = projectId != null && !projectId.isBlank();
    if (!hasEmulator && !hasProject) {
      System.out.println(
          "Firestore not configured (set FIRESTORE_EMULATOR_HOST or GCLOUD_PROJECT); running"
              + " client-managed.");
      return null;
    }
    try {
      String effectiveProject = hasProject ? projectId : "demo-genkit";
      Firestore firestore;
      if (hasEmulator) {
        firestore =
            FirestoreOptions.getDefaultInstance().toBuilder()
                .setProjectId(effectiveProject)
                .setEmulatorHost(emulatorHost)
                .build()
                .getService();
      } else {
        firestore =
            FirestoreOptions.newBuilder().setProjectId(effectiveProject).build().getService();
      }
      return new FirestoreSessionStore<>(firestore);
    } catch (Exception e) {
      System.out.println(
          "Firestore not reachable (" + e.getMessage() + "); running client-managed.");
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
