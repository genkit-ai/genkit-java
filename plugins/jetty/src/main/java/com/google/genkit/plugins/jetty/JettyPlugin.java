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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JettyPlugin provides HTTP endpoints for Genkit flows.
 *
 * <p>This plugin exposes registered flows as HTTP endpoints, making it easy to deploy Genkit
 * applications as web services.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(new JettyPlugin(JettyPluginOptions.builder().port(8080).build()))
 *     .build();
 *
 * // Define your flows...
 *
 * // Start the server and block (keeps application running)
 * genkit.start();
 * }</pre>
 */
public class JettyPlugin implements ServerPlugin {

  private static final Logger logger = LoggerFactory.getLogger(JettyPlugin.class);

  private final JettyPluginOptions options;
  private Server server;
  private Registry registry;
  private ObjectMapper objectMapper;

  /** Creates a JettyPlugin with default options. */
  public JettyPlugin() {
    this(JettyPluginOptions.builder().build());
  }

  /**
   * Creates a JettyPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public JettyPlugin(JettyPluginOptions options) {
    this.options = options;
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Creates a JettyPlugin with the specified port.
   *
   * @param port the HTTP port
   * @return a new JettyPlugin
   */
  public static JettyPlugin create(int port) {
    return new JettyPlugin(JettyPluginOptions.builder().port(port).build());
  }

  @Override
  public String getName() {
    return "jetty";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    // Jetty plugin doesn't provide actions itself
    return Collections.emptyList();
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    this.registry = registry;
    return Collections.emptyList();
  }

  /**
   * Starts the Jetty server and blocks until it is stopped.
   *
   * <p>This is the recommended way to start the server in a main() method. Similar to Express's
   * app.listen() in JavaScript, this method will keep your application running until the server is
   * explicitly stopped.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());
   *
   * Genkit genkit = Genkit.builder().plugin(jetty).build();
   *
   * // Define your flows...
   *
   * // Start and block
   * jetty.start();
   * }</pre>
   *
   * @throws Exception if the server cannot be started or if interrupted while waiting
   */
  @Override
  public void start() throws Exception {
    if (registry == null) {
      throw new GenkitException(
          "Registry not set. Make sure JettyPlugin is added to Genkit before calling start().");
    }

    startServer();
    server.join();
  }

  /**
   * Starts the Jetty server without blocking.
   *
   * @throws Exception if the server cannot be started
   */
  private void startServer() throws Exception {
    if (server != null) {
      return;
    }

    if (registry == null) {
      throw new GenkitException(
          "Registry not set. Make sure JettyPlugin is added to Genkit before calling start().");
    }

    server = new Server();

    ServerConnector connector = new ServerConnector(server);
    connector.setPort(options.getPort());
    connector.setHost(options.getHost());
    server.addConnector(connector);

    // Create handler collection
    ContextHandlerCollection handlers = new ContextHandlerCollection();

    // Add flow endpoints
    addFlowHandlers(handlers);

    // Add agent endpoints (bidi actions + companions)
    addAgentHandlers(handlers);

    // Add health endpoint
    ContextHandler healthHandler = new ContextHandler("/health");
    healthHandler.setHandler(new HealthHandler());
    handlers.addHandler(healthHandler);

    server.setHandler(handlers);
    server.start();

    logger.info("Jetty server started on {}:{}", options.getHost(), options.getPort());
  }

  /**
   * Stops the Jetty server.
   *
   * @throws Exception if the server cannot be stopped
   */
  @Override
  public void stop() throws Exception {
    if (server != null) {
      server.stop();
      server = null;
      logger.info("Jetty server stopped");
    }
  }

  /**
   * Returns the port the server is listening on.
   *
   * @return the configured port
   */
  @Override
  public int getPort() {
    return options.getPort();
  }

  /**
   * Returns true if the server is currently running.
   *
   * @return true if the server is running, false otherwise
   */
  @Override
  public boolean isRunning() {
    return server != null && server.isRunning();
  }

  /** Adds HTTP handlers for all registered flows. */
  private void addFlowHandlers(ContextHandlerCollection handlers) {
    List<Action<?, ?, ?>> flows = registry.listActions(ActionType.FLOW);

    for (Action<?, ?, ?> action : flows) {
      String path = options.getBasePath() + "/" + action.getName();

      ContextHandler handler = new ContextHandler(path);
      handler.setAllowNullPathInContext(true);
      handler.setHandler(new FlowHandler(action));
      handlers.addHandler(handler);

      logger.info("Registered flow endpoint: {}", path);
    }
  }

  /**
   * Adds HTTP handlers for all registered agents (bidi actions of type {@link ActionType#AGENT}).
   *
   * <p>For each agent named {@code <name>} this mounts:
   *
   * <ul>
   *   <li>{@code POST /<name>} -> one turn per request (non-streaming or SSE).
   *   <li>{@code POST /<name>/getSnapshot} -> companion {@code /agent-snapshot/<name>} if present.
   *   <li>{@code POST /<name>/abort} -> companion {@code /agent-abort/<name>} if present.
   * </ul>
   *
   * <p>Companions may be absent for client-managed agents; only existing ones are mounted.
   */
  private void addAgentHandlers(ContextHandlerCollection handlers) {
    List<Action<?, ?, ?>> agents = registry.listActions(ActionType.AGENT);

    for (Action<?, ?, ?> action : agents) {
      if (!(action instanceof BidiAction)) {
        logger.warn("Agent action {} is not a BidiAction; skipping", action.getName());
        continue;
      }
      String name = action.getName();
      String path = "/" + name;

      // Main one-turn-per-request endpoint.
      ContextHandler agentHandler = new ContextHandler(path);
      agentHandler.setAllowNullPathInContext(true);
      agentHandler.setHandler(new AgentHandler((BidiAction<?, ?, ?, ?>) action));
      handlers.addHandler(agentHandler);
      logger.info("Registered agent endpoint: {}", path);

      // Companion getSnapshot endpoint (if a companion action is registered).
      Action<?, ?, ?> snapshotAction =
          registry.lookupAction(ActionType.AGENT_SNAPSHOT.keyFromName(name));
      if (snapshotAction != null) {
        String snapshotPath = path + "/getSnapshot";
        ContextHandler snapshotHandler = new ContextHandler(snapshotPath);
        snapshotHandler.setAllowNullPathInContext(true);
        snapshotHandler.setHandler(new CompanionHandler(snapshotAction));
        handlers.addHandler(snapshotHandler);
        logger.info("Registered agent companion endpoint: {}", snapshotPath);
      }

      // Companion abort endpoint (if a companion action is registered).
      Action<?, ?, ?> abortAction = registry.lookupAction(ActionType.AGENT_ABORT.keyFromName(name));
      if (abortAction != null) {
        String abortPath = path + "/abort";
        ContextHandler abortHandler = new ContextHandler(abortPath);
        abortHandler.setAllowNullPathInContext(true);
        abortHandler.setHandler(new CompanionHandler(abortAction));
        handlers.addHandler(abortHandler);
        logger.info("Registered agent companion endpoint: {}", abortPath);
      }
    }
  }

  /** Handler for health check endpoint. */
  private class HealthHandler extends Handler.Abstract {
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
      response.setStatus(200);
      response.getHeaders().put("Content-Type", "application/json");

      String json = "{\"status\":\"ok\"}";
      response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);

      return true;
    }
  }

  /** Handler for flow endpoints. */
  private class FlowHandler extends Handler.Abstract {
    private final Action<Object, Object, Object> action;

    @SuppressWarnings("unchecked")
    FlowHandler(Action<?, ?, ?> action) {
      this.action = (Action<Object, Object, Object>) action;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
      try {
        // Only accept POST requests
        if (!"POST".equals(request.getMethod())) {
          response.setStatus(405);
          response.getHeaders().put("Content-Type", "application/json");
          String error = "{\"error\":\"Method not allowed\"}";
          response.write(true, ByteBuffer.wrap(error.getBytes(StandardCharsets.UTF_8)), callback);
          return true;
        }

        // Read request body
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Request.asInputStream(request).transferTo(baos);
        String body = baos.toString(StandardCharsets.UTF_8);

        // Parse input as JsonNode so runJson() can convert to the proper typed class
        JsonNode input = null;
        if (body != null && !body.isEmpty()) {
          input = objectMapper.readTree(body);
        }

        // Run the action using runJson() which properly deserializes to the typed input class
        ActionContext context = new ActionContext(registry);
        JsonNode result = action.runJson(context, input, null);

        // Send response
        response.setStatus(200);
        response.getHeaders().put("Content-Type", "application/json");

        String json = objectMapper.writeValueAsString(result);
        response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);

        return true;
      } catch (Exception e) {
        logger.error("Error handling flow request", e);

        response.setStatus(500);
        response.getHeaders().put("Content-Type", "application/json");

        // Format error with structured error status for proper UI display
        // For HTTP 500, send error status directly (no wrapper)
        // Format: {code, message, details: {stack}}
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String stacktrace = sw.toString();

        Map<String, Object> errorDetails = Map.of("stack", stacktrace);
        Map<String, Object> errorStatus =
            Map.of(
                "code",
                2, // INTERNAL error code
                "message",
                errorMessage,
                "details",
                errorDetails);

        String json = objectMapper.writeValueAsString(errorStatus);
        response.write(true, ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), callback);

        return true;
      }
    }
  }

  /**
   * Handler for agent endpoints. Serves one turn of a bidi agent per HTTP request.
   *
   * <p>Request body envelope is {@code {"data": <AgentInput>, "init": <AgentInit>}}. Streaming is
   * selected when the {@code Accept} header contains {@code text/event-stream} or the query string
   * contains {@code stream=true}; otherwise the final result is returned as a single JSON object.
   */
  private class AgentHandler extends Handler.Abstract {
    private final BidiAction<Object, Object, Object, Object> action;

    @SuppressWarnings("unchecked")
    AgentHandler(BidiAction<?, ?, ?, ?> action) {
      this.action = (BidiAction<Object, Object, Object, Object>) action;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
      // Only accept POST requests.
      if (!"POST".equals(request.getMethod())) {
        writeMethodNotAllowed(response, callback);
        return true;
      }

      // Read and parse the request body.
      JsonNode body;
      try {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Request.asInputStream(request).transferTo(baos);
        String raw = baos.toString(StandardCharsets.UTF_8);
        body = (raw == null || raw.isEmpty()) ? null : objectMapper.readTree(raw);
      } catch (Exception e) {
        logger.error("Error reading agent request body", e);
        writeError(response, callback, 400, "INVALID_ARGUMENT", e);
        return true;
      }

      JsonNode data = body != null ? body.get("data") : null;
      JsonNode init = body != null ? body.get("init") : null;
      Map<String, Object> userContext = mergeHeadersIntoContext(parseContext(body), request);

      boolean streaming = isStreamingRequested(request);
      if (streaming) {
        handleStreaming(response, callback, data, init, userContext);
      } else {
        handleUnary(response, callback, data, init, userContext);
      }
      return true;
    }

    /** Non-streaming: run the turn and write {@code {"result": <output>}} or an error. */
    private void handleUnary(
        Response response,
        Callback callback,
        JsonNode data,
        JsonNode init,
        Map<String, Object> userContext) {
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

        response.setStatus(200);
        response.getHeaders().put("Content-Type", "application/json");
        byte[] bytes = objectMapper.writeValueAsBytes(envelope);
        response.write(true, ByteBuffer.wrap(bytes), callback);
      } catch (Exception e) {
        logger.error("Error handling agent request", e);
        writeError(response, callback, statusFromError(e), errorCodeFromError(e), e);
      }
    }

    /**
     * Streaming: respond with {@code text/event-stream}, emitting one {@code data: {"message":
     * <chunk>}} frame per chunk, then a final {@code data: {"result": <output>}} frame; errors emit
     * a {@code data: {"error": {...}}} frame.
     */
    private void handleStreaming(
        Response response,
        Callback callback,
        JsonNode data,
        JsonNode init,
        Map<String, Object> userContext) {
      response.setStatus(200);
      response.getHeaders().put("Content-Type", "text/event-stream");
      response.getHeaders().put("Cache-Control", "no-cache");
      response.getHeaders().put("Connection", "keep-alive");
      response.getHeaders().put("X-Genkit-Stream-Id", UUID.randomUUID().toString());

      Consumer<JsonNode> streamCallback =
          chunk -> {
            Map<String, Object> frame = new HashMap<>();
            frame.put("message", chunk);
            writeSseFrame(response, frame);
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
        writeSseFrameLast(response, callback, resultFrame);
      } catch (Exception e) {
        logger.error("Error streaming agent request", e);
        Map<String, Object> errorFrame = new HashMap<>();
        errorFrame.put("error", errorBody(errorCodeFromError(e), e));
        writeSseFrameLast(response, callback, errorFrame);
      }
    }

    /** Writes a non-final SSE frame, blocking until the write completes. */
    private void writeSseFrame(Response response, Object payload) {
      try {
        String data = "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
        blockingWrite(response, false, ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception e) {
        throw new RuntimeException("Failed to write SSE frame", e);
      }
    }

    /** Writes the final SSE frame and completes the supplied request callback. */
    private void writeSseFrameLast(Response response, Callback callback, Object payload) {
      try {
        String data = "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
        response.write(true, ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)), callback);
      } catch (Exception e) {
        callback.failed(e);
      }
    }

    /** Performs a Jetty async write and blocks the current thread until it completes. */
    private void blockingWrite(Response response, boolean last, ByteBuffer buffer)
        throws Exception {
      Callback.Completable completable = new Callback.Completable();
      response.write(last, buffer, completable);
      completable.get();
    }
  }

  /**
   * Handler for agent companion endpoints (snapshot / abort). Unary POST over a plain {@link
   * Action} looked up by key. Request body envelope is {@code {"data": <input>}}; the response is
   * {@code {"result": <output>}} or an error.
   */
  private class CompanionHandler extends Handler.Abstract {
    private final Action<Object, Object, Object> action;

    @SuppressWarnings("unchecked")
    CompanionHandler(Action<?, ?, ?> action) {
      this.action = (Action<Object, Object, Object>) action;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
      if (!"POST".equals(request.getMethod())) {
        writeMethodNotAllowed(response, callback);
        return true;
      }

      try {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Request.asInputStream(request).transferTo(baos);
        String raw = baos.toString(StandardCharsets.UTF_8);
        JsonNode body = (raw == null || raw.isEmpty()) ? null : objectMapper.readTree(raw);
        JsonNode data = body != null ? body.get("data") : null;

        ActionContext context =
            ActionContext.builder()
                .registry(registry)
                .context(mergeHeadersIntoContext(parseContext(body), request))
                .build();
        JsonNode result = action.runJson(context, data, null);

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("result", result);

        response.setStatus(200);
        response.getHeaders().put("Content-Type", "application/json");
        byte[] bytes = objectMapper.writeValueAsBytes(envelope);
        response.write(true, ByteBuffer.wrap(bytes), callback);
      } catch (Exception e) {
        logger.error("Error handling agent companion request", e);
        writeError(response, callback, statusFromError(e), errorCodeFromError(e), e);
      }
      return true;
    }
  }

  // ---------------------------------------------------------------------------
  // Shared helpers for agent / companion handlers
  // ---------------------------------------------------------------------------

  /**
   * Parses the optional {@code context} object from a request body envelope into a {@code
   * Map<String,Object>}. Threaded into the run's ActionContext so tools/flows can read it (e.g.
   * {@code {"auth": {"user": "alice"}}}).
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
    return objectMapper.convertValue(
        contextNode, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  /**
   * HTTP framing/transport headers that are excluded from the {@code "headers"} sub-map threaded
   * into the run's {@link ActionContext}. These describe the HTTP message itself (body encoding,
   * connection lifecycle, routing) rather than application-level data a tool/flow would care about.
   * Everything else — including custom headers like {@code Authorization} or {@code X-*} — is
   * included; when in doubt we err on the side of including a header rather than filtering it.
   */
  private static final Set<String> EXCLUDED_HEADERS =
      Set.of("content-type", "content-length", "accept", "accept-encoding", "connection", "host");

  /**
   * Merges incoming HTTP request headers into the request-scoped user context returned by {@link
   * #parseContext(JsonNode)}.
   *
   * <p><b>Design:</b> headers are exposed to tools/flows the same way the JSON-body {@code context}
   * field already is — via {@link com.google.genkit.core.ActionContext#getContext()} — by nesting
   * them under a reserved {@code "headers"} key as a {@code Map<String,String>} (single-valued, to
   * match the shape of {@code RemoteAgentOptions.headers()} on the client). This is additive and
   * cannot silently collide with arbitrary body-context keys other than a literal top-level {@code
   * "headers"} key. <b>Precedence:</b> if the body-supplied {@code context} already defines a
   * {@code "headers"} entry, that body value wins and incoming HTTP headers are dropped for that
   * key (body {@code context} always takes precedence over transport-level data); otherwise the
   * HTTP headers populate {@code context.get("headers")}.
   *
   * @param bodyContext the context map parsed from the request body (may be null)
   * @param request the incoming Jetty request, whose headers are folded in
   * @return a merged context map (never null if headers are present; may be null if both the body
   *     context and the header set are empty)
   */
  private Map<String, Object> mergeHeadersIntoContext(
      Map<String, Object> bodyContext, Request request) {
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
   * wins (mirroring {@code HttpFields#get}).
   *
   * @param request the incoming Jetty request
   * @return a mutable map of header name to value (never null; may be empty)
   */
  private static Map<String, String> parseHeaders(Request request) {
    Map<String, String> result = new HashMap<>();
    for (org.eclipse.jetty.http.HttpField field : request.getHeaders()) {
      String name = field.getName();
      if (name == null || EXCLUDED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        continue;
      }
      result.put(name, field.getValue());
    }
    return result;
  }

  /** Returns true if the request asks for SSE streaming. */
  private static boolean isStreamingRequested(Request request) {
    String accept = request.getHeaders().get("Accept");
    if (accept != null && accept.contains("text/event-stream")) {
      return true;
    }
    String query = request.getHttpURI().getQuery();
    return query != null && query.contains("stream=true");
  }

  /** Writes a 405 Method Not Allowed JSON response. */
  private void writeMethodNotAllowed(Response response, Callback callback) {
    response.setStatus(405);
    response.getHeaders().put("Content-Type", "application/json");
    String error = "{\"error\":\"Method not allowed\"}";
    response.write(true, ByteBuffer.wrap(error.getBytes(StandardCharsets.UTF_8)), callback);
  }

  /** Writes an error response with the given HTTP status and {@code {"error": {...}}} body. */
  private void writeError(
      Response response, Callback callback, int httpStatus, String code, Throwable e) {
    response.setStatus(httpStatus);
    response.getHeaders().put("Content-Type", "application/json");
    Map<String, Object> envelope = new HashMap<>();
    envelope.put("error", errorBody(code, e));
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(envelope);
      response.write(true, ByteBuffer.wrap(bytes), callback);
    } catch (Exception writeError) {
      callback.failed(writeError);
    }
  }

  /** Builds a structured error body: {@code {status, message, details: {stack}}}. */
  private static Map<String, Object> errorBody(String code, Throwable e) {
    String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
    java.io.StringWriter sw = new java.io.StringWriter();
    e.printStackTrace(new java.io.PrintWriter(sw));
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
