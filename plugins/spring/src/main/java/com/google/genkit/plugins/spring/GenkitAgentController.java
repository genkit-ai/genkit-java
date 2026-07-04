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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.BidiAction;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.Registry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * REST controller that exposes Genkit agents ({@code ActionType.AGENT} bidi actions) as HTTP
 * endpoints, at parity with the {@code plugins/jetty} module's {@code AgentHandler} / {@code
 * CompanionHandler}.
 *
 * <p>For each registered agent named {@code <name>} this mounts, at the root path (NOT under {@code
 * /api/...}, so that {@code RemoteAgent}/{@code HttpAgentTransport} clients that derive companion
 * URLs by simple string concatenation on the base agent URL keep working regardless of which server
 * plugin they talk to):
 *
 * <ul>
 *   <li>{@code POST /<name>} — one turn per request (non-streaming or SSE).
 *   <li>{@code POST /<name>/getSnapshot} — companion {@code agent-snapshot} action, if registered.
 *   <li>{@code POST /<name>/abort} — companion {@code agent-abort} action, if registered.
 * </ul>
 *
 * <p>Actions are looked up by name at request time (mirroring {@link
 * GenkitFlowController#findFlowByName}) rather than pre-registered per-agent handlers, since Spring
 * MVC controllers use static {@code @RequestMapping}-family annotations.
 */
@RestController
public class GenkitAgentController {

  private static final Logger logger = LoggerFactory.getLogger(GenkitAgentController.class);

  private final ObjectMapper objectMapper;

  /**
   * Creates a new GenkitAgentController.
   *
   * @param objectMapper the ObjectMapper for JSON serialization
   */
  public GenkitAgentController(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    logRegisteredEndpoints();
  }

  private Registry getRegistry() {
    SpringPlugin plugin = SpringPlugin.getInstance();
    return plugin != null ? plugin.getRegistry() : null;
  }

  /** Logs all registered agent endpoints (and their companions, if present). */
  private void logRegisteredEndpoints() {
    Registry registry = getRegistry();
    if (registry == null) {
      return;
    }
    List<Action<?, ?, ?>> agents = registry.listActions(ActionType.AGENT);
    for (Action<?, ?, ?> action : agents) {
      String name = action.getName();
      logger.info("Registered agent endpoint: /{}", name);
      if (registry.lookupAction(ActionType.AGENT_SNAPSHOT.keyFromName(name)) != null) {
        logger.info("Registered agent companion endpoint: /{}/getSnapshot", name);
      }
      if (registry.lookupAction(ActionType.AGENT_ABORT.keyFromName(name)) != null) {
        logger.info("Registered agent companion endpoint: /{}/abort", name);
      }
    }
  }

  /**
   * Finds an agent action by name.
   *
   * @param agentName the name of the agent
   * @return the bidi action, or null if not found or not a {@link BidiAction}
   */
  private BidiAction<Object, Object, Object, Object> findAgentByName(String agentName) {
    Registry registry = getRegistry();
    if (registry == null) {
      return null;
    }
    List<Action<?, ?, ?>> agents = registry.listActions(ActionType.AGENT);
    for (Action<?, ?, ?> action : agents) {
      if (action.getName().equals(agentName) && action instanceof BidiAction) {
        @SuppressWarnings("unchecked")
        BidiAction<Object, Object, Object, Object> bidi =
            (BidiAction<Object, Object, Object, Object>) action;
        return bidi;
      }
    }
    return null;
  }

  /**
   * Finds a companion action (getSnapshot/abort) by its {@link ActionType} and agent name.
   *
   * @param type the companion action type ({@code AGENT_SNAPSHOT} or {@code AGENT_ABORT})
   * @param agentName the agent name
   * @return the companion action, or null if not registered
   */
  @SuppressWarnings("unchecked")
  private Action<Object, Object, Object> findCompanion(ActionType type, String agentName) {
    Registry registry = getRegistry();
    if (registry == null) {
      return null;
    }
    Action<?, ?, ?> action = registry.lookupAction(type.keyFromName(agentName));
    return (Action<Object, Object, Object>) action;
  }

  // ---------------------------------------------------------------------------
  // Main turn endpoint
  // ---------------------------------------------------------------------------

  /**
   * Handles one turn of an agent conversation. Non-streaming requests receive a single {@code
   * {"result": <output>}} JSON body; requests that ask for streaming (via {@code Accept:
   * text/event-stream} or {@code ?stream=true}) receive an SSE response instead — see {@link
   * #streamAgent}.
   *
   * @param agentName the name of the agent
   * @param body the request envelope: {@code {"data": <AgentInput>, "init": <AgentInit>, "context":
   *     <optional map>}}
   * @param request the servlet request, used to detect streaming
   * @return the JSON result envelope, or an error envelope with a mapped HTTP status
   */
  @PostMapping(value = "/{agentName}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Object runAgent(
      @PathVariable String agentName,
      @RequestBody(required = false) JsonNode body,
      HttpServletRequest request) {
    if (isStreamingRequested(request)) {
      return streamAgent(agentName, body, request);
    }

    Registry registry = getRegistry();
    if (registry == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "Registry not initialized"));
    }

    BidiAction<Object, Object, Object, Object> action = findAgentByName(agentName);
    if (action == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "Agent not found: " + agentName));
    }

    JsonNode data = body != null ? body.get("data") : null;
    JsonNode init = body != null ? body.get("init") : null;
    Map<String, Object> userContext = mergeHeadersIntoContext(parseContext(body), request);

    try {
      BufferedInputSource<JsonNode> inputs = new BufferedInputSource<>();
      if (data != null && !data.isNull()) {
        inputs.offer(data);
      }
      inputs.end();

      ActionContext context =
          ActionContext.builder().registry(registry).context(userContext).build();
      JsonNode result = action.runBidiJson(context, init, inputs, null);

      Map<String, Object> envelope = new HashMap<>();
      envelope.put("result", result);
      return ResponseEntity.ok(envelope);
    } catch (Exception e) {
      logger.error("Error handling agent request: {}", agentName, e);
      return errorResponse(e);
    }
  }

  /**
   * Handles one streaming turn of an agent conversation over SSE. Emits one {@code data:
   * {"message": <chunk>}} frame per streamed chunk, then a final {@code data: {"result": <output>}}
   * frame (or {@code data: {"error": {...}}} on failure). The turn runs on a background thread so
   * the servlet container's async dispatch mechanism can flush frames as they are produced.
   *
   * @param agentName the name of the agent
   * @param body the request envelope: {@code {"data": <AgentInput>, "init": <AgentInit>, "context":
   *     <optional map>}}
   * @param request the servlet request, whose headers are merged into the run's user context
   * @return a response wrapping a {@link ResponseBodyEmitter} that streams the turn's chunks and
   *     final result, with the {@code X-Genkit-Stream-Id} header set (matching Jetty's {@code
   *     AgentHandler})
   */
  private ResponseEntity<ResponseBodyEmitter> streamAgent(
      String agentName, JsonNode body, HttpServletRequest request) {
    // A plain ResponseBodyEmitter (rather than SseEmitter) is used because SseEmitter's
    // SseEventBuilder hardcodes a "data:" prefix with no trailing space, whereas Jetty's
    // AgentHandler (and the HttpAgentTransport client's SSE_DATA_PREFIX = "data: " parser) require
    // a space after the colon. Sending fully pre-formatted "data: <json>\n\n" strings directly as
    // TEXT_PLAIN chunks reproduces Jetty's exact byte-level framing.
    ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);

    Registry registry = getRegistry();
    if (registry == null) {
      completeWithEnvelopeError(emitter, "SERVICE_UNAVAILABLE", "Registry not initialized");
      return sseResponse(emitter);
    }

    BidiAction<Object, Object, Object, Object> action = findAgentByName(agentName);
    if (action == null) {
      completeWithEnvelopeError(emitter, "NOT_FOUND", "Agent not found: " + agentName);
      return sseResponse(emitter);
    }

    JsonNode data = body != null ? body.get("data") : null;
    JsonNode init = body != null ? body.get("init") : null;
    Map<String, Object> userContext = mergeHeadersIntoContext(parseContext(body), request);

    Thread worker =
        new Thread(
            () -> {
              Consumer<JsonNode> streamCallback =
                  chunk -> {
                    try {
                      Map<String, Object> frame = new HashMap<>();
                      frame.put("message", chunk);
                      sendSseFrame(emitter, frame);
                    } catch (Exception e) {
                      throw new RuntimeException("Failed to write SSE frame", e);
                    }
                  };

              try {
                BufferedInputSource<JsonNode> inputs = new BufferedInputSource<>();
                if (data != null && !data.isNull()) {
                  inputs.offer(data);
                }
                inputs.end();

                ActionContext context =
                    ActionContext.builder().registry(registry).context(userContext).build();
                JsonNode result = action.runBidiJson(context, init, inputs, streamCallback);

                Map<String, Object> resultFrame = new HashMap<>();
                resultFrame.put("result", result);
                sendSseFrame(emitter, resultFrame);
                emitter.complete();
              } catch (Exception e) {
                logger.error("Error streaming agent request: {}", agentName, e);
                try {
                  Map<String, Object> errorFrame = new HashMap<>();
                  errorFrame.put("error", errorBody(errorCodeFromError(e), e));
                  sendSseFrame(emitter, errorFrame);
                  emitter.complete();
                } catch (Exception writeError) {
                  emitter.completeWithError(writeError);
                }
              }
            },
            "genkit-agent-sse-" + agentName);
    worker.setDaemon(true);
    worker.start();

    return sseResponse(emitter);
  }

  /**
   * Wraps a {@link ResponseBodyEmitter} in a {@link ResponseEntity} carrying the {@code
   * X-Genkit-Stream-Id} header and {@code text/event-stream} content type, mirroring Jetty's {@code
   * AgentHandler#handleStreaming}. Headers must be set before the emitter is returned from the
   * controller method, since headers cannot be added once the async response body starts streaming.
   */
  private ResponseEntity<ResponseBodyEmitter> sseResponse(ResponseBodyEmitter emitter) {
    return ResponseEntity.ok()
        .header("X-Genkit-Stream-Id", UUID.randomUUID().toString())
        .header("Cache-Control", "no-cache")
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(emitter);
  }

  /**
   * Writes one SSE frame in Jetty's exact wire format: {@code "data: " + json + "\n\n"}, sent as a
   * raw {@code TEXT_PLAIN} chunk so no additional SSE-specific encoding is applied.
   */
  private void sendSseFrame(ResponseBodyEmitter emitter, Object payload) throws Exception {
    String frame = "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
    emitter.send(frame, MediaType.TEXT_PLAIN);
  }

  /** Sends a single error SSE frame (used before the turn even starts) and completes. */
  private void completeWithEnvelopeError(ResponseBodyEmitter emitter, String code, String message) {
    try {
      Map<String, Object> errorFrame = new HashMap<>();
      Map<String, Object> error = new HashMap<>();
      error.put("status", code);
      error.put("message", message);
      error.put("details", Map.of());
      errorFrame.put("error", error);
      sendSseFrame(emitter, errorFrame);
      emitter.complete();
    } catch (Exception e) {
      emitter.completeWithError(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Companion endpoints: getSnapshot / abort
  // ---------------------------------------------------------------------------

  /**
   * Companion endpoint for retrieving a previously-saved agent session snapshot.
   *
   * @param agentName the name of the agent
   * @param body the request envelope: {@code {"data": <input>, "context": <optional map>}}
   * @param request the servlet request, whose headers are merged into the run's user context
   * @return the JSON result envelope, or an error envelope with a mapped HTTP status
   */
  @PostMapping(value = "/{agentName}/getSnapshot", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> getSnapshot(
      @PathVariable String agentName,
      @RequestBody(required = false) JsonNode body,
      HttpServletRequest request) {
    return runCompanion(ActionType.AGENT_SNAPSHOT, agentName, body, request);
  }

  /**
   * Companion endpoint for aborting a pending agent session snapshot.
   *
   * @param agentName the name of the agent
   * @param body the request envelope: {@code {"data": <input>, "context": <optional map>}}
   * @param request the servlet request, whose headers are merged into the run's user context
   * @return the JSON result envelope, or an error envelope with a mapped HTTP status
   */
  @PostMapping(value = "/{agentName}/abort", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> abort(
      @PathVariable String agentName,
      @RequestBody(required = false) JsonNode body,
      HttpServletRequest request) {
    return runCompanion(ActionType.AGENT_ABORT, agentName, body, request);
  }

  private ResponseEntity<Object> runCompanion(
      ActionType type, String agentName, JsonNode body, HttpServletRequest request) {
    Registry registry = getRegistry();
    if (registry == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "Registry not initialized"));
    }

    Action<Object, Object, Object> action = findCompanion(type, agentName);
    if (action == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "Agent companion not found: " + agentName));
    }

    JsonNode data = body != null ? body.get("data") : null;
    Map<String, Object> userContext = mergeHeadersIntoContext(parseContext(body), request);

    try {
      ActionContext context =
          ActionContext.builder().registry(registry).context(userContext).build();
      JsonNode result = action.runJson(context, data, null);

      Map<String, Object> envelope = new HashMap<>();
      envelope.put("result", result);
      return ResponseEntity.ok(envelope);
    } catch (Exception e) {
      logger.error("Error handling agent companion request: {}", agentName, e);
      return errorResponse(e);
    }
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  /**
   * Parses the optional {@code context} object from a request body envelope into a {@code
   * Map<String,Object>}. Threaded into the run's ActionContext so tools/flows can read it.
   *
   * @param body the parsed request body (may be null)
   * @return the parsed context map, or null if absent/blank
   */
  private Map<String, Object> parseContext(JsonNode body) {
    if (body == null || !body.has("context") || body.get("context").isNull()) {
      return null;
    }
    JsonNode contextNode = body.get("context");
    if (!contextNode.isObject()) {
      return null;
    }
    return objectMapper.convertValue(contextNode, new TypeReference<Map<String, Object>>() {});
  }

  /**
   * HTTP framing/transport headers that are excluded from the {@code "headers"} sub-map threaded
   * into the run's {@link ActionContext}. These describe the HTTP message itself (body encoding,
   * connection lifecycle, routing) rather than application-level data a tool/flow would care about.
   * Everything else — including custom headers like {@code Authorization} or {@code X-*} — is
   * included; when in doubt we err on the side of including a header rather than filtering it.
   *
   * <p>Kept identical to {@code plugins/jetty}'s {@code JettyPlugin.EXCLUDED_HEADERS} so a tool
   * reading {@code ctx.getContext().get("headers")} behaves the same regardless of which server
   * plugin served the request.
   */
  private static final Set<String> EXCLUDED_HEADERS =
      Set.of("content-type", "content-length", "accept", "accept-encoding", "connection", "host");

  /**
   * Merges incoming HTTP request headers into the request-scoped user context returned by {@link
   * #parseContext(JsonNode)}.
   *
   * <p><b>Design:</b> headers are exposed to tools/flows the same way the JSON-body {@code context}
   * field already is — via {@link ActionContext#getContext()} — by nesting them under a reserved
   * {@code "headers"} key as a {@code Map<String,String>} (single-valued, to match the shape of
   * {@code RemoteAgentOptions.headers()} on the client). This is additive and cannot silently
   * collide with arbitrary body-context keys other than a literal top-level {@code "headers"} key.
   * <b>Precedence:</b> if the body-supplied {@code context} already defines a {@code "headers"}
   * entry, that body value wins and incoming HTTP headers are dropped for that key (body {@code
   * context} always takes precedence over transport-level data); otherwise the HTTP headers
   * populate {@code context.get("headers")}.
   *
   * @param bodyContext the context map parsed from the request body (may be null)
   * @param request the incoming servlet request, whose headers are folded in
   * @return a merged context map (never null if headers are present; may be null if both the body
   *     context and the header set are empty)
   */
  private Map<String, Object> mergeHeadersIntoContext(
      Map<String, Object> bodyContext, HttpServletRequest request) {
    Map<String, String> headers = parseHeaders(request);
    if (headers.isEmpty()) {
      return bodyContext;
    }
    Map<String, Object> merged = bodyContext != null ? new HashMap<>(bodyContext) : new HashMap<>();
    merged.putIfAbsent("headers", headers);
    return merged;
  }

  /**
   * Extracts incoming HTTP request headers (excluding standard framing headers, see {@link
   * #EXCLUDED_HEADERS}) into a {@code Map<String,String>}. If a header name repeats, the last value
   * wins (mirroring {@code HttpServletRequest#getHeader}).
   *
   * @param request the incoming servlet request
   * @return a mutable map of header name to value (never null; may be empty)
   */
  private static Map<String, String> parseHeaders(HttpServletRequest request) {
    Map<String, String> result = new HashMap<>();
    Enumeration<String> names = request.getHeaderNames();
    if (names == null) {
      return result;
    }
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      if (name == null || EXCLUDED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        continue;
      }
      result.put(name, request.getHeader(name));
    }
    return result;
  }

  /** Returns true if the request asks for SSE streaming. */
  private static boolean isStreamingRequested(HttpServletRequest request) {
    String accept = request.getHeader("Accept");
    if (accept != null && accept.contains("text/event-stream")) {
      return true;
    }
    String query = request.getQueryString();
    return query != null && query.contains("stream=true");
  }

  /** Builds a {@code {"error": {...}}} envelope response with a status-mapped HTTP code. */
  private ResponseEntity<Object> errorResponse(Exception e) {
    int httpStatus = statusFromError(e);
    String code = errorCodeFromError(e);
    Map<String, Object> envelope = new HashMap<>();
    envelope.put("error", errorBody(code, e));
    return ResponseEntity.status(httpStatus).body(envelope);
  }

  /** Builds a structured error body: {@code {status, message, details: {stack}}}. */
  private static Map<String, Object> errorBody(String code, Throwable e) {
    String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    Map<String, Object> body = new HashMap<>();
    body.put("status", code);
    body.put("message", message);
    body.put("details", Map.of("stack", sw.toString()));
    return body;
  }

  /** Derives a status string (mirroring the error's status field) from a thrown error. */
  private static String errorCodeFromError(Throwable e) {
    if (e instanceof GenkitException) {
      String c = ((GenkitException) e).getErrorCode();
      if (c != null && !c.isEmpty()) {
        return c;
      }
    }
    return "INTERNAL";
  }

  /** Maps a thrown error to an HTTP status code derived from its status field. */
  private static int statusFromError(Throwable e) {
    String code = errorCodeFromError(e);
    switch (code) {
      case "NOT_FOUND":
        return 404;
      case "INVALID_ARGUMENT":
      case "FAILED_PRECONDITION":
      case "OUT_OF_RANGE":
        return 400;
      case "UNAUTHENTICATED":
        return 401;
      case "PERMISSION_DENIED":
        return 403;
      case "ALREADY_EXISTS":
      case "ABORTED":
        return 409;
      case "RESOURCE_EXHAUSTED":
        return 429;
      case "UNIMPLEMENTED":
        return 501;
      case "UNAVAILABLE":
        return 503;
      case "DEADLINE_EXCEEDED":
        return 504;
      default:
        return 500;
    }
  }
}
