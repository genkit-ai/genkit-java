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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Registry;

/**
 * REST controller that exposes Genkit flows as HTTP endpoints.
 *
 * <p>
 * This controller dynamically handles requests to flow endpoints based on the
 * registered flows in the Genkit registry.
 */
@RestController
public class GenkitFlowController {

  private static final Logger logger = LoggerFactory.getLogger(GenkitFlowController.class);

  private final ObjectMapper objectMapper;

  /**
   * Creates a new GenkitFlowController.
   *
   * @param objectMapper
   *            the ObjectMapper for JSON serialization
   */
  public GenkitFlowController(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    logRegisteredEndpoints();
  }

  /**
   * Gets the registry from the SpringPlugin.
   *
   * @return the registry, or null if not initialized
   */
  private Registry getRegistry() {
    SpringPlugin plugin = SpringPlugin.getInstance();
    return plugin != null ? plugin.getRegistry() : null;
  }

  /**
   * Gets the plugin options from the SpringPlugin.
   *
   * @return the options, or null if not initialized
   */
  private SpringPluginOptions getOptions() {
    SpringPlugin plugin = SpringPlugin.getInstance();
    return plugin != null ? plugin.getOptions() : null;
  }

  /**
   * Logs all registered flow endpoints.
   */
  private void logRegisteredEndpoints() {
    Registry registry = getRegistry();
    SpringPluginOptions options = getOptions();
    if (registry == null || options == null) {
      return;
    }

    List<Action<?, ?, ?>> flows = registry.listActions(ActionType.FLOW);
    for (Action<?, ?, ?> action : flows) {
      String path = options.getBasePath() + "/" + action.getName();
      logger.info("Registered flow endpoint: {}", path);
    }
  }

  /**
   * Health check endpoint.
   *
   * @return health status
   */
  @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, String>> health() {
    Map<String, String> response = new HashMap<>();
    response.put("status", "ok");
    return ResponseEntity.ok(response);
  }

  /**
   * Lists all available flows.
   *
   * @return list of flow names
   */
  @GetMapping(value = "/api/flows", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> listFlows() {
    Registry registry = getRegistry();
    if (registry == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "Registry not initialized"));
    }

    List<Action<?, ?, ?>> flows = registry.listActions(ActionType.FLOW);
    List<String> flowNames = flows.stream().map(Action::getName).collect(Collectors.toList());

    Map<String, Object> response = new HashMap<>();
    response.put("flows", flowNames);
    return ResponseEntity.ok(response);
  }

  /**
   * Executes a flow by name.
   *
   * @param flowName
   *            the name of the flow to execute
   * @param input
   *            the input data for the flow
   * @return the flow execution result
   */
  @PostMapping(value = "/api/flows/{flowName}", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> executeFlow(@PathVariable String flowName,
      @RequestBody(required = false) Object input) {
    Registry registry = getRegistry();
    if (registry == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "Registry not initialized"));
    }

    try {
      // Find the flow action
      Action<?, ?, ?> action = findFlowByName(flowName);
      if (action == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Flow not found: " + flowName));
      }

      // Execute the flow
      @SuppressWarnings("unchecked")
      Action<Object, Object, Object> typedAction = (Action<Object, Object, Object>) action;
      ActionContext context = new ActionContext(registry);
      Object result = typedAction.run(context, input);

      return ResponseEntity.ok(result);
    } catch (Exception e) {
      logger.error("Error executing flow: {}", flowName, e);
      return createErrorResponse(e);
    }
  }

  /**
   * Finds a flow action by name.
   *
   * @param flowName
   *            the name of the flow
   * @return the flow action, or null if not found
   */
  private Action<?, ?, ?> findFlowByName(String flowName) {
    Registry registry = getRegistry();
    if (registry == null) {
      return null;
    }
    List<Action<?, ?, ?>> flows = registry.listActions(ActionType.FLOW);
    return flows.stream().filter(action -> action.getName().equals(flowName)).findFirst().orElse(null);
  }

  /**
   * Creates an error response with structured error information.
   *
   * @param e
   *            the exception
   * @return the error response
   */
  private ResponseEntity<Object> createErrorResponse(Exception e) {
    String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";

    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    String stacktrace = sw.toString();

    Map<String, Object> errorDetails = new HashMap<>();
    errorDetails.put("stack", stacktrace);

    Map<String, Object> errorStatus = new HashMap<>();
    errorStatus.put("code", 2); // INTERNAL error code
    errorStatus.put("message", errorMessage);
    errorStatus.put("details", errorDetails);

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorStatus);
  }
}
