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

import com.google.genkit.ai.Message;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.agent.internal.AgentActions;
import com.google.genkit.client.RemoteAgent;
import com.google.genkit.client.RemoteAgentOptions;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.DefaultRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof that custom HTTP request headers set via {@link RemoteAgentOptions#headers()} on
 * the client actually reach a served {@code AgentFn} through {@code GenkitAgentController}.
 *
 * <p>Mirrors {@code plugins/jetty}'s {@code AgentHeaderPropagationTest}, proving that the same
 * reserved {@code "headers"} context key populated by {@code JettyPlugin} is populated identically
 * by {@code GenkitAgentController} — a tool/flow reading {@code ctx.getContext().get("headers")}
 * behaves the same whether served by Jetty or Spring.
 *
 * <p>This is the full client-to-server round trip that was previously broken: {@code
 * HttpAgentTransport} already sent {@code RemoteAgentOptions.headers()} as real HTTP headers, but
 * {@code GenkitAgentController} never read incoming HTTP headers on the server side, so a tool/flow
 * had no way to observe them. These tests start a real Spring server, send a real HTTP request (via
 * {@link RemoteAgent}/{@link RemoteAgentOptions#headers()}) carrying a custom header, and assert a
 * no-model custom {@code AgentFn} can read that header's value out of {@code
 * AgentFnContext#context()} and reflect it back in its response.
 */
class AgentHeaderPropagationTest {

  private SpringPlugin plugin;

  @AfterEach
  void tearDown() throws Exception {
    if (plugin != null) {
      plugin.stop();
    }
  }

  /**
   * Starts a Spring server on {@code port} hosting a server-managed, no-model custom agent named
   * {@code headerEchoAgent}. The agent's {@code AgentFn} reads {@code
   * fnCtx.context().getContext().get("headers")} (the reserved key that {@code
   * GenkitAgentController} merges incoming HTTP headers into) and echoes the requested header's
   * value back in its reply text, so the test can assert on it without any model call.
   */
  private void startHeaderEchoAgent(int port) throws Exception {
    DefaultRegistry registry = new DefaultRegistry();

    AgentActions.defineCustomAgent(
        registry,
        CustomAgentConfig.<Map<String, Object>>builder()
            .name("headerEchoAgent")
            .store(new InMemorySessionStore<>())
            .build(),
        (sess, fnCtx) -> {
          String headerValue = "(missing)";
          ActionContext actionContext = fnCtx.context();
          if (actionContext != null && actionContext.getContext() != null) {
            Object headersObj = actionContext.getContext().get("headers");
            if (headersObj instanceof Map<?, ?> headers) {
              Object v = headers.get("X-Custom-Auth");
              if (v != null) {
                headerValue = String.valueOf(v);
              }
            }
          }
          return AgentResult.builder()
              .message(Message.model("header=" + headerValue))
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
   * Full round trip: a {@link RemoteAgent} configured with {@link
   * RemoteAgentOptions.Builder#headers(Map)} sends a real HTTP POST to a live Spring server; the
   * server-side {@code AgentFn} reads the header back out of {@code ActionContext.getContext()} and
   * reflects it in its reply. Proves the client-to-server header pipe end-to-end, not just that a
   * map got populated somewhere.
   */
  @Test
  void testCustomHeaderReachesAgentFnOverHttp() throws Exception {
    int port = findAvailablePort();
    startHeaderEchoAgent(port);

    Map<String, String> headers = new HashMap<>();
    headers.put("X-Custom-Auth", "secret-token-123");

    AgentChat<Map<String, Object>> chat =
        RemoteAgent.chat(
            RemoteAgentOptions.builder()
                .url("http://localhost:" + port + "/headerEchoAgent")
                .headers(headers)
                .build());

    AgentResponse<Map<String, Object>> resp = chat.send("hello");

    assertNotNull(resp.text(), "expected non-null text");
    assertEquals(
        "header=secret-token-123",
        resp.text(),
        "expected the AgentFn to read the custom HTTP header via ActionContext.getContext()");
  }

  /**
   * Without any custom header configured, the AgentFn should observe the reserved {@code "headers"}
   * key as either absent or not containing {@code X-Custom-Auth} — i.e. the header plumbing does
   * not fabricate values, and the (missing) fallback path is exercised.
   */
  @Test
  void testNoCustomHeaderMeansMissingInAgentFn() throws Exception {
    int port = findAvailablePort();
    startHeaderEchoAgent(port);

    AgentChat<Map<String, Object>> chat =
        RemoteAgent.chat(
            RemoteAgentOptions.builder()
                .url("http://localhost:" + port + "/headerEchoAgent")
                .build());

    AgentResponse<Map<String, Object>> resp = chat.send("hello");

    assertEquals(
        "header=(missing)",
        resp.text(),
        "expected no X-Custom-Auth header to be observed when the client sets none");
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
    int maxRetries = 100;
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
