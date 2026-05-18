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

package com.google.genkit;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.core.JsonUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ReflectionServerTest {

  @Test
  void runActionErrorsIncludeTraceId() throws Exception {
    Genkit genkit = new Genkit();
    genkit.defineFlow(
        "failingFlow",
        String.class,
        String.class,
        input -> {
          throw new IllegalStateException("boom");
        });

    int port = findFreePort();
    ReflectionServer server = new ReflectionServer(genkit.getRegistry(), port, null);
    server.start();

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/api/runAction"))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"key\":\"/flow/failingFlow\",\"input\":\"hello\"}"))
              .build();

      HttpResponse<String> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(500, response.statusCode());

      JsonNode error = JsonUtils.parseJson(response.body());
      assertEquals(2, error.get("code").asInt());
      assertEquals("Action execution failed: boom", error.get("message").asText());
      assertTrue(error.has("details"));
      assertTrue(error.get("details").hasNonNull("traceId"));

      String traceId = error.get("details").get("traceId").asText();
      assertFalse(traceId.isBlank());
      assertNotNull(ReflectionServer.getTrace(traceId));
    } finally {
      server.stop();
    }
  }

  private int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
