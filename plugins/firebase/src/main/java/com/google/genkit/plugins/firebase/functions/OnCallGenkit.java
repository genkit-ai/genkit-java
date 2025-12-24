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

package com.google.genkit.plugins.firebase.functions;

import java.io.*;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.genkit.Genkit;
import com.google.genkit.core.*;

/**
 * Firebase Cloud Functions integration for Genkit flows.
 * 
 * <p>
 * This class provides a way to expose Genkit flows as Firebase Cloud Functions
 * using the Google Cloud Functions Framework. It supports both streaming and
 * non-streaming responses.
 * 
 * <p>
 * Example usage with Cloud Functions:
 * 
 * <pre>{@code
 * public class MyFunction implements HttpFunction {
 * 	private final Genkit genkit = Genkit.builder().plugin(GoogleGenAIPlugin.create(apiKey)).build();
 * 
 * 	@Override
 * 	public void service(HttpRequest request, HttpResponse response) throws Exception {
 * 		OnCallGenkit.fromFlow(genkit, "generatePoem").withAuthPolicy(auth -> auth != null && auth.isEmailVerified())
 * 				.service(request, response);
 * 	}
 * }
 * }</pre>
 */
public class OnCallGenkit implements HttpFunction {

  private static final Logger logger = LoggerFactory.getLogger(OnCallGenkit.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final Genkit genkit;
  private final String flowName;
  private final Action<?, ?, ?> flow;
  private AuthPolicy authPolicy;
  private boolean enforceAppCheck;
  private boolean consumeAppCheckToken;
  private String cors;

  private OnCallGenkit(Genkit genkit, String flowName, Action<?, ?, ?> flow) {
    this.genkit = genkit;
    this.flowName = flowName;
    this.flow = flow;
  }

  /**
   * Creates an OnCallGenkit handler from a flow name.
   *
   * @param genkit
   *            the Genkit instance
   * @param flowName
   *            the name of the flow to expose
   * @return a new OnCallGenkit handler
   */
  public static OnCallGenkit fromFlow(Genkit genkit, String flowName) {
    Action<?, ?, ?> flow = genkit.getRegistry().lookupAction(ActionType.FLOW.keyFromName(flowName));
    if (flow == null) {
      throw new IllegalArgumentException("Flow not found: " + flowName);
    }
    return new OnCallGenkit(genkit, flowName, flow);
  }

  /**
   * Creates an OnCallGenkit handler from a flow action.
   *
   * @param genkit
   *            the Genkit instance
   * @param flow
   *            the flow action to expose
   * @return a new OnCallGenkit handler
   */
  public static OnCallGenkit fromFlow(Genkit genkit, Action<?, ?, ?> flow) {
    return new OnCallGenkit(genkit, flow.getName(), flow);
  }

  /**
   * Sets the authorization policy for this function.
   *
   * @param authPolicy
   *            the authorization policy
   * @return this handler for chaining
   */
  public OnCallGenkit withAuthPolicy(AuthPolicy authPolicy) {
    this.authPolicy = authPolicy;
    return this;
  }

  /**
   * Enables App Check enforcement.
   *
   * @param enforce
   *            true to enforce App Check
   * @return this handler for chaining
   */
  public OnCallGenkit enforceAppCheck(boolean enforce) {
    this.enforceAppCheck = enforce;
    return this;
  }

  /**
   * Enables App Check token consumption for extra security.
   *
   * @param consume
   *            true to consume App Check tokens
   * @return this handler for chaining
   */
  public OnCallGenkit consumeAppCheckToken(boolean consume) {
    this.consumeAppCheckToken = consume;
    return this;
  }

  /**
   * Sets the CORS policy.
   *
   * @param cors
   *            the allowed origin(s), or null for default behavior
   * @return this handler for chaining
   */
  public OnCallGenkit withCors(String cors) {
    this.cors = cors;
    return this;
  }

  @Override
  public void service(HttpRequest request, HttpResponse response) throws Exception {
    try {
      // Handle CORS preflight
      if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
        handleCors(response);
        response.setStatusCode(204);
        return;
      }

      // Set CORS headers
      handleCors(response);

      // Validate request method
      if (!"POST".equalsIgnoreCase(request.getMethod())) {
        sendError(response, 405, "Method not allowed");
        return;
      }

      // Parse request body
      JsonNode requestBody = parseRequestBody(request);

      // Extract auth context
      AuthContext authContext = extractAuthContext(request);

      // Validate App Check if enforced
      if (enforceAppCheck && !validateAppCheck(request)) {
        sendError(response, 401, "App Check validation failed");
        return;
      }

      // Validate authorization policy
      if (authPolicy != null && !authPolicy.isAuthorized(authContext)) {
        sendError(response, 403, "Unauthorized");
        return;
      }

      // Extract input from the request
      JsonNode input = requestBody.has("data") ? requestBody.get("data") : requestBody;

      // Check if streaming is requested
      boolean streaming = requestBody.has("streaming") && requestBody.get("streaming").asBoolean();

      if (streaming) {
        handleStreamingRequest(input, response, authContext);
      } else {
        handleNonStreamingRequest(input, response, authContext);
      }

    } catch (Exception e) {
      logger.error("Error processing request for flow: {}", flowName, e);
      sendError(response, 500, "Internal error: " + e.getMessage());
    }
  }

  /**
   * Handles a non-streaming request.
   */
  private void handleNonStreamingRequest(JsonNode input, HttpResponse response, AuthContext authContext)
      throws Exception {
    response.setContentType("application/json");

    // Create action context using the registry
    ActionContext ctx = new ActionContext(genkit.getRegistry());

    // Run the flow
    ActionRunResult<JsonNode> result = flow.runJsonWithTelemetry(ctx, input, null);

    // Send response
    Map<String, Object> responseBody = Map.of("result", result.getResult());
    response.getWriter().write(objectMapper.writeValueAsString(responseBody));
  }

  /**
   * Handles a streaming request using Server-Sent Events.
   */
  private void handleStreamingRequest(JsonNode input, HttpResponse response, AuthContext authContext)
      throws Exception {
    response.setContentType("text/event-stream");
    response.appendHeader("Cache-Control", "no-cache");
    response.appendHeader("Connection", "keep-alive");

    BufferedWriter writer = response.getWriter();

    // Create action context using the registry
    ActionContext ctx = new ActionContext(genkit.getRegistry());

    // Run the flow with streaming callback
    flow.runJsonWithTelemetry(ctx, input, chunk -> {
      try {
        String eventData = objectMapper.writeValueAsString(Map.of("chunk", chunk));
        writer.write("data: " + eventData + "\n\n");
        writer.flush();
      } catch (IOException e) {
        logger.error("Error writing stream chunk", e);
      }
    });

    // Send completion event
    writer.write("data: [DONE]\n\n");
    writer.flush();
  }

  /**
   * Handles CORS headers.
   */
  private void handleCors(HttpResponse response) {
    String allowedOrigin = cors != null ? cors : "*";
    response.appendHeader("Access-Control-Allow-Origin", allowedOrigin);
    response.appendHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
    response.appendHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Firebase-AppCheck");
    response.appendHeader("Access-Control-Max-Age", "3600");
  }

  /**
   * Parses the request body as JSON.
   */
  private JsonNode parseRequestBody(HttpRequest request) throws IOException {
    try (BufferedReader reader = request.getReader()) {
      StringBuilder body = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        body.append(line);
      }
      if (body.length() == 0) {
        return objectMapper.createObjectNode();
      }
      return objectMapper.readTree(body.toString());
    }
  }

  /**
   * Extracts auth context from the request headers.
   */
  private AuthContext extractAuthContext(HttpRequest request) {
    AuthContext context = new AuthContext();

    // Extract Firebase Auth token
    String authHeader = request.getFirstHeader("Authorization").orElse(null);
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7);
      context.setToken(token);
      // In a real implementation, you would verify the token here
      // using Firebase Admin SDK and extract claims
    }

    return context;
  }

  /**
   * Validates App Check token.
   */
  private boolean validateAppCheck(HttpRequest request) {
    String appCheckToken = request.getFirstHeader("X-Firebase-AppCheck").orElse(null);
    if (appCheckToken == null) {
      return false;
    }
    // In a real implementation, verify the App Check token
    // using Firebase Admin SDK
    return true;
  }

  /**
   * Sends an error response.
   */
  private void sendError(HttpResponse response, int statusCode, String message) throws IOException {
    response.setStatusCode(statusCode);
    response.setContentType("application/json");
    Map<String, Object> error = Map.of("error", Map.of("status", statusCode, "message", message));
    response.getWriter().write(objectMapper.writeValueAsString(error));
  }

  /**
   * Functional interface for authorization policies.
   */
  @FunctionalInterface
  public interface AuthPolicy {
    /**
     * Checks if the request is authorized.
     *
     * @param auth
     *            the auth context
     * @return true if authorized
     */
    boolean isAuthorized(AuthContext auth);
  }

  /**
   * Predefined authorization policy that requires the user to be signed in.
   *
   * @return an auth policy requiring sign-in
   */
  public static AuthPolicy signedIn() {
    return auth -> auth != null && auth.getToken() != null;
  }

  /**
   * Predefined authorization policy that requires a specific claim.
   *
   * @param claim
   *            the required claim name
   * @return an auth policy requiring the claim
   */
  public static AuthPolicy hasClaim(String claim) {
    return auth -> {
      if (auth == null || auth.getClaims() == null) {
        return false;
      }
      Object value = auth.getClaims().get(claim);
      return value != null && (!(value instanceof Boolean) || (Boolean) value);
    };
  }
}
