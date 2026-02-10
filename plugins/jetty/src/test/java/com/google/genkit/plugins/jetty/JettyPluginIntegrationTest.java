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

import com.google.genkit.core.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for JettyPlugin verifying that typed flow inputs are properly deserialized.
 *
 * <p>This tests the fix for the bug where the JettyPlugin would deserialize JSON input as
 * Object.class (resulting in LinkedHashMap) instead of the typed input class, causing
 * ClassCastException when the flow lambda tried to use the typed input.
 */
class JettyPluginIntegrationTest {

  private JettyPlugin plugin;

  /** Simple POJO used as flow input. */
  public static class TranslateRequest {
    private String text;
    private String targetLanguage;

    public TranslateRequest() {}

    public TranslateRequest(String text, String targetLanguage) {
      this.text = text;
      this.targetLanguage = targetLanguage;
    }

    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }

    public String getTargetLanguage() {
      return targetLanguage;
    }

    public void setTargetLanguage(String targetLanguage) {
      this.targetLanguage = targetLanguage;
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    if (plugin != null) {
      plugin.stop();
    }
  }

  /**
   * Tests that a flow with a typed POJO input is properly deserialized from the HTTP request body.
   * Before the fix, this would fail with a ClassCastException because the JSON was deserialized to
   * LinkedHashMap instead of TranslateRequest.
   */
  @Test
  void testFlowWithTypedInputIsProperlyDeserialized() throws Exception {
    // Use a random available port
    int port = findAvailablePort();
    JettyPluginOptions options = JettyPluginOptions.builder().port(port).build();
    plugin = new JettyPlugin(options);

    // Create a registry and register a flow with a typed input
    DefaultRegistry registry = new DefaultRegistry();
    plugin.init(registry);

    // Define a flow that expects TranslateRequest as input
    Flow.define(
        registry,
        "translateFlow",
        TranslateRequest.class,
        String.class,
        (ctx, request) -> {
          // This line would throw ClassCastException if input is a LinkedHashMap
          return "Translated '" + request.getText() + "' to " + request.getTargetLanguage();
        });

    // Start in background thread
    Thread serverThread =
        new Thread(
            () -> {
              try {
                plugin.start();
              } catch (Exception e) {
                // Server was stopped
              }
            });
    serverThread.setDaemon(true);
    serverThread.start();

    // Wait for server to be ready
    waitForServer(port);

    // Send POST request with JSON body
    String jsonBody = "{\"text\":\"Hello\",\"targetLanguage\":\"Spanish\"}";
    String response = sendPost("http://localhost:" + port + "/api/flows/translateFlow", jsonBody);

    // Verify the response contains the expected output
    assertTrue(
        response.contains("Translated 'Hello' to Spanish"),
        "Expected response to contain translated text but got: " + response);
  }

  /**
   * Tests that a flow with a String input works correctly (regression test to ensure simple types
   * still work).
   */
  @Test
  void testFlowWithStringInput() throws Exception {
    int port = findAvailablePort();
    JettyPluginOptions options = JettyPluginOptions.builder().port(port).build();
    plugin = new JettyPlugin(options);

    DefaultRegistry registry = new DefaultRegistry();
    plugin.init(registry);

    Flow.define(registry, "echoFlow", String.class, String.class, (ctx, input) -> "Echo: " + input);

    Thread serverThread =
        new Thread(
            () -> {
              try {
                plugin.start();
              } catch (Exception e) {
                // Server was stopped
              }
            });
    serverThread.setDaemon(true);
    serverThread.start();

    waitForServer(port);

    String response =
        sendPost("http://localhost:" + port + "/api/flows/echoFlow", "\"test input\"");

    assertTrue(
        response.contains("Echo: test input"), "Expected echo response but got: " + response);
  }

  /** Tests that a flow with a Map input works correctly. */
  @Test
  void testFlowWithMapInput() throws Exception {
    int port = findAvailablePort();
    JettyPluginOptions options = JettyPluginOptions.builder().port(port).build();
    plugin = new JettyPlugin(options);

    DefaultRegistry registry = new DefaultRegistry();
    plugin.init(registry);

    Flow.define(
        registry,
        "mapFlow",
        Map.class,
        String.class,
        (ctx, input) -> "Got key: " + input.get("key"));

    Thread serverThread =
        new Thread(
            () -> {
              try {
                plugin.start();
              } catch (Exception e) {
                // Server was stopped
              }
            });
    serverThread.setDaemon(true);
    serverThread.start();

    waitForServer(port);

    String response =
        sendPost("http://localhost:" + port + "/api/flows/mapFlow", "{\"key\":\"value\"}");

    assertTrue(response.contains("Got key: value"), "Expected map response but got: " + response);
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
        // Server not ready yet
      }
      Thread.sleep(100);
    }
    fail("Server did not start within timeout");
  }

  private static String sendPost(String url, String body) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setDoOutput(true);
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);

    try (OutputStream os = conn.getOutputStream()) {
      os.write(body.getBytes(StandardCharsets.UTF_8));
    }

    int status = conn.getResponseCode();
    java.io.InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
    String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);

    assertEquals(200, status, "Expected HTTP 200 but got " + status + ": " + response);
    return response;
  }
}
