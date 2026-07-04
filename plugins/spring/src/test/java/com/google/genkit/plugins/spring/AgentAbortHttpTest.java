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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test verifying the abort companion endpoint over the HTTP wire using SpringPlugin.
 *
 * <p>Mirrors {@code plugins/jetty}'s {@code AgentAbortHttpTest}. Cross-referenced with {@code
 * Agent.abort(String)} / {@code AgentActions.buildAbortAction}: the abort companion only flips a
 * stored snapshot's status from {@code PENDING} to {@code ABORTED}; it never interrupts a running
 * foreground {@code AgentFn} call (there is no cancellation hook threaded into a synchronous {@code
 * AgentFn.run}). This test therefore targets what is actually implemented: aborting a snapshot that
 * is genuinely in the {@code PENDING} window opened by a detached turn, which synchronously
 * persists the {@code PENDING} row before the HTTP response returns and whose finalizer never
 * overwrites an {@code ABORTED} status (race-safe by design) — then confirms via {@code
 * getSnapshot} that the status is {@code ABORTED} and stays that way even after the background turn
 * body completes.
 */
class AgentAbortHttpTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SpringPlugin plugin;

  @AfterEach
  void tearDown() throws Exception {
    if (plugin != null) {
      plugin.stop();
    }
  }

  /**
   * Starts a SpringPlugin on the given port with a server-managed custom agent named {@code
   * abortAgent} whose AgentFn blocks on {@code latch} until released, giving the test a
   * deterministic window in which the snapshot is guaranteed to still be {@code PENDING}.
   */
  private void startWithBlockingAgent(int port, CountDownLatch releaseLatch) throws Exception {
    DefaultRegistry registry = new DefaultRegistry();

    AgentActions.defineCustomAgent(
        registry,
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("abortAgent")
            .store(new InMemorySessionStore<>())
            .build(),
        (sess, fnCtx) -> {
          // Block the detached background turn body until the test releases it, so the
          // PENDING window is deterministic and long enough to reliably call abort().
          releaseLatch.await(10, TimeUnit.SECONDS);
          return AgentResult.builder()
              .message(Message.model("done"))
              .finishReason(AgentFinishReason.STOP)
              .build();
        });

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

  /**
   * Integration test: detach a turn (leaving its snapshot PENDING while the AgentFn blocks) -> POST
   * /abort for that snapshot -> assert the abort response reports status ABORTED -> release the
   * blocked turn so it finalizes in the background -> poll getSnapshot and assert the status
   * remains ABORTED (proving the finalizer's abort-aware guard truly won the race, not just that
   * the abort call itself returned ABORTED).
   */
  @Test
  void testAbortOverHttp() throws Exception {
    int port = findAvailablePort();
    CountDownLatch releaseLatch = new CountDownLatch(1);
    startWithBlockingAgent(port, releaseLatch);

    HttpClient client = HttpClient.newHttpClient();

    // Step 1: POST a detach turn. The AgentFn blocks on releaseLatch, so the snapshot remains
    // PENDING until we count down the latch below.
    String detachBody =
        "{\"data\":{\"detach\":true,\"message\":{\"role\":\"user\","
            + "\"content\":[{\"text\":\"go\"}]}},\"init\":{}}";

    HttpRequest detachRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/abortAgent"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(detachBody))
            .build();

    HttpResponse<String> detachResponse =
        client.send(detachRequest, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, detachResponse.statusCode(), "detach POST body: " + detachResponse.body());

    JsonNode detachRoot = MAPPER.readTree(detachResponse.body());
    JsonNode result = detachRoot.path("result");
    assertEquals(
        "detached",
        result.path("finishReason").asText(""),
        "expected finishReason=detached: " + detachResponse.body());
    String snapshotId = result.path("snapshotId").asText("");
    assertFalse(snapshotId.isEmpty(), "expected non-empty snapshotId");

    // Step 2: POST /abort for the still-PENDING snapshot.
    String abortBody = "{\"data\":{\"snapshotId\":\"" + snapshotId + "\"}}";
    HttpRequest abortRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/abortAgent/abort"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(abortBody))
            .build();

    HttpResponse<String> abortResponse =
        client.send(abortRequest, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, abortResponse.statusCode(), "abort POST body: " + abortResponse.body());

    JsonNode abortRoot = MAPPER.readTree(abortResponse.body());
    assertTrue(abortRoot.has("result"), "expected result envelope: " + abortResponse.body());
    JsonNode abortResult = abortRoot.path("result");
    assertEquals(snapshotId, abortResult.path("snapshotId").asText(""));
    assertEquals(
        "aborted",
        abortResult.path("status").asText(""),
        "expected abort response status=aborted: " + abortResponse.body());

    // Step 3: getSnapshot immediately (still PENDING-turned-ABORTED, before the background turn
    // body has been released) should already reflect ABORTED.
    String getSnapshotBody = "{\"data\":{\"snapshotId\":\"" + snapshotId + "\"}}";
    HttpRequest snapshotRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/abortAgent/getSnapshot"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(getSnapshotBody))
            .build();

    HttpResponse<String> snapResponse1 =
        client.send(snapshotRequest, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, snapResponse1.statusCode(), "getSnapshot body: " + snapResponse1.body());
    JsonNode snapResult1 = MAPPER.readTree(snapResponse1.body()).path("result");
    assertEquals(
        "aborted",
        snapResult1.path("status").asText(""),
        "expected snapshot status=aborted before finalize: " + snapResult1);

    // Step 4: release the blocked background turn so it runs to completion and attempts to
    // finalize the (now ABORTED) snapshot to COMPLETED.
    releaseLatch.countDown();

    // Step 5: poll getSnapshot for up to 3s and assert the status NEVER reverts from "aborted"
    // (proving DetachController's finalizer really never overwrites an ABORTED row).
    long deadline = System.currentTimeMillis() + 3000;
    String lastStatus = "aborted";
    while (System.currentTimeMillis() < deadline) {
      HttpResponse<String> snapResponse =
          client.send(snapshotRequest, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, snapResponse.statusCode(), "getSnapshot body: " + snapResponse.body());
      JsonNode snapResult = MAPPER.readTree(snapResponse.body()).path("result");
      lastStatus = snapResult.path("status").asText("");
      assertEquals(
          "aborted",
          lastStatus,
          "snapshot status must remain aborted (finalizer must not overwrite it): " + snapResult);
      Thread.sleep(50);
    }
    assertEquals("aborted", lastStatus);
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
