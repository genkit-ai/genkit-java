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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionDesc;
import com.google.genkit.core.ActionRunResult;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.Registry;
import com.google.genkit.core.tracing.Tracer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReflectionServerV2 implements the Reflection API V2 using WebSockets and JSON-RPC 2.0.
 *
 * <p>In V2, the connection direction is reversed compared to V1: the runtime acts as a WebSocket
 * client, connecting outbound to the CLI's WebSocket server. Communication uses JSON-RPC 2.0 for
 * bidirectional messaging.
 *
 * <p>This is activated when the {@code GENKIT_REFLECTION_V2_SERVER} environment variable is set
 * (e.g., {@code ws://localhost:4100}).
 */
public class ReflectionServerV2 {

  private static final Logger logger = LoggerFactory.getLogger(ReflectionServerV2.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final String GENKIT_VERSION = "java/1.0.0";
  private static final int REFLECTION_API_SPEC_VERSION = 2;

  private final Registry registry;
  private final String serverUrl;
  private final String runtimeId;

  private WebSocket webSocket;
  private final HttpClient httpClient;
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private final AtomicInteger reconnectCount = new AtomicInteger(0);
  private final AtomicInteger requestIdCounter = new AtomicInteger(0);

  private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Thread> activeActions = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService actionExecutor = Executors.newCachedThreadPool();

  private static final long BASE_DELAY_MS = 500;
  private static final long MAX_DELAY_MS = 5000;

  /**
   * Creates a new ReflectionServerV2.
   *
   * @param registry the Genkit registry
   * @param serverUrl the WebSocket server URL (e.g., ws://localhost:4100)
   */
  public ReflectionServerV2(Registry registry, String serverUrl) {
    this.registry = registry;
    this.serverUrl = serverUrl;
    this.runtimeId = ProcessHandle.current().pid() + "";
    this.httpClient = HttpClient.newHttpClient();

    // Register local telemetry store for Dev UI trace access
    Tracer.registerSpanProcessor(new LocalTelemetryStore());
  }

  /** Gets the runtime ID. */
  public String getRuntimeId() {
    return runtimeId;
  }

  /**
   * Starts the V2 reflection client by connecting to the CLI WebSocket server.
   *
   * @throws Exception if the initial connection fails
   */
  public void start() throws Exception {
    stopped.set(false);
    reconnectCount.set(0);
    connect();
    logger.info("Reflection V2 client connecting to {}", serverUrl);
  }

  /** Stops the V2 reflection client. */
  public void stop() {
    stopped.set(true);
    scheduler.shutdownNow();
    actionExecutor.shutdownNow();

    // Reject all pending requests
    for (Map.Entry<String, CompletableFuture<JsonNode>> entry : pendingRequests.entrySet()) {
      entry.getValue().completeExceptionally(new Exception("Connection closed"));
    }
    pendingRequests.clear();

    if (webSocket != null) {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
      webSocket = null;
    }
    logger.info("Reflection V2 client stopped");
  }

  private void connect() {
    if (stopped.get()) return;

    try {
      URI uri = URI.create(serverUrl);
      httpClient
          .newWebSocketBuilder()
          .buildAsync(uri, new ReflectionWebSocketListener())
          .thenAccept(
              ws -> {
                this.webSocket = ws;
                reconnectCount.set(0);
                logger.debug("Connected to Reflection V2 server.");
                register();
              })
          .exceptionally(
              ex -> {
                logger.error("Failed to connect to Reflection V2 server: {}", ex.getMessage());
                scheduleReconnect();
                return null;
              });
    } catch (Exception e) {
      logger.error("Error creating WebSocket connection: {}", e.getMessage());
      scheduleReconnect();
    }
  }

  private void scheduleReconnect() {
    if (stopped.get()) return;

    int count = reconnectCount.getAndIncrement();
    long delay = Math.min(BASE_DELAY_MS * (1L << count), MAX_DELAY_MS);

    logger.debug("Scheduling reconnection in {}ms (attempt {})", delay, count + 1);

    scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
  }

  // =========================================================================
  // JSON-RPC Messaging
  // =========================================================================

  private void send(String message) {
    WebSocket ws = this.webSocket;
    if (ws != null) {
      ws.sendText(message, true);
    }
  }

  private void sendResponse(String id, Object result) {
    Map<String, Object> response = new HashMap<>();
    response.put("jsonrpc", "2.0");
    response.put("result", result);
    response.put("id", id);
    send(JsonUtils.toJson(response));
  }

  private void sendError(String id, int code, String message, Object data) {
    Map<String, Object> error = new HashMap<>();
    error.put("code", code);
    error.put("message", message);
    if (data != null) {
      error.put("data", data);
    }

    Map<String, Object> response = new HashMap<>();
    response.put("jsonrpc", "2.0");
    response.put("error", error);
    response.put("id", id);
    send(JsonUtils.toJson(response));
  }

  private void sendNotification(String method, Object params) {
    Map<String, Object> notification = new HashMap<>();
    notification.put("jsonrpc", "2.0");
    notification.put("method", method);
    notification.put("params", params);
    send(JsonUtils.toJson(notification));
  }

  private CompletableFuture<JsonNode> sendRequest(String method, Object params) {
    String id = String.valueOf(requestIdCounter.incrementAndGet());
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pendingRequests.put(id, future);

    Map<String, Object> request = new HashMap<>();
    request.put("jsonrpc", "2.0");
    request.put("id", id);
    request.put("method", method);
    request.put("params", params);
    send(JsonUtils.toJson(request));

    return future;
  }

  // =========================================================================
  // Registration
  // =========================================================================

  private void register() {
    Map<String, Object> params = new HashMap<>();
    params.put("id", runtimeId);
    params.put("pid", ProcessHandle.current().pid());
    params.put("name", runtimeId);
    params.put("genkitVersion", GENKIT_VERSION);
    params.put("reflectionApiSpecVersion", REFLECTION_API_SPEC_VERSION);
    params.put("envs", List.of("dev"));

    sendRequest("register", params)
        .thenAccept(
            response -> {
              if (response != null && response.has("telemetryServerUrl")) {
                String telemetryUrl = response.get("telemetryServerUrl").asText();
                if (telemetryUrl != null
                    && !telemetryUrl.isEmpty()
                    && System.getenv("GENKIT_TELEMETRY_SERVER") == null) {
                  Tracer.configureTelemetryServer(telemetryUrl);
                  logger.debug("Connected to telemetry server on {} via handshake", telemetryUrl);
                }
              }
            })
        .exceptionally(
            ex -> {
              logger.error("Failed to register with CLI: {}", ex.getMessage());
              return null;
            });
  }

  // =========================================================================
  // Message Handling
  // =========================================================================

  private void handleMessage(String text) {
    try {
      JsonNode message = objectMapper.readTree(text);

      if (message.has("method")) {
        // It's a request or notification
        handleRequest(message);
      } else if (message.has("id")) {
        // It's a response to one of our requests
        handleResponse(message);
      }
    } catch (Exception e) {
      logger.error("Failed to parse message: {}", e.getMessage());
    }
  }

  private void handleResponse(JsonNode response) {
    String id = response.has("id") ? response.get("id").asText() : null;
    if (id == null) return;

    CompletableFuture<JsonNode> future = pendingRequests.remove(id);
    if (future == null) {
      logger.error("Unknown response ID: {}", id);
      return;
    }

    if (response.has("error")) {
      future.completeExceptionally(new Exception(response.get("error").get("message").asText()));
    } else {
      future.complete(response.get("result"));
    }
  }

  private void handleRequest(JsonNode request) {
    String method = request.get("method").asText();
    String id =
        request.has("id") && !request.get("id").isNull() ? request.get("id").asText() : null;
    JsonNode params = request.has("params") ? request.get("params") : null;

    try {
      switch (method) {
        case "listActions":
          handleListActions(id);
          break;
        case "listValues":
          handleListValues(id, params);
          break;
        case "runAction":
          handleRunAction(id, params);
          break;
        case "configure":
          handleConfigure(params);
          break;
        case "cancelAction":
          handleCancelAction(id, params);
          break;
        default:
          if (id != null) {
            sendError(id, -32601, "Method not found: " + method, null);
          }
      }
    } catch (Exception e) {
      if (id != null) {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("stack", getStackTraceString(e));
        sendError(id, -32000, e.getMessage() != null ? e.getMessage() : "Unknown error", errorData);
      }
    }
  }

  // =========================================================================
  // Request Handlers
  // =========================================================================

  private void handleListActions(String requestId) {
    if (requestId == null) return;

    List<Action<?, ?, ?>> actions = registry.listActions();
    Map<String, Map<String, Object>> actionMap = new HashMap<>();

    for (Action<?, ?, ?> action : actions) {
      Map<String, Object> actionInfo = new HashMap<>();
      ActionDesc desc = action.getDesc();
      String key;
      if (desc != null) {
        key = desc.getKey();
        actionInfo.put("key", key);
        actionInfo.put("name", desc.getName());
        actionInfo.put("description", desc.getDescription() != null ? desc.getDescription() : "");
        if (desc.getInputSchema() != null) {
          actionInfo.put("inputSchema", desc.getInputSchema());
        }
        if (desc.getOutputSchema() != null) {
          actionInfo.put("outputSchema", desc.getOutputSchema());
        }
        actionInfo.put(
            "metadata", desc.getMetadata() != null ? desc.getMetadata() : new HashMap<>());
      } else {
        key = action.getType().keyFromName(action.getName());
        actionInfo.put("key", key);
        actionInfo.put("name", action.getName());
        actionInfo.put("description", "");
        actionInfo.put("metadata", new HashMap<>());
      }
      actionMap.put(key, actionInfo);
    }

    // V2 wraps actions in { actions: { ... } }
    Map<String, Object> result = new HashMap<>();
    result.put("actions", actionMap);
    sendResponse(requestId, result);
  }

  private void handleListValues(String requestId, JsonNode params) {
    if (requestId == null) return;

    // Currently no values to list for Java runtime
    Map<String, Object> result = new HashMap<>();
    result.put("values", new HashMap<>());
    sendResponse(requestId, result);
  }

  private void handleRunAction(String requestId, JsonNode params) {
    if (requestId == null) return;

    // Run action in a separate thread so we don't block the WebSocket message loop
    actionExecutor.submit(
        () -> {
          String traceId = null;
          try {
            String key = params.has("key") ? params.get("key").asText() : null;
            JsonNode input = params.has("input") ? params.get("input") : null;
            boolean stream = params.has("stream") && params.get("stream").asBoolean();

            if (key == null) {
              sendError(requestId, -32602, "Missing 'key' in params", null);
              return;
            }

            Action<?, ?, ?> action = registry.lookupAction(key);
            if (action == null) {
              sendError(requestId, 404, "Action not found: " + key, null);
              return;
            }

            // Track this action for cancellation
            activeActions.put(requestId, Thread.currentThread());

            ActionContext context = new ActionContext(registry);
            ActionRunResult<JsonNode> result = action.runJsonWithTelemetry(context, input, null);

            traceId = result.getTraceId();

            // Send runActionState notification with traceId
            if (traceId != null) {
              Map<String, Object> stateParams = new HashMap<>();
              stateParams.put("requestId", requestId);
              Map<String, Object> state = new HashMap<>();
              state.put("traceId", traceId);
              stateParams.put("state", state);
              sendNotification("runActionState", stateParams);
            }

            // Send final result
            Map<String, Object> responseResult = new HashMap<>();
            responseResult.put("result", result.getResult());
            if (traceId != null) {
              Map<String, Object> telemetry = new HashMap<>();
              telemetry.put("traceId", traceId);
              responseResult.put("telemetry", telemetry);
            }
            sendResponse(requestId, responseResult);

          } catch (Exception e) {
            boolean isInterrupt =
                e instanceof InterruptedException
                    || (e.getCause() != null && e.getCause() instanceof InterruptedException);

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("code", isInterrupt ? 1 : 13); // CANCELLED : INTERNAL
            errorData.put("message", isInterrupt ? "Action was cancelled" : e.getMessage());
            Map<String, Object> details = new HashMap<>();
            details.put("stack", getStackTraceString(e));
            if (traceId != null) {
              details.put("traceId", traceId);
            }
            errorData.put("details", details);

            sendError(
                requestId,
                -32000,
                isInterrupt ? "Action was cancelled" : e.getMessage(),
                errorData);
          } finally {
            activeActions.remove(requestId);
          }
        });
  }

  private void handleConfigure(JsonNode params) {
    if (params == null) return;

    if (params.has("telemetryServerUrl")) {
      String telemetryUrl = params.get("telemetryServerUrl").asText();
      if (telemetryUrl != null
          && !telemetryUrl.isEmpty()
          && System.getenv("GENKIT_TELEMETRY_SERVER") == null) {
        Tracer.configureTelemetryServer(telemetryUrl);
        logger.debug("Connected to telemetry server on {}", telemetryUrl);
      }
    }
  }

  private void handleCancelAction(String requestId, JsonNode params) {
    if (requestId == null) return;

    if (params == null || !params.has("traceId")) {
      sendError(requestId, -32602, "Missing 'traceId' in params", null);
      return;
    }

    String traceId = params.get("traceId").asText();

    // Look through active actions - find by traceId is not straightforward since
    // activeActions is keyed by requestId. We interrupt all matching threads.
    boolean found = false;
    for (Map.Entry<String, Thread> entry : activeActions.entrySet()) {
      // Interrupt the thread to cancel the action
      entry.getValue().interrupt();
      activeActions.remove(entry.getKey());
      found = true;
    }

    if (found) {
      sendResponse(requestId, Map.of("message", "Action cancelled"));
    } else {
      sendError(requestId, 404, "Action not found or already completed", null);
    }
  }

  // =========================================================================
  // Utilities
  // =========================================================================

  private static String getStackTraceString(Throwable e) {
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  // =========================================================================
  // WebSocket Listener
  // =========================================================================

  private class ReflectionWebSocketListener implements WebSocket.Listener {

    private final StringBuilder messageBuffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      logger.debug("WebSocket connection opened");
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      messageBuffer.append(data);
      if (last) {
        String fullMessage = messageBuffer.toString();
        messageBuffer.setLength(0);
        handleMessage(fullMessage);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      logger.debug("Reflection V2 WebSocket closed. Code: {}, Reason: {}", statusCode, reason);

      // Reject all pending requests
      for (Map.Entry<String, CompletableFuture<JsonNode>> entry : pendingRequests.entrySet()) {
        entry
            .getValue()
            .completeExceptionally(
                new Exception(
                    "Connection closed before response was received (id: " + entry.getKey() + ")"));
      }
      pendingRequests.clear();

      if (!stopped.get()) {
        scheduleReconnect();
      }
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      logger.error("Reflection V2 WebSocket error: {}", error.getMessage());
    }
  }
}
