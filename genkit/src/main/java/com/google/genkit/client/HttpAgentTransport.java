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

package com.google.genkit.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentOutput;
import com.google.genkit.ai.agent.AgentStreamChunk;
import com.google.genkit.ai.agent.AgentTransport;
import com.google.genkit.ai.agent.GetSnapshotRequest;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * HTTP implementation of {@link AgentTransport} that speaks the Jetty {@code AgentHandler} wire
 * format.
 *
 * <p>Wire format:
 *
 * <ul>
 *   <li>Turn: {@code POST url} with body {@code {"data":<AgentInput>,"init":<AgentInit>}} and
 *       {@code Accept: text/event-stream}. SSE frames are {@code data: {"message":<chunk>}} then
 *       {@code data: {"result":<AgentOutput>}}. Error frames are {@code data: {"error":{...}}}.
 *   <li>getSnapshot: {@code POST url/getSnapshot} with body {@code {"data":<req>}} → {@code
 *       {"result":<snapshot>}}.
 *   <li>abort: {@code POST url/abort} with body {@code {"data":{"snapshotId":...}}} → {@code
 *       {"result":{"status":...}}}.
 * </ul>
 *
 * @param <S> the type of custom session state
 */
public final class HttpAgentTransport<S> implements AgentTransport<S> {

  private static final String SSE_DATA_PREFIX = "data: ";

  private final RemoteAgentOptions opts;
  private final ObjectMapper mapper;
  private final HttpClient httpClient;

  /**
   * Constructs an {@link HttpAgentTransport}.
   *
   * @param opts the options specifying the endpoint URLs, headers, and serverManaged flag
   */
  public HttpAgentTransport(RemoteAgentOptions opts) {
    this.opts = opts;
    this.mapper = JsonUtils.getObjectMapper();
    this.httpClient = HttpClient.newHttpClient();
  }

  @Override
  public AgentOutput<S> runTurn(
      AgentInput input, AgentInit<S> init, Consumer<AgentStreamChunk> onChunk) {
    try {
      // Build request envelope: {"data": <input>, "init": <init>}
      ObjectNode envelope = mapper.createObjectNode();
      envelope.set("data", mapper.valueToTree(input != null ? input : new AgentInput()));
      if (init != null) {
        envelope.set("init", mapper.valueToTree(init));
      }
      String body = mapper.writeValueAsString(envelope);

      HttpRequest.Builder reqBuilder =
          HttpRequest.newBuilder()
              .uri(URI.create(opts.url()))
              .header("Content-Type", "application/json")
              .header("Accept", "text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

      for (Map.Entry<String, String> entry : opts.headers().entrySet()) {
        reqBuilder.header(entry.getKey(), entry.getValue());
      }

      HttpResponse<java.io.InputStream> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

      if (response.statusCode() >= 400) {
        String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        throw new GenkitException("Agent HTTP error " + response.statusCode() + ": " + errorBody);
      }

      // Parse SSE stream
      String contentType = response.headers().firstValue("Content-Type").orElse("");
      if (contentType.contains("text/event-stream")) {
        return parseSseStream(response.body(), onChunk);
      } else {
        // Non-streaming response: {"result": <output>}
        String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(responseBody);
        return deserializeOutput(root.get("result"));
      }
    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("HttpAgentTransport.runTurn failed", e);
    }
  }

  /**
   * Parses an SSE stream, dispatching {@code data: {"message":...}} frames to {@code onChunk} and
   * returning the {@link AgentOutput} from the terminal {@code data: {"result":...}} frame.
   */
  private AgentOutput<S> parseSseStream(
      java.io.InputStream inputStream, Consumer<AgentStreamChunk> onChunk) throws Exception {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith(SSE_DATA_PREFIX)) {
          continue;
        }
        String json = line.substring(SSE_DATA_PREFIX.length()).trim();
        if (json.isEmpty()) {
          continue;
        }
        JsonNode frame = mapper.readTree(json);

        if (frame.has("error")) {
          JsonNode err = frame.get("error");
          String msg = err.path("message").asText("agent error");
          throw new GenkitException(msg);
        }

        if (frame.has("result")) {
          return deserializeOutput(frame.get("result"));
        }

        if (frame.has("message") && onChunk != null) {
          JsonNode chunkNode = frame.get("message");
          AgentStreamChunk chunk = mapper.treeToValue(chunkNode, AgentStreamChunk.class);
          onChunk.accept(chunk);
        }
      }
    }
    throw new GenkitException("SSE stream ended without a result frame");
  }

  @Override
  @SuppressWarnings("unchecked")
  public SessionSnapshot<S> getSnapshot(GetSnapshotRequest req) {
    try {
      ObjectNode envelope = mapper.createObjectNode();
      envelope.set("data", mapper.valueToTree(req));
      String body = mapper.writeValueAsString(envelope);

      HttpRequest.Builder reqBuilder =
          HttpRequest.newBuilder()
              .uri(URI.create(opts.getSnapshotUrl()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

      for (Map.Entry<String, String> entry : opts.headers().entrySet()) {
        reqBuilder.header(entry.getKey(), entry.getValue());
      }

      HttpResponse<String> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        throw new GenkitException(
            "getSnapshot HTTP error " + response.statusCode() + ": " + response.body());
      }

      JsonNode root = mapper.readTree(response.body());
      JsonNode result = root.get("result");
      if (result == null || result.isNull()) {
        return null;
      }
      return (SessionSnapshot<S>) mapper.treeToValue(result, SessionSnapshot.class);
    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("HttpAgentTransport.getSnapshot failed", e);
    }
  }

  @Override
  public SnapshotStatus abort(String snapshotId) {
    try {
      ObjectNode data = mapper.createObjectNode();
      data.put("snapshotId", snapshotId);
      ObjectNode envelope = mapper.createObjectNode();
      envelope.set("data", data);
      String body = mapper.writeValueAsString(envelope);

      HttpRequest.Builder reqBuilder =
          HttpRequest.newBuilder()
              .uri(URI.create(opts.abortUrl()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

      for (Map.Entry<String, String> entry : opts.headers().entrySet()) {
        reqBuilder.header(entry.getKey(), entry.getValue());
      }

      HttpResponse<String> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 400) {
        throw new GenkitException(
            "abort HTTP error " + response.statusCode() + ": " + response.body());
      }

      JsonNode root = mapper.readTree(response.body());
      JsonNode result = root.get("result");
      if (result == null || result.isNull()) {
        return null;
      }
      String statusStr = result.path("status").asText(null);
      if (statusStr == null || statusStr.isEmpty()) {
        return null;
      }
      try {
        return SnapshotStatus.fromValue(statusStr);
      } catch (IllegalArgumentException e) {
        return null;
      }
    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("HttpAgentTransport.abort failed", e);
    }
  }

  @Override
  public boolean serverManaged() {
    return opts.serverManaged();
  }

  @SuppressWarnings("unchecked")
  private AgentOutput<S> deserializeOutput(JsonNode node) throws Exception {
    if (node == null || node.isNull()) {
      return new AgentOutput<>();
    }
    return (AgentOutput<S>) mapper.treeToValue(node, AgentOutput.class);
  }
}
