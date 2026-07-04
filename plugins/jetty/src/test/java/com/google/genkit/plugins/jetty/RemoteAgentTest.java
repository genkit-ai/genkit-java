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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.GetSnapshotRequest;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.client.HttpAgentTransport;
import com.google.genkit.client.RemoteAgent;
import com.google.genkit.client.RemoteAgentOptions;
import com.google.genkit.core.ActionDef;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.BidiActionImpl;
import com.google.genkit.core.DefaultRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link RemoteAgent} / {@link HttpAgentTransport} against a live Jetty
 * server running a server-managed echo agent.
 *
 * <p>Placed in the {@code plugins/jetty} module because that module depends on {@code genkit}
 * (which contains {@code RemoteAgent}) and can also start a {@link JettyPlugin} — giving us both
 * halves of the test in one module without a circular dependency.
 */
class RemoteAgentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JettyPlugin plugin;

  @AfterEach
  void tearDown() throws Exception {
    if (plugin != null) {
      plugin.stop();
    }
  }

  // ---------------------------------------------------------------------------
  // Server setup
  // ---------------------------------------------------------------------------

  /**
   * Starts a Jetty server on {@code port} hosting a server-managed echo agent named {@code
   * echoAgent}.
   *
   * <p>The agent:
   *
   * <ul>
   *   <li>Emits one SSE chunk with {@code {"text":"streaming..."}}.
   *   <li>Returns a final output whose {@code snapshotId} is {@code "snap-<turn>"} (turn counter
   *       increments on each invocation), {@code sessionId} is {@code "session-1"}, and {@code
   *       message} echoes the user message content back.
   * </ul>
   *
   * <p>A companion {@code agent-snapshot} action is registered so {@code getSnapshot} works.
   */
  private void startEchoAgent(int port) throws Exception {
    DefaultRegistry registry = new DefaultRegistry();

    AtomicInteger turnCounter = new AtomicInteger(0);

    BidiActionImpl<JsonNode, JsonNode, JsonNode, JsonNode> agent =
        BidiActionImpl.<JsonNode, JsonNode, JsonNode, JsonNode>builder()
            .name("echoAgent")
            .inputClass(JsonNode.class)
            .outputClass(JsonNode.class)
            .streamClass(JsonNode.class)
            .initClass(JsonNode.class)
            .handler(
                (ctx, init, inputs, cb) -> {
                  Optional<JsonNode> first = inputs.next();
                  JsonNode data = first.orElse(MAPPER.nullNode());
                  JsonNode messageNode = data.path("message");

                  int turn = turnCounter.incrementAndGet();

                  // Emit one streamed chunk.
                  if (cb != null) {
                    ObjectNode chunk = MAPPER.createObjectNode();
                    chunk.put("text", "streaming...");
                    cb.accept(chunk);
                  }

                  // Build final output.
                  ObjectNode result = MAPPER.createObjectNode();
                  result.put("snapshotId", "snap-" + turn);
                  result.put("sessionId", "session-1");
                  result.put("finishReason", AgentFinishReason.STOP.getValue());
                  // Echo message back.
                  ObjectNode message = MAPPER.createObjectNode();
                  message.put("role", "model");
                  ObjectNode part = MAPPER.createObjectNode();
                  // Echo the first text part of the user message.
                  String userText =
                      messageNode.path("content").path(0).path("text").asText("(empty)");
                  part.put("text", "echo: " + userText);
                  message.putArray("content").add(part);
                  result.set("message", message);
                  return result;
                })
            .build();
    agent.register(registry);

    // Companion snapshot action.
    ActionDef<JsonNode, JsonNode, Void> snapshot =
        new ActionDef<>(
            "echoAgent",
            ActionType.AGENT_SNAPSHOT,
            null,
            null,
            JsonNode.class,
            JsonNode.class,
            (ctx, input, cb) -> {
              ObjectNode snap = MAPPER.createObjectNode();
              String sid =
                  (input != null && input.has("snapshotId"))
                      ? input.get("snapshotId").asText("?")
                      : "?";
              snap.put("snapshotId", sid);
              snap.put("sessionId", "session-1");
              snap.put("status", "completed");
              return snap;
            });
    snapshot.register(registry);

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

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  void testSendFirstTurn() throws Exception {
    int port = findAvailablePort();
    startEchoAgent(port);

    AgentChat<Map<String, Object>> chat =
        RemoteAgent.chat(
            RemoteAgentOptions.builder().url("http://localhost:" + port + "/echoAgent").build());

    AgentResponse<Map<String, Object>> resp = chat.send("hello");

    // Response text must be non-empty (echoed user message).
    assertNotNull(resp.text(), "expected non-null text");
    assertFalse(resp.text().isEmpty(), "expected non-empty text, got: " + resp.text());
    assertTrue(resp.text().contains("echo: hello"), "expected echo in text, got: " + resp.text());

    // snapshotId must be set after the first turn.
    assertNotNull(chat.snapshotId(), "expected snapshotId to be set after first turn");
    assertEquals("snap-1", chat.snapshotId());
  }

  @Test
  void testSendSecondTurnResumes() throws Exception {
    int port = findAvailablePort();
    startEchoAgent(port);

    AgentChat<Map<String, Object>> chat =
        RemoteAgent.chat(
            RemoteAgentOptions.builder().url("http://localhost:" + port + "/echoAgent").build());

    chat.send("first");
    AgentResponse<Map<String, Object>> resp2 = chat.send("second");

    // Second turn should have incremented the counter.
    assertEquals("snap-2", chat.snapshotId());
    assertTrue(
        resp2.text().contains("echo: second"), "expected echo of second turn: " + resp2.text());
  }

  @Test
  void testStreamingChunksDelivered() throws Exception {
    int port = findAvailablePort();
    startEchoAgent(port);

    AgentChat<Map<String, Object>> chat =
        RemoteAgent.chat(
            RemoteAgentOptions.builder().url("http://localhost:" + port + "/echoAgent").build());

    List<String> chunks = new ArrayList<>();
    chat.sendStream(
        "streaming test",
        chunk -> {
          // Chunks may contain a modelChunk; collect whatever the server sends.
          chunks.add(chunk.toString());
        });

    // The server emits one streaming chunk; the onChunk callback must have been called.
    // (AgentStreamChunk is not a plain text — it wraps modelChunk; we just verify it fired.)
    assertFalse(chunks.isEmpty(), "expected at least one SSE chunk");
  }

  @Test
  void testGetSnapshot() throws Exception {
    int port = findAvailablePort();
    startEchoAgent(port);

    AgentChat<Map<String, Object>> chat =
        RemoteAgent.chat(
            RemoteAgentOptions.builder().url("http://localhost:" + port + "/echoAgent").build());

    chat.send("hi");
    String snapId = chat.snapshotId();
    assertNotNull(snapId, "expected snapshotId after send");

    // Retrieve snapshot via the transport directly.
    HttpAgentTransport<Map<String, Object>> transport =
        new HttpAgentTransport<>(
            RemoteAgentOptions.builder().url("http://localhost:" + port + "/echoAgent").build());

    SessionSnapshot<Map<String, Object>> snap =
        transport.getSnapshot(GetSnapshotRequest.builder().snapshotId(snapId).build());

    assertNotNull(snap, "expected non-null snapshot");
    assertEquals(snapId, snap.getSnapshotId(), "snapshot ID should match requested ID");
  }

  /**
   * Starts a Jetty server on {@code port} hosting a server-managed custom agent named {@code
   * blockingAgent} whose {@code AgentFn} blocks on {@code releaseLatch} until released. Used to
   * open a deterministic {@code PENDING} window for {@link #testAbortOverHttp()}.
   */
  private void startBlockingAgent(int port, CountDownLatch releaseLatch) throws Exception {
    DefaultRegistry registry = new DefaultRegistry();

    AgentActions.defineCustomAgent(
        registry,
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("blockingAgent")
            .store(new InMemorySessionStore<>())
            .build(),
        (sess, fnCtx) -> {
          releaseLatch.await(10, TimeUnit.SECONDS);
          return AgentResult.builder()
              .message(Message.model("done"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        });

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
   * Starts a Jetty server on {@code port} hosting a client-managed (no store) custom agent named
   * {@code counterAgent} that increments an integer counter in its custom state each turn and
   * echoes back the running total in its reply text.
   */
  private void startClientManagedCounterAgent(int port) throws Exception {
    DefaultRegistry registry = new DefaultRegistry();

    AgentActions.defineCustomAgent(
        registry,
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("counterAgent")
            // No store() call => client-managed: state round-trips via AgentInit/AgentOutput.
            .build(),
        (sess, fnCtx) -> {
          Map<String, Object> custom = sess.getCustom();
          int count = 0;
          if (custom != null && custom.get("count") instanceof Number) {
            count = ((Number) custom.get("count")).intValue();
          }
          count++;
          Map<String, Object> updated = new HashMap<>();
          updated.put("count", count);
          sess.updateCustom(c -> updated);
          return AgentResult.builder()
              .message(Message.model("count=" + count))
              .finishReason(AgentFinishReason.STOP)
              .build();
        });

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
   * Verifies {@link AgentChat#abort()} (backed by {@link HttpAgentTransport#abort}) reaches the
   * server's {@code /abort} companion endpoint and that a subsequent {@code getSnapshot} (via the
   * transport directly, mirroring how {@code AgentChat.loadChat} would read it back) reflects the
   * flipped status.
   *
   * <p>Scoped to what is actually implemented: aborting a snapshot that is genuinely {@code
   * PENDING} (opened by a detached turn whose {@code AgentFn} we block deterministically) — not
   * interrupting a live foreground call, which {@code Agent.abort()} / the abort companion action
   * do not support (no cancellation hook is threaded into a running {@code AgentFn}).
   */
  @Test
  void testAbortOverHttp() throws Exception {
    int port = findAvailablePort();
    CountDownLatch releaseLatch = new CountDownLatch(1);
    startBlockingAgent(port, releaseLatch);

    RemoteAgentOptions opts =
        RemoteAgentOptions.builder().url("http://localhost:" + port + "/blockingAgent").build();
    AgentChat<Map<String, Object>> chat = RemoteAgent.chat(opts);

    // Detach so the snapshot is written PENDING and the background AgentFn blocks on the latch.
    chat.sendStream(
        com.google.genkit.ai.agent.AgentInput.builder()
            .message(Message.user("go"))
            .detach(true)
            .build(),
        c -> {});

    String snapshotId = chat.snapshotId();
    assertNotNull(snapshotId, "expected snapshotId after detach");

    // Abort via the client — reaches the server's /abort companion endpoint.
    com.google.genkit.ai.agent.SnapshotStatus status = chat.abort();
    assertEquals(
        com.google.genkit.ai.agent.SnapshotStatus.ABORTED,
        status,
        "expected client-side abort() to report ABORTED");

    // Read the snapshot back directly via the transport (as AgentChat.loadChat would internally).
    HttpAgentTransport<Map<String, Object>> transport = new HttpAgentTransport<>(opts);
    SessionSnapshot<Map<String, Object>> snap =
        transport.getSnapshot(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertNotNull(snap, "expected non-null snapshot");
    assertEquals(
        com.google.genkit.ai.agent.SnapshotStatus.ABORTED,
        snap.getStatus(),
        "expected stored snapshot status to be ABORTED");

    // Release the blocked background turn; its finalizer must not revert the ABORTED status.
    releaseLatch.countDown();
    Thread.sleep(300);
    SessionSnapshot<Map<String, Object>> snapAfter =
        transport.getSnapshot(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    assertEquals(
        com.google.genkit.ai.agent.SnapshotStatus.ABORTED,
        snapAfter.getStatus(),
        "expected status to remain ABORTED after the background turn finalizes");
  }

  /**
   * Verifies a client-managed agent (no {@code SessionStore}) genuinely round-trips its session
   * state over real HTTP: {@link AgentChat} carries the full {@link
   * com.google.genkit.ai.agent.SessionState} (including custom state) in {@code AgentInit} on each
   * turn, and the served agent's reply on turn 2 reflects state seeded by turn 1 — proving
   * serialization/deserialization across the wire, not just in-process object sharing.
   */
  @Test
  void testClientManagedRemoteAgent() throws Exception {
    int port = findAvailablePort();
    startClientManagedCounterAgent(port);

    AgentChat<Map<String, Object>> chat =
        RemoteAgent.chat(
            RemoteAgentOptions.builder()
                .url("http://localhost:" + port + "/counterAgent")
                .serverManaged(false)
                .build());

    AgentResponse<Map<String, Object>> resp1 = chat.send("first");
    assertEquals("count=1", resp1.text(), "expected first turn to start the counter at 1");

    AgentResponse<Map<String, Object>> resp2 = chat.send("second");
    assertEquals(
        "count=2",
        resp2.text(),
        "expected second turn's reply to reflect state seeded from turn 1 over the wire");

    AgentResponse<Map<String, Object>> resp3 = chat.send("third");
    assertEquals("count=3", resp3.text(), "expected third turn to continue incrementing");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static int findAvailablePort() throws IOException {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void waitForServer(int port) throws Exception {
    int maxRetries = 50;
    for (int i = 0; i < maxRetries; i++) {
      try {
        URL healthUrl = new URI("http://localhost:" + port + "/health").toURL();
        HttpURLConnection conn = (HttpURLConnection) healthUrl.openConnection();
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
