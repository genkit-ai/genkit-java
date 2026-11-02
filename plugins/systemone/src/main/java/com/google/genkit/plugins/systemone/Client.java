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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.core.GenkitException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Posts questions to one System One endpoint and reads its model listing. */
final class Client {

  /** Retry policy: two retries after the first attempt, half a second doubling to five. */
  static final int MAX_RETRIES = 2;

  static final Duration RETRY_BASE = Duration.ofMillis(500);
  static final Duration RETRY_CAP = Duration.ofSeconds(5);
  static final Duration RETRY_AFTER_CAP = Duration.ofSeconds(30);
  static final int MAX_BODY_BYTES = 8 << 20;

  /** Bounds one attempt when the plugin builds the HTTP client. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final MediaType JSON = MediaType.get("application/json");

  /** Fields a request builds itself, which an extra field cannot replace. */
  static final List<String> RESERVED_FIELDS =
      List.of("model", "state", "questions", "images", "audio", "videos");

  /** How a server takes System One requests: the paths, and the hooks for a non-native wire. */
  record Endpoint(
      String name,
      String path,
      String modelsPath,
      SystemOne.Route route,
      SystemOne.Unwrap unwrap) {}

  /** The native request body, before the endpoint's envelope. */
  record Request(
      Object state,
      StateBuilder.Media media,
      Map<String, WireQuestion> questions,
      Map<String, Object> extra) {

    /** Builds the native JSON body. */
    Map<String, Object> body(String model) {
      Map<String, Object> body = new LinkedHashMap<>();
      if (extra != null) {
        body.putAll(extra);
      }
      RESERVED_FIELDS.forEach(body::remove);
      body.put("model", model);
      body.put("state", state);
      body.put("questions", questions);
      if (media != null) {
        if (!media.images().isEmpty()) {
          body.put("images", new ArrayList<>(media.images()));
        }
        if (!media.audio().isEmpty()) {
          body.put("audio", new ArrayList<>(media.audio()));
        }
        if (!media.videos().isEmpty()) {
          body.put("videos", new ArrayList<>(media.videos()));
        }
      }
      return body;
    }
  }

  /** The native response: the info, and the usage a gateway may add a cost to. */
  record Answered(Info info, int inputTokens, int outputTokens, Double cost) {}

  /** One entry of a model listing: a model named by its ID, or by its name when it has no ID. */
  record ListedModel(String id, String name, String description, String releaseDate) {
    String model() {
      return id != null && !id.isEmpty() ? id : name == null ? "" : name;
    }
  }

  final OkHttpClient http;
  final String baseUrl;
  final String apiKey;
  final Map<String, List<String>> headers;
  final Endpoint endpoint;

  Client(
      OkHttpClient http,
      String baseUrl,
      String apiKey,
      Map<String, List<String>> headers,
      Endpoint endpoint) {
    this.http = http;
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.headers = headers == null ? Map.of() : headers;
    this.endpoint = endpoint;
  }

  /** Posts one request and decodes the answers. */
  Answered decide(String model, Request req) {
    String suffix = "";
    Map<String, Object> nativeBody = req.body(model);
    Object body = nativeBody;
    if (endpoint.route() != null) {
      SystemOne.Routed routed;
      try {
        routed = endpoint.route().route(model, nativeBody);
      } catch (GenkitException e) {
        throw e;
      } catch (Exception e) {
        throw new GenkitException(e.getMessage(), e);
      }
      suffix = routed.path() == null ? "" : routed.path();
      body = routed.body();
      if (!suffix.isEmpty() && !suffix.startsWith("/")) {
        suffix = "/" + suffix;
      }
    }
    String raw = new String(send("POST", baseUrl + endpoint.path() + suffix, body), UTF8);
    if (endpoint.unwrap() != null) {
      try {
        raw = endpoint.unwrap().unwrap(raw);
      } catch (Exception e) {
        String status = "UNKNOWN";
        if (e instanceof GenkitException ge && ge.getErrorCode() != null) {
          status = ge.getErrorCode();
        }
        throw Wire.error(status, endpoint.name() + ": " + e.getMessage(), e);
      }
    }
    JsonNode node;
    try {
      node = raw == null ? null : Wire.tree(raw);
    } catch (JsonProcessingException e) {
      throw Wire.error(
          "INTERNAL",
          endpoint.name() + ": response is not a System One answer: " + e.getOriginalMessage(),
          e);
    }
    if (node != null && !node.isObject() && !node.isNull()) {
      throw Wire.error(
          "INTERNAL", endpoint.name() + ": response is not a System One answer: not an object");
    }
    JsonNode answers = node == null ? null : node.get("answers");
    if (answers == null || answers.isNull()) {
      throw Wire.error("INTERNAL", endpoint.name() + ": response carries no answers");
    }
    if (!answers.isObject()) {
      throw Wire.error(
          "INTERNAL",
          endpoint.name() + ": response is not a System One answer: answers is not an object");
    }
    Map<String, Map<String, Object>> answerMap =
        Wire.MAPPER.convertValue(
            answers, new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
    Info info =
        new Info(
            text(node.get("model")), text(node.get("provider")), text(node.get("id")), answerMap);
    JsonNode usage = node.path("usage");
    JsonNode cost = usage.get("cost");
    return new Answered(
        info,
        usage.path("input_tokens").asInt(0),
        usage.path("output_tokens").asInt(0),
        cost != null && cost.isNumber() ? cost.asDouble() : null);
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : "";
  }

  /**
   * Fetches the endpoint's model listing. It is sent once, with no retries: the listing serves the
   * Dev UI, and the caller falls back to the known models when it fails. A bare array and the usual
   * wrapping keys are all accepted.
   */
  List<ListedModel> listModels(OkHttpClient listingHttp) {
    if (endpoint.modelsPath() == null || endpoint.modelsPath().isEmpty()) {
      throw Wire.error("UNIMPLEMENTED", endpoint.name() + ": no model listing");
    }
    Attempt attempt = once(listingHttp, "GET", baseUrl + endpoint.modelsPath(), null);
    if (attempt.error != null) {
      throw attempt.error;
    }
    JsonNode node;
    try {
      node = Wire.tree(new String(attempt.data, UTF8));
    } catch (JsonProcessingException e) {
      throw Wire.error(
          "INTERNAL",
          endpoint.name() + ": model listing is not JSON: " + e.getOriginalMessage(),
          e);
    }
    if (node != null && node.isArray()) {
      return models(node);
    }
    if (node != null && node.isObject()) {
      for (String key : List.of("models", "data")) {
        JsonNode list = node.get(key);
        if (list != null && list.isArray()) {
          return models(list);
        }
      }
    } else {
      throw Wire.error("INTERNAL", endpoint.name() + ": model listing is not JSON");
    }
    throw Wire.error("INTERNAL", endpoint.name() + ": model listing has no models array");
  }

  private static List<ListedModel> models(JsonNode list) {
    List<ListedModel> models = new ArrayList<>();
    for (JsonNode m : list) {
      models.add(
          new ListedModel(
              text(m.get("id")),
              text(m.get("name")),
              text(m.get("description")),
              text(m.get("release_date"))));
    }
    return models;
  }

  /**
   * Sends one request with retries and returns the response body. A request that fails to connect,
   * times out, is rate limited, or hits a server error other than 501 and 505 is retried.
   */
  byte[] send(String method, String url, Object body) {
    byte[] payload = null;
    if (body != null) {
      try {
        payload = Wire.MAPPER.writeValueAsBytes(body);
      } catch (JsonProcessingException e) {
        throw Wire.error(
            "INVALID_ARGUMENT",
            endpoint.name() + ": request is not JSON: " + e.getOriginalMessage(),
            e);
      }
    }
    for (int attempt = 0; ; attempt++) {
      Attempt result = once(http, method, url, payload);
      if (result.error == null) {
        return result.data;
      }
      if (result.cancelled) {
        throw result.error;
      }
      if (attempt >= MAX_RETRIES || !result.retry) {
        throw result.error;
      }
      long delay = backoff(attempt).toMillis();
      if (result.retryAfter != null) {
        delay = Math.min(result.retryAfter.toMillis(), RETRY_AFTER_CAP.toMillis());
      }
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw stopped(result.error, e);
      }
    }
  }

  /**
   * The error for a request whose caller gave up: a cancellation, so the caller sees it rather than
   * the retryable failure it interrupted, whose message is kept as text only.
   */
  private static GenkitException stopped(GenkitException last, Throwable cause) {
    return Wire.error(
        "CANCELLED", "systemone: cancelled (last attempt: " + last.getMessage() + ")", cause);
  }

  /** The outcome of one attempt. */
  private static final class Attempt {
    byte[] data;
    boolean retry;
    boolean cancelled;
    Duration retryAfter;
    GenkitException error;
  }

  /**
   * Sends one attempt. It reports whether a failure is worth a retry, which is a failure to reach
   * the endpoint, a timeout, a rate limit, or a server error, and the Retry-After the server asked
   * for, which is null when the server did not send one.
   */
  private Attempt once(OkHttpClient client, String method, String url, byte[] payload) {
    Attempt a = new Attempt();
    okhttp3.Request.Builder builder;
    try {
      builder = new okhttp3.Request.Builder().url(url);
    } catch (IllegalArgumentException e) {
      a.error = Wire.error("INVALID_ARGUMENT", endpoint.name() + ": " + e.getMessage(), e);
      return a;
    }
    headers.forEach(
        (name, values) -> {
          for (String value : values) {
            builder.addHeader(name, value);
          }
        });
    if (apiKey != null && !apiKey.isEmpty()) {
      builder.header("Authorization", "Bearer " + apiKey);
    }
    builder.header("Accept", "application/json");
    builder.method(method, payload == null ? null : RequestBody.create(payload, JSON));
    if (payload != null) {
      builder.header("Content-Type", "application/json");
    }
    try (Response resp = client.newCall(builder.build()).execute()) {
      byte[] data;
      try {
        data = readLimited(resp.body());
      } catch (IOException e) {
        return failed(a, e, endpoint.name() + ": reading response: " + e.getMessage());
      }
      int code = resp.code();
      if (code / 100 == 2) {
        a.data = data;
        return a;
      }
      a.retry = code == 408 || code == 429 || (code >= 500 && code != 501 && code != 505);
      a.retryAfter = parseRetryAfter(resp.header("Retry-After"));
      a.error = httpError(endpoint.name(), code, data);
      return a;
    } catch (IOException e) {
      return failed(a, e, endpoint.name() + ": " + e.getMessage());
    }
  }

  /** A failure to reach the endpoint: retried, unless the caller gave up. */
  private static Attempt failed(Attempt a, IOException e, String message) {
    a.error = Wire.error("UNAVAILABLE", message, e);
    if (e instanceof InterruptedIOException && Thread.currentThread().isInterrupted()) {
      a.cancelled = true;
      a.error = stopped(a.error, e);
      return a;
    }
    a.retry = true;
    return a;
  }

  private static byte[] readLimited(ResponseBody body) throws IOException {
    if (body == null) {
      return new byte[0];
    }
    try (InputStream in = body.byteStream()) {
      return in.readNBytes(MAX_BODY_BYTES);
    }
  }

  /**
   * Maps an error response onto a Genkit status: the canonical HTTP mapping, with the codes the
   * endpoints give their own meaning to handled first.
   */
  static GenkitException httpError(String endpoint, int code, byte[] body) {
    String status;
    if (code == 402) {
      // Out of credit at a gateway: a quota, not a bad request.
      status = "RESOURCE_EXHAUSTED";
    } else if (code == 408 || code == 504 || code == 524) {
      // 524 is Cloudflare's origin timeout.
      status = "DEADLINE_EXCEEDED";
    } else if (code == 501 || code == 505) {
      // A server without the path or the protocol: a setup to fix, not a failure to wait out.
      status = "UNIMPLEMENTED";
    } else if (code > 500) {
      // Every other server-side failure reads as transient.
      status = "UNAVAILABLE";
    } else {
      status =
          switch (code) {
            case 400 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "ABORTED";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 499 -> "CANCELLED";
            case 500 -> "INTERNAL";
            // A client error the mapping does not name, such as 422, is the request's fault.
            default -> "INVALID_ARGUMENT";
          };
    }
    String message = errorMessage(body);
    if (message.isEmpty()) {
      message = statusText(code);
    }
    if (message.isEmpty()) {
      message = "empty response";
    }
    return Wire.error(status, endpoint + ": HTTP " + code + ": " + message);
  }

  /**
   * Reads the message out of an error body. The endpoints use four shapes: {error: {message}} or
   * {error: "..."}, a {message}, a Cloudflare {errors: [{code, message}]} envelope, and a
   * validation list, sent under detail as {loc, msg} records or as a bare array of {path, message}
   * records. A body in none of these shapes is quoted as it came, and an empty one gives "".
   */
  static String errorMessage(byte[] body) {
    String text = body == null ? "" : new String(body, UTF8);
    JsonNode node = null;
    try {
      node = text.isBlank() ? null : Wire.tree(text);
    } catch (JsonProcessingException e) {
      // Not JSON: quoted below.
    }
    String msg = validationErrors(node);
    if (!msg.isEmpty()) {
      return msg;
    }
    if (node != null && node.isObject()) {
      JsonNode error = node.get("error");
      if (error != null) {
        if (error.isObject() && error.path("message").isTextual()) {
          String nested = error.get("message").asText();
          if (!nested.isEmpty()) {
            return nested;
          }
        }
        if (error.isTextual() && !error.asText().isEmpty()) {
          return error.asText();
        }
      }
      if (node.path("message").isTextual() && !node.get("message").asText().isEmpty()) {
        return node.get("message").asText();
      }
      msg = validationErrors(node.get("errors"));
      if (!msg.isEmpty()) {
        return msg;
      }
      JsonNode detail = node.get("detail");
      if (detail != null) {
        msg = validationErrors(detail);
        if (!msg.isEmpty()) {
          return msg;
        }
        if (detail.isTextual() && !detail.asText().isEmpty()) {
          return detail.asText();
        }
        return detail.toString();
      }
    }
    String trimmed = text.strip();
    if (trimmed.length() > 200) {
      trimmed = trimmed.substring(0, 200) + "...";
    }
    return trimmed;
  }

  /**
   * Joins a list of validation records into one line, each as its path and message, or returns ""
   * when the value is not such a list.
   */
  private static String validationErrors(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty()) {
      return "";
    }
    for (JsonNode record : node) {
      if (!record.isObject()) {
        return "";
      }
    }
    List<String> msgs = new ArrayList<>();
    for (JsonNode record : node) {
      String msg = record.path("message").asText("");
      if (msg.isEmpty()) {
        msg = record.path("msg").asText("");
      }
      if (msg.isEmpty()) {
        continue;
      }
      List<String> path = new ArrayList<>();
      for (String field : List.of("path", "loc")) {
        JsonNode segments = record.get(field);
        if (segments != null && segments.isArray()) {
          for (JsonNode segment : segments) {
            path.add(segment.isTextual() ? segment.asText() : segment.toString());
          }
        }
      }
      if (!path.isEmpty()) {
        msg = String.join(".", path) + ": " + msg;
      }
      msgs.add(msg);
    }
    return String.join("; ", msgs);
  }

  /** The delay before a retry: half a second doubling to five, less up to a quarter of jitter. */
  static Duration backoff(int attempt) {
    long delay = Math.min(RETRY_BASE.toMillis() << attempt, RETRY_CAP.toMillis());
    long jitter = (long) (ThreadLocalRandom.current().nextDouble() * 0.25 * delay);
    return Duration.ofMillis(delay - jitter);
  }

  /**
   * Reads a Retry-After header, given either as seconds or as an HTTP date. It is null when there
   * is no usable header, and zero when the server asks for an immediate retry.
   */
  static Duration parseRetryAfter(String header) {
    if (header == null || header.isEmpty()) {
      return null;
    }
    try {
      return Duration.ofSeconds(Math.max(Long.parseLong(header.strip()), 0));
    } catch (NumberFormatException e) {
      // Not seconds; try a date.
    }
    try {
      ZonedDateTime at = ZonedDateTime.parse(header.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration until = Duration.between(ZonedDateTime.now(at.getZone()), at);
      return until.isNegative() ? Duration.ZERO : until;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The standard reason phrase of an HTTP status code, or "" for one without. */
  static String statusText(int code) {
    return switch (code) {
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 402 -> "Payment Required";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 408 -> "Request Timeout";
      case 409 -> "Conflict";
      case 413 -> "Request Entity Too Large";
      case 415 -> "Unsupported Media Type";
      case 422 -> "Unprocessable Entity";
      case 429 -> "Too Many Requests";
      case 500 -> "Internal Server Error";
      case 501 -> "Not Implemented";
      case 502 -> "Bad Gateway";
      case 503 -> "Service Unavailable";
      case 504 -> "Gateway Timeout";
      case 505 -> "HTTP Version Not Supported";
      default -> "";
    };
  }

  private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
}
