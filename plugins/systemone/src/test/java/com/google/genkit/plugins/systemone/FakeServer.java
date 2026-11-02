/*
 * Copyright 2026 Google LLC
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

package com.google.genkit.plugins.systemone;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A fake System One endpoint that answers whatever questions it is sent, so a test can check the
 * whole path from a Java type to the wire and back. A choice is answered with its first option by
 * name, a score with 1.3, a noul with 0.93, from the model jev-1.13.0 with 312 input tokens and 48
 * output tokens.
 */
final class FakeServer implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** One request the server received. */
  record Received(
      String method, String rawPath, String query, Headers headers, Map<String, Object> body) {}

  /** The reply to a GET, the model listing. When null, a GET fails with a server error. */
  volatile String models;

  /** When set, the usage of every answer. */
  volatile Map<String, Object> usage;

  /** When set, wraps every answer in a server's envelope. */
  volatile Function<Map<String, Object>, Object> wrap;

  private final HttpServer server;
  private final List<Received> requests = new ArrayList<>();
  private final List<String> listings = new ArrayList<>();

  FakeServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  /** The server's root URL. */
  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange ex) throws IOException {
    try (ex) {
      String raw = ex.getRequestURI().getRawPath();
      String query = ex.getRequestURI().getRawQuery();
      if ("GET".equals(ex.getRequestMethod())) {
        synchronized (this) {
          listings.add(raw + (query == null ? "" : "?" + query));
        }
        String m = models;
        if (m == null) {
          ex.sendResponseHeaders(500, -1);
          return;
        }
        write(ex, 200, m);
        return;
      }
      byte[] bytes = ex.getRequestBody().readAllBytes();
      Map<String, Object> body;
      try {
        body = MAPPER.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {});
      } catch (IOException e) {
        body = new LinkedHashMap<>();
      }
      synchronized (this) {
        requests.add(new Received(ex.getRequestMethod(), raw, query, ex.getRequestHeaders(), body));
      }

      // A server that wraps the native body, as Workers AI does for a partner's model, carries the
      // questions under input.
      Map<String, Object> nativeBody = body;
      if (body.get("input") instanceof Map<?, ?> input) {
        nativeBody = Wire.map(input);
      }
      Map<String, Object> questions = Wire.map(nativeBody.get("questions"));
      Map<String, Object> answers = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : questions.entrySet()) {
        Map<String, Object> q = Wire.map(e.getValue());
        switch (String.valueOf(q.get("type"))) {
          case "choice" -> {
            List<String> keys =
                new ArrayList<>(new TreeSet<>(Wire.map(q.get("criteria")).keySet()));
            if (keys.isEmpty()) {
              write(ex, 400, "{\"error\":{\"message\":\"a choice needs criteria\"}}");
              return;
            }
            Map<String, Object> probabilities = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
              probabilities.put(keys.get(i), i == 0 ? 1 - 0.1 * (keys.size() - 1) : 0.1);
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", "choice");
            a.put("choice", keys.get(0));
            a.put("probabilities", probabilities);
            a.put("confidence", 0.6);
            answers.put(e.getKey(), a);
          }
          case "score" -> {
            List<?> levels = q.get("criteria") instanceof List<?> l ? l : List.of();
            Map<String, Object> legend = new LinkedHashMap<>();
            for (int i = 0; i < levels.size(); i++) {
              legend.put(Integer.toString(i), levels.get(i));
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", "score");
            a.put("score", 1.3);
            a.put("legend", legend);
            a.put("probabilities", Map.of("0", 0, "1", 0.7, "2", 0.3));
            a.put("confidence", 0.54);
            answers.put(e.getKey(), a);
          }
          case "noul" -> answers.put(e.getKey(), Map.of("type", "noul", "noul", 0.93));
          default -> {}
        }
      }
      Map<String, Object> reply = new LinkedHashMap<>();
      reply.put("model", "jev-1.13.0");
      reply.put("answers", answers);
      reply.put("usage", Map.of("input_tokens", 312, "output_tokens", 48));
      if (usage != null) {
        reply.put("usage", usage);
      }
      Object out = wrap != null ? wrap.apply(reply) : reply;
      ex.getResponseHeaders().set("Content-Type", "application/json");
      write(ex, 200, MAPPER.writeValueAsString(out));
    }
  }

  private static void write(HttpExchange ex, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** The last request posted. It fails the test when nothing was posted. */
  synchronized Received last() {
    if (requests.isEmpty()) {
      fail("the endpoint was never called");
    }
    return requests.get(requests.size() - 1);
  }

  /** The paths, with their queries, of the listings asked for. */
  synchronized List<String> listings() {
    return new ArrayList<>(listings);
  }

  /** How many requests were posted. */
  synchronized int calls() {
    return requests.size();
  }
}
