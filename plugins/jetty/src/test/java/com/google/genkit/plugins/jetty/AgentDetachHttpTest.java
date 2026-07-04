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

package com.google.genkit.plugins.jetty;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.core.DefaultRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test verifying the detach feature works over the HTTP wire using JettyPlugin.
 *
 * <p>Proves that a DETACH turn works through the Jetty agent HTTP endpoint: POST a turn with {@code
 * detach:true} → server returns {@code finishReason: "detached"} + a pending {@code snapshotId}
 * immediately → the background work finalizes → polling the {@code getSnapshot} companion shows
 * {@code status: "completed"} with the accumulated state.
 */
class AgentDetachHttpTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JettyPlugin plugin;

  @AfterEach
  void tearDown() throws Exception {
    if (plugin != null) {
      plugin.stop();
    }
  }

  /**
   * Starts a JettyPlugin on the given port with a server-managed custom agent named {@code
   * detachAgent} that returns immediately (so background work finalizes quickly).
   */
  private void startWithDetachAgent(int port) throws Exception {
    DefaultRegistry registry = new DefaultRegistry();

    // Register a server-managed custom agent. The AgentFn returns immediately with a STOP
    // finish reason — detach background work will finalize quickly.
    AgentActions.defineCustomAgent(
        registry,
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("detachAgent")
            .store(new InMemorySessionStore<>())
            .build(),
        (sess, fnCtx) ->
            AgentResult.builder()
                .message(Message.model("done"))
                .finishReason(AgentFinishReason.STOP)
                .build());

    JettyPluginOptions options = JettyPluginOptions.builder().port(port).build();
    plugin = new JettyPlugin(options);
    plugin.init(registry);

    Thread serverThread =
        new Thread(
            () -> {
              try {
                plugin.start();
              } catch (Exception e) {
                // Server stopped.
              }
            });
    serverThread.setDaemon(true);
    serverThread.start();

    waitForServer(port);
  }

  /**
   * Integration test: POST with detach:true → assert finishReason=="detached" + snapshotId → poll
   * getSnapshot until status=="completed" → assert messages non-empty.
   */
  @Test
  void testDetachOverHttp() throws Exception {
    int port = findAvailablePort();
    startWithDetachAgent(port);

    HttpClient client = HttpClient.newHttpClient();

    // Step 1: POST a detach turn.
    String detachBody =
        "{\"data\":{\"detach\":true,\"message\":{\"role\":\"user\","
            + "\"content\":[{\"text\":\"go\"}]}},\"init\":{}}";

    HttpRequest detachRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/detachAgent"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(detachBody))
            .build();

    HttpResponse<String> detachResponse =
        client.send(detachRequest, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, detachResponse.statusCode(), "detach POST body: " + detachResponse.body());

    JsonNode detachRoot = MAPPER.readTree(detachResponse.body());
    assertTrue(detachRoot.has("result"), "expected result envelope, got: " + detachResponse.body());

    JsonNode result = detachRoot.path("result");

    // Assert: finishReason is "detached".
    String finishReason = result.path("finishReason").asText("");
    assertEquals(
        "detached",
        finishReason,
        "expected finishReason=detached, got: " + finishReason + " body: " + detachResponse.body());

    // Assert: snapshotId is present and non-empty.
    String snapshotId = result.path("snapshotId").asText("");
    assertFalse(
        snapshotId.isEmpty(),
        "expected non-empty snapshotId, got: " + snapshotId + " body: " + detachResponse.body());

    // Step 2: Poll getSnapshot until status becomes "completed" (or timeout at 5s).
    String getSnapshotBody = "{\"data\":{\"snapshotId\":\"" + snapshotId + "\"}}";
    HttpRequest snapshotRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/detachAgent/getSnapshot"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(getSnapshotBody))
            .build();

    JsonNode snapResult = null;
    String snapStatus = "";
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      HttpResponse<String> snapResponse =
          client.send(snapshotRequest, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, snapResponse.statusCode(), "getSnapshot body: " + snapResponse.body());

      JsonNode snapRoot = MAPPER.readTree(snapResponse.body());
      assertTrue(
          snapRoot.has("result"),
          "expected result envelope from getSnapshot, got: " + snapResponse.body());

      snapResult = snapRoot.path("result");
      snapStatus = snapResult.path("status").asText("");

      if ("completed".equals(snapStatus)
          || "failed".equals(snapStatus)
          || "aborted".equals(snapStatus)) {
        break;
      }
      Thread.sleep(100);
    }

    // Assert: snapshot finalized to "completed".
    assertNotNull(snapResult, "snapResult should not be null");
    assertEquals(
        "completed",
        snapStatus,
        "expected snapshot status=completed, got: " + snapStatus + " snap: " + snapResult);

    // Assert: state.messages is non-empty (background turn ran and accumulated messages).
    JsonNode messages = snapResult.path("state").path("messages");
    assertFalse(
        messages.isMissingNode() || messages.isEmpty(),
        "expected non-empty state.messages in completed snapshot, got: " + snapResult);
  }

  private static int findAvailablePort() throws IOException {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void waitForServer(int port) throws Exception {
    int maxRetries = 50;
    for (int i = 0; i < maxRetries; i++) {
      try {
        HttpURLConnection conn =
            (HttpURLConnection) new URL("http://localhost:" + port + "/health").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(200);
        conn.setReadTimeout(200);
        if (conn.getResponseCode() == 200) {
          return;
        }
      } catch (IOException e) {
        // Server not ready yet.
      }
      Thread.sleep(100);
    }
    fail("Server did not start within timeout");
  }
}
