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

package com.google.genkit.plugins.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.core.ActionDef;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.BidiActionImpl;
import com.google.genkit.core.DefaultRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for agent HTTP serving in the Spring plugin.
 *
 * <p>Mirrors {@code plugins/jetty}'s {@code AgentHttpServingTest}: verifies the
 * one-turn-per-request transport for {@code ActionType.AGENT} bidi actions served by {@link
 * GenkitAgentController} — a non-streaming {@code {data, init}} request, an SSE streaming request,
 * and a companion {@code getSnapshot} endpoint — proving wire-format parity between the Spring and
 * Jetty server plugins.
 */
class AgentHttpServingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SpringPlugin plugin;

  @AfterEach
  void tearDown() throws Exception {
    if (plugin != null) {
      plugin.stop();
    }
  }

  /**
   * Builds a registry containing a server-managed bidi agent named {@code chatAgent} plus a
   * companion {@code agent-snapshot} action, then starts a SpringPlugin on the given port.
   */
  private void startWithChatAgent(int port) throws Exception {
    DefaultRegistry registry = new DefaultRegistry();

    // A minimal server-managed bidi agent: reads one input, emits one stream chunk, returns a
    // final output containing snapshotId + an echoed message.
    BidiActionImpl<JsonNode, JsonNode, JsonNode, JsonNode> agent =
        BidiActionImpl.<JsonNode, JsonNode, JsonNode, JsonNode>builder()
            .name("chatAgent")
            .inputClass(JsonNode.class)
            .outputClass(JsonNode.class)
            .streamClass(JsonNode.class)
            .initClass(JsonNode.class)
            .handler(
                (ctx, init, inputs, cb) -> {
                  Optional<JsonNode> first = inputs.next();
                  JsonNode data = first.orElse(MAPPER.nullNode());
                  // The AgentInput envelope carries the turn message under "message".
                  JsonNode message = data.path("message");
                  // Emit one streamed chunk.
                  if (cb != null) {
                    ObjectNode chunk = MAPPER.createObjectNode();
                    chunk.put("text", "thinking...");
                    cb.accept(chunk);
                  }
                  // Final output echoes the message back.
                  ObjectNode result = MAPPER.createObjectNode();
                  result.put("snapshotId", "s1");
                  result.set("message", message);
                  return result;
                })
            .build();
    agent.register(registry);

    // Companion agent-snapshot action looked up at key "/agent-snapshot/chatAgent".
    ActionDef<JsonNode, JsonNode, Void> snapshot =
        new ActionDef<>(
            "chatAgent",
            ActionType.AGENT_SNAPSHOT,
            null,
            null,
            JsonNode.class,
            JsonNode.class,
            (ctx, input, cb) -> {
              ObjectNode snap = MAPPER.createObjectNode();
              snap.put("snapshotId", input != null ? input.path("snapshotId").asText("?") : "?");
              snap.put("status", "captured");
              return snap;
            });
    snapshot.register(registry);

    SpringPluginOptions options = SpringPluginOptions.builder().port(port).build();
    plugin = new SpringPlugin(options);
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

  @Test
  void testAgentNonStreaming() throws Exception {
    int port = findAvailablePort();
    startWithChatAgent(port);

    String body =
        "{\"data\":{\"message\":{\"role\":\"user\",\"content\":[{\"text\":\"hi\"}]}},\"init\":{}}";

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/chatAgent"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "body: " + response.body());

    JsonNode root = MAPPER.readTree(response.body());
    assertTrue(root.has("result"), "expected result envelope: " + response.body());
    assertEquals("s1", root.path("result").path("snapshotId").asText());
    // Echoed message should be present.
    assertEquals(
        "hi", root.path("result").path("message").path("content").path(0).path("text").asText());
  }

  @Test
  void testAgentStreamingSse() throws Exception {
    int port = findAvailablePort();
    startWithChatAgent(port);

    String body =
        "{\"data\":{\"message\":{\"role\":\"user\",\"content\":[{\"text\":\"hi\"}]}},\"init\":{}}";

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/chatAgent"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "body: " + response.body());

    String contentType = response.headers().firstValue("Content-Type").orElse("");
    assertTrue(contentType.contains("text/event-stream"), "content-type: " + contentType);
    assertTrue(
        response.headers().firstValue("X-Genkit-Stream-Id").isPresent(),
        "expected X-Genkit-Stream-Id header");

    String text = response.body();
    // A message frame then a result frame.
    assertTrue(text.contains("\"message\""), "expected a message frame: " + text);
    assertTrue(text.contains("thinking..."), "expected streamed chunk text: " + text);
    assertTrue(text.contains("\"result\""), "expected a result frame: " + text);
    assertTrue(text.contains("\"snapshotId\":\"s1\""), "expected snapshotId in result: " + text);
    // Frames are SSE-formatted.
    assertTrue(text.contains("data:"), "expected SSE data prefix: " + text);
  }

  @Test
  void testAgentSnapshotCompanion() throws Exception {
    int port = findAvailablePort();
    startWithChatAgent(port);

    String body = "{\"data\":{\"snapshotId\":\"s1\"}}";

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/chatAgent/getSnapshot"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "body: " + response.body());

    JsonNode root = MAPPER.readTree(response.body());
    assertTrue(root.has("result"), "expected result envelope: " + response.body());
    assertEquals("s1", root.path("result").path("snapshotId").asText());
    assertEquals("captured", root.path("result").path("status").asText());
  }

  private static int findAvailablePort() throws IOException {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void waitForServer(int port) throws Exception {
    int maxRetries = 100;
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
