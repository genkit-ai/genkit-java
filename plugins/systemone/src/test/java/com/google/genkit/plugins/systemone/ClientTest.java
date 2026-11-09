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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.GenkitException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** One question of each kind, as they go on the wire. */
  static Map<String, WireQuestion> testQuestions() {
    Map<String, WireQuestion> q = new TreeMap<>();
    q.put(
        "department",
        new WireQuestion(
            Wire.KIND_CHOICE,
            "Which team should handle this?",
            WireQuestion.Options.of(
                new WireQuestion.Option("billing", "Payments, invoicing, refunds"),
                new WireQuestion.Option("technical", "Bugs, outages, integrations"))));
    q.put(
        "is_urgent",
        new WireQuestion(
            Wire.KIND_NOUL, "Does the ticket explicitly communicate time pressure?", null));
    List<String> levels = List.of("Calm", "Concerned but civil", "Very angry");
    q.put(
        "frustration",
        new WireQuestion(
            Wire.KIND_SCORE, "How frustrated is the customer?", List.copyOf(levels), levels));
    return q;
  }

  static final String TEST_ANSWERS =
      "{\"department\": {\"type\": \"choice\", \"choice\": \"billing\", \"probabilities\":"
          + " {\"billing\": 0.84, \"technical\": 0.16}, \"confidence\": 0.6},"
          + "\"is_urgent\": {\"type\": \"noul\", \"noul\": 0.93},"
          + "\"frustration\": {\"type\": \"score\", \"score\": 1.3, \"legend\": {\"0\": \"Calm\","
          + " \"1\": \"Concerned but civil\", \"2\": \"Very angry\"}, \"probabilities\": {\"0\": 0,"
          + " \"1\": 0.7, \"2\": 0.3}, \"confidence\": 0.54}}";

  static final String CANNED_REPLY =
      "{\"model\":\"jev-1.13.0\",\"answers\":"
          + TEST_ANSWERS
          + ",\"usage\":{\"input_tokens\":312,\"output_tokens\":48}}";

  /** A request the recorder received. */
  record Recorded(
      String method, String path, Map<String, List<String>> headers, Map<String, Object> body) {}

  /**
   * A fake endpoint that records what it was sent and answers with a canned body, or with a
   * scripted sequence of statuses.
   */
  static final class Recorder implements AutoCloseable {
    final HttpServer server;
    final List<Recorded> requests = new ArrayList<>();
    volatile BiConsumer<HttpExchange, Integer> respond;
    volatile String reply;

    Recorder(String reply) {
      this.reply = reply;
      try {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      server.createContext("/", this::handle);
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
      try (ex) {
        byte[] raw = ex.getRequestBody().readAllBytes();
        Map<String, Object> body = null;
        if (raw.length > 0) {
          try {
            body = MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
          } catch (IOException e) {
            body = null;
          }
        }
        int call;
        synchronized (this) {
          Map<String, List<String>> headers = new LinkedHashMap<>();
          ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v));
          requests.add(
              new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), headers, body));
          call = requests.size() - 1;
        }
        if (respond != null) {
          respond.accept(ex, call);
          return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/json");
        write(ex, 200, reply);
      }
    }

    synchronized Recorded last() {
      assertFalse(requests.isEmpty(), "the endpoint was never called");
      return requests.get(requests.size() - 1);
    }

    synchronized int calls() {
      return requests.size();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  static void write(HttpExchange ex, int code, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        try (OutputStream out = ex.getResponseBody()) {
          out.write(bytes);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private final List<Recorder> recorders = new ArrayList<>();

  @AfterEach
  void stopRecorders() {
    recorders.forEach(Recorder::close);
  }

  private Recorder recorder(String reply) {
    Recorder r = new Recorder(reply);
    recorders.add(r);
    return r;
  }

  static Client.Endpoint testEndpoint() {
    return new Client.Endpoint("test", "/v1/systemone", "/v1/models", null, null);
  }

  private static Client newClient(Recorder rec, Client.Endpoint ep) {
    return new Client(new OkHttpClient(), rec.url(), "test-key", Map.of(), ep);
  }

  private static Client.Request request(Map<String, Object> extra) {
    return new Client.Request("hi", new StateBuilder.Media(), testQuestions(), extra);
  }

  @Test
  void decide() {
    Recorder rec = recorder(CANNED_REPLY);
    Client c =
        new Client(
            new OkHttpClient(),
            rec.url(),
            "test-key",
            Map.of("X-Trace", List.of("abc")),
            testEndpoint());
    Client.Answered resp = c.decide("jev-1.13.0", request(null));
    Recorded req = rec.last();
    assertEquals("POST", req.method());
    assertEquals("/v1/systemone", req.path());
    assertEquals(List.of("Bearer test-key"), req.headers().get("authorization"));
    assertEquals(List.of("abc"), req.headers().get("x-trace"));
    assertEquals("jev-1.13.0", req.body().get("model"));
    assertEquals("hi", req.body().get("state"));
    for (String field : List.of("images", "audio", "videos")) {
      assertFalse(req.body().containsKey(field), field + " was sent with none to send");
    }
    Map<String, Object> department =
        Wire.map(Wire.map(req.body().get("questions")).get("department"));
    assertEquals("choice", department.get("type"));
    assertEquals("Which team should handle this?", department.get("instructions"));
    assertEquals("jev-1.13.0", resp.info().model());
    assertEquals(312, resp.inputTokens());
    assertEquals("billing", resp.info().answers().get("department").get("choice"));
  }

  @Test
  void route() {
    Recorder rec = recorder(CANNED_REPLY);
    Client.Endpoint ep =
        new Client.Endpoint(
            "test",
            "/v1/systemone",
            "/v1/models",
            (model, body) -> {
              body.remove("model");
              return new SystemOne.Routed(model, Map.of("input", body));
            },
            null);
    newClient(rec, ep).decide("clef", request(null));
    Recorded req = rec.last();
    assertEquals("/v1/systemone/clef", req.path(), "the route's suffix joined to the path");
    assertEquals("hi", Wire.map(req.body().get("input")).get("state"));
    assertNull(req.body().get("state"));
  }

  @Test
  void unwrapErrorNamesTheEndpoint() {
    Client.Endpoint ep =
        new Client.Endpoint(
            "test",
            "/v1/systemone",
            "/v1/models",
            null,
            body -> {
              throw new IllegalStateException("Authentication error");
            });
    GenkitException e =
        assertThrows(
            GenkitException.class,
            () -> newClient(recorder(CANNED_REPLY), ep).decide("clef", request(null)));
    assertEquals("UNKNOWN", e.getErrorCode());
    assertTrue(e.getMessage().contains("test: Authentication error"), e.getMessage());
  }

  @Test
  void unwrapErrorKeepsItsStatus() {
    Client.Endpoint ep =
        new Client.Endpoint(
            "test",
            "/v1/systemone",
            "/v1/models",
            null,
            body -> {
              throw Wire.error("PERMISSION_DENIED", "denied");
            });
    GenkitException e =
        assertThrows(
            GenkitException.class,
            () -> newClient(recorder(CANNED_REPLY), ep).decide("clef", request(null)));
    assertEquals("PERMISSION_DENIED", e.getErrorCode());
  }

  @Test
  void noKeyNoAuthorization() {
    Recorder rec = recorder(CANNED_REPLY);
    new Client(new OkHttpClient(), rec.url(), "", Map.of(), testEndpoint())
        .decide("clef", request(null));
    assertNull(rec.last().headers().get("authorization"));
  }

  @Test
  void extraMergesTopLevelFields() {
    Recorder rec = recorder(CANNED_REPLY);
    newClient(rec, testEndpoint())
        .decide("jev-latest", request(Map.of("session_id", "s-1", "model", "typesafe/jev-9")));
    Map<String, Object> body = rec.last().body();
    assertEquals("s-1", body.get("session_id"));
    assertEquals(
        "jev-latest", body.get("model"), "an extra cannot replace a field the request builds");
  }

  @Test
  void retriesOverloadAndHonorsRetryAfter() {
    Recorder rec = recorder(CANNED_REPLY);
    rec.respond =
        (ex, call) -> {
          if (call == 0) {
            ex.getResponseHeaders().set("Retry-After", "0");
            write(ex, 529, "{\"error\":{\"message\":\"overloaded\"}}");
            return;
          }
          write(ex, 200, CANNED_REPLY);
        };
    long start = System.nanoTime();
    newClient(rec, testEndpoint()).decide("jev-latest", request(null));
    assertEquals(2, rec.calls());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
    assertTrue(
        elapsed.compareTo(Duration.ofMillis(300)) < 0,
        "Retry-After: 0 was not honored; the retry waited " + elapsed);
  }

  @Test
  void cancelDuringBackoffReportsTheCancellation() throws Exception {
    // The server asks for a long wait; the caller gives up during it. The error is the
    // cancellation, not the 503 it interrupted.
    Recorder rec = recorder(CANNED_REPLY);
    rec.respond =
        (ex, call) -> {
          ex.getResponseHeaders().set("Retry-After", "5");
          write(ex, 503, "");
        };
    Client c = newClient(rec, testEndpoint());
    GenkitException[] error = new GenkitException[1];
    Thread caller =
        new Thread(() -> {
          try {
            c.decide("jev-latest", request(null));
          } catch (GenkitException e) {
            error[0] = e;
          }
        });
    long start = System.nanoTime();
    caller.start();
    Thread.sleep(200);
    caller.interrupt();
    caller.join(5000);
    assertFalse(caller.isAlive(), "the wait ran on after the cancellation");
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
    assertEquals("CANCELLED", error[0].getErrorCode(), String.valueOf(error[0]));
    assertNotEquals("UNAVAILABLE", error[0].getErrorCode());
    assertEquals(1, rec.calls());
  }

  @Test
  void errorsMapToStatus() {
    Object[][] cases = {
      {
        401,
        "{\"error\":{\"message\":\"Missing or invalid API key\"}}",
        "UNAUTHENTICATED",
        "Missing or invalid API key"
      },
      {
        422,
        "{\"detail\":[{\"loc\":[\"body\",\"questions\"],\"msg\":\"field required\"}]}",
        "INVALID_ARGUMENT",
        "body.questions: field required"
      },
      {
        400,
        "[{\"code\":\"invalid_union\",\"path\":[\"questions\",\"u\",\"criteria\",\"false\"],\"message\":\"Invalid input\"},{\"path\":[],\"message\":\"Unrecognized key\"}]",
        "INVALID_ARGUMENT",
        "questions.u.criteria.false: Invalid input; Unrecognized key"
      },
      {
        400,
        "{\"error\":{\"message\":\"HTTP 400: {\\\"detail\\\":\\\"Too many score levels\\\"}\"}}",
        "INVALID_ARGUMENT",
        "Too many score levels"
      },
      {429, "{\"error\":\"rate limited\"}", "RESOURCE_EXHAUSTED", "rate limited"},
      {
        403,
        "{\"result\":null,\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}],\"messages\":[]}",
        "PERMISSION_DENIED",
        "HTTP 403: Authentication error"
      },
      {503, "service unavailable", "UNAVAILABLE", "service unavailable"},
      {500, "", "INTERNAL", "HTTP 500: Internal Server Error"},
      {501, "not implemented", "UNIMPLEMENTED", "not implemented"},
      {504, "gateway timeout", "DEADLINE_EXCEEDED", "gateway timeout"},
      {404, "", "NOT_FOUND", "HTTP 404: Not Found"},
    };
    for (Object[] c : cases) {
      int code = (int) c[0];
      Recorder rec = recorder(CANNED_REPLY);
      rec.respond =
          (ex, call) -> {
            // An immediate Retry-After keeps the retried codes from sleeping through the backoff.
            ex.getResponseHeaders().set("Retry-After", "0");
            write(ex, code, (String) c[1]);
          };
      GenkitException e =
          assertThrows(
              GenkitException.class,
              () -> newClient(rec, testEndpoint()).decide("jev-latest", request(null)));
      assertEquals(c[2], e.getErrorCode(), code + ": " + e.getMessage());
      assertTrue(e.getMessage().contains((String) c[3]), code + ": " + e.getMessage());
      if (code == 401 || code == 501) {
        assertEquals(1, rec.calls(), "a " + code + " was retried");
      }
    }
  }

  @Test
  void retryDelays() {
    assertEquals(Duration.ofSeconds(2), Client.parseRetryAfter("2"));
    String date =
        ZonedDateTime.now(ZoneOffset.UTC)
            .plusSeconds(3)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);
    Duration d = Client.parseRetryAfter(date);
    assertTrue(
        d != null && !d.isNegative() && d.compareTo(Duration.ofSeconds(3)) <= 0,
        "Retry-After date = " + d);
    assertNull(Client.parseRetryAfter("soon"));
    assertNull(Client.parseRetryAfter(""));
    assertEquals(Duration.ZERO, Client.parseRetryAfter("0"));
    long[] want = {500, 1000, 2000, 4000, 5000};
    for (int attempt = 0; attempt < want.length; attempt++) {
      long got = Client.backoff(attempt).toMillis();
      assertTrue(
          got <= want[attempt] && got >= want[attempt] * 3 / 4,
          "backoff(" + attempt + ") = " + got);
    }
  }

  @Test
  void listModels() {
    for (String reply :
        List.of(
            "[{\"name\":\"jev-1.13.0\",\"description\":\"Current release\",\"release_date\":\"2026-08-01\"}]",
            "{\"models\":[{\"name\":\"jev-1.13.0\"}]}",
            "{\"data\":[{\"name\":\"jev-1.13.0\"}]}",
            // OpenRouter names a model by its id; its name is for display.
            "{\"data\":[{\"id\":\"jev-1.13.0\",\"name\":\"TypeSafe: Jev 1.13\"}]}")) {
      Recorder rec = recorder(reply);
      Client c = newClient(rec, testEndpoint());
      List<Client.ListedModel> models = c.listModels(c.http);
      Recorded req = rec.last();
      assertEquals("GET", req.method());
      assertEquals("/v1/models", req.path());
      assertEquals(1, models.size(), reply);
      assertEquals("jev-1.13.0", models.get(0).model(), reply);
    }
    Client noPath = newClient(recorder("{}"), new Client.Endpoint("gateway", "", "", null, null));
    GenkitException e = assertThrows(GenkitException.class, () -> noPath.listModels(noPath.http));
    assertEquals("UNIMPLEMENTED", e.getErrorCode());
  }
}
