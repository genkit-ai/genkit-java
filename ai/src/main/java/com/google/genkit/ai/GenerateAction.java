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

package com.google.genkit.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddlewareDesc;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.ai.middleware.ToolParams;
import com.google.genkit.ai.telemetry.ModelTelemetryHelper;
import com.google.genkit.core.*;
import com.google.genkit.core.tracing.SpanMetadata;
import com.google.genkit.core.tracing.Tracer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GenerateAction is a utility action that provides a unified interface for generating content from
 * AI models. It's registered at /util/generate and is used by the Dev UI.
 */
public class GenerateAction
    implements Action<GenerateActionOptions, ModelResponse, ModelResponseChunk> {

  private static final Logger logger = LoggerFactory.getLogger(GenerateAction.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final Registry registry;

  public GenerateAction(Registry registry) {
    this.registry = registry;
  }

  /**
   * Defines and registers the generate utility action.
   *
   * @param registry the registry to register with
   * @return the generate action
   */
  public static GenerateAction define(Registry registry) {
    GenerateAction action = new GenerateAction(registry);
    registry.registerAction("/util/generate", action);
    logger.debug("Registered utility action: /util/generate");
    return action;
  }

  @Override
  public String getName() {
    return "generate";
  }

  @Override
  public ActionType getType() {
    return ActionType.UTIL;
  }

  @Override
  public ActionDesc getDesc() {
    return ActionDesc.builder()
        .name("generate")
        .type(ActionType.UTIL)
        .description("Utility action for generating content from AI models")
        .build();
  }

  @Override
  public ModelResponse run(ActionContext ctx, GenerateActionOptions options)
      throws GenkitException {
    return run(ctx, options, null);
  }

  @Override
  public ModelResponse run(
      ActionContext ctx, GenerateActionOptions options, Consumer<ModelResponseChunk> streamCallback)
      throws GenkitException {
    if (options == null) {
      throw new GenkitException("GenerateActionOptions cannot be null");
    }

    // Resolve middleware names from the registry. The Dev UI's Middleware panel sends
    // selected middleware as a list of names in `options.use`. Each name is looked up
    // in the "middleware" value bucket (registered via Genkit.Builder.middleware(...)).
    // Mirrors the JS SDK's resolveMiddleware() in js/ai/src/generate/action.ts.
    final List<GenerationMiddleware> middlewares = resolveMiddlewares(options.getUse());

    // Core: run the full tool loop (possibly streaming). model.run() inside is wrapped
    // by the wrapModel chain; tool execution is wrapped by the wrapTool chain.
    GenerateNext core =
        (gctx, gparams) ->
            runIterations(gctx, gparams.getRequest(), gparams.getOnChunk(), middlewares);

    // Outermost: wrapGenerate chain
    GenerateNext chain = chainGenerate(middlewares, core);

    int initialMsgIdx = options.getMessages() != null ? options.getMessages().size() : 0;
    return chain.apply(ctx, new GenerateParams(options, 0, initialMsgIdx, streamCallback));
  }

  /**
   * Executes the tool-call loop. Each iteration wraps the model call in the {@code wrapModel}
   * middleware chain and tool execution in the {@code wrapTool} chain.
   */
  private ModelResponse runIterations(
      ActionContext ctx,
      GenerateActionOptions options,
      Consumer<ModelResponseChunk> streamCallback,
      List<GenerationMiddleware> middlewares)
      throws GenkitException {

    String modelName = options.getModel();
    if (modelName == null || modelName.isEmpty()) {
      throw new GenkitException("Model name is required");
    }

    String modelKey = resolveModelKey(modelName);
    Action<?, ?, ?> action = registry.lookupAction(modelKey);
    if (action == null) {
      throw new GenkitException("Model not found: " + modelName + " (key: " + modelKey + ")");
    }
    if (!(action instanceof Model)) {
      throw new GenkitException("Action is not a model: " + modelKey);
    }
    final Model model = (Model) action;

    ModelRequest request = buildModelRequest(options);

    logger.debug("Generating with model: {}", modelKey);

    boolean returnToolRequests = Boolean.TRUE.equals(options.getReturnToolRequests());
    int maxTurns = options.getMaxTurns() != null ? options.getMaxTurns() : 5;
    int turn = 0;

    String flowName = ctx.getFlowName();

    while (turn < maxTurns) {
      SpanMetadata spanMetadata =
          SpanMetadata.builder()
              .name(modelName)
              .type(ActionType.MODEL.getValue())
              .subtype("model")
              .build();

      if (flowName != null) {
        spanMetadata.getAttributes().put("genkit:metadata:flow:name", flowName);
      }

      final ModelRequest currentRequest = request;
      final String spanPath = "/generate/" + modelName;

      // Run the model wrapped in a span and through the wrapModel middleware chain.
      ModelResponse response =
          Tracer.runInNewSpan(
              ctx,
              spanMetadata,
              request,
              (spanCtx, req) -> {
                ActionContext newCtx = ctx.withSpanContext(spanCtx);
                ModelNext modelCore =
                    (mctx, mparams) -> {
                      ModelRequest mreq = mparams.getRequest();
                      Consumer<ModelResponseChunk> sc = mparams.getStreamCallback();
                      if (sc != null && model.supportsStreaming()) {
                        return ModelTelemetryHelper.runWithTelemetryStreaming(
                            modelName, flowName, spanPath, mreq, r -> model.run(mctx, r, sc));
                      } else {
                        return ModelTelemetryHelper.runWithTelemetry(
                            modelName, flowName, spanPath, mreq, r -> model.run(mctx, r));
                      }
                    };
                ModelNext wrappedModel = chainModel(middlewares, modelCore);
                return wrappedModel.apply(newCtx, new ModelParams(currentRequest, streamCallback));
              });

      // Check if the model requested tool calls
      List<Part> toolRequestParts = extractToolRequestParts(response);

      if (toolRequestParts.isEmpty() || returnToolRequests) {
        return response;
      }

      if (options.getTools() == null || options.getTools().isEmpty()) {
        return response;
      }

      // Execute tools through the wrapTool chain
      List<Part> toolResponseParts =
          executeTools(ctx, toolRequestParts, options.getTools(), middlewares);

      Message assistantMessage = response.getMessage();
      List<Message> updatedMessages = new ArrayList<>(request.getMessages());
      updatedMessages.add(assistantMessage);

      Message toolResponseMessage = new Message();
      toolResponseMessage.setRole(Role.TOOL);
      toolResponseMessage.setContent(toolResponseParts);
      updatedMessages.add(toolResponseMessage);

      request =
          ModelRequest.builder()
              .messages(updatedMessages)
              .config(request.getConfig())
              .tools(request.getTools())
              .output(request.getOutput())
              .build();

      turn++;
    }

    throw new GenkitException("Max tool execution turns (" + maxTurns + ") exceeded");
  }

  /**
   * Resolves middleware references to fresh per-call middleware instances by looking them up in the
   * registry's {@code "middleware"} value bucket. Each reference is a {@code {name, config?}}
   * object (the {@code MiddlewareRef} shape the Dev UI's Middleware panel sends); a bare JSON
   * string is also accepted as a name-only reference. The optional {@code config} is passed
   * opaquely to the descriptor's {@link GenerationMiddlewareDesc#instantiate(JsonNode)} — no
   * server-side validation is performed; defaults live inside the middleware. Unknown names are
   * logged and skipped.
   */
  private List<GenerationMiddleware> resolveMiddlewares(List<JsonNode> refs)
      throws GenkitException {
    if (refs == null || refs.isEmpty()) {
      return List.of();
    }
    List<GenerationMiddleware> resolved = new ArrayList<>(refs.size());
    for (JsonNode ref : refs) {
      if (ref == null || ref.isNull()) continue;
      String name;
      JsonNode config = null;
      if (ref.isTextual()) {
        name = ref.asText();
      } else if (ref.isObject() && ref.hasNonNull("name")) {
        name = ref.get("name").asText();
        config = ref.get("config"); // may be absent (null) or JSON null
      } else {
        logger.warn("Unrecognized middleware reference shape: {}", ref);
        continue;
      }
      if (name == null || name.isEmpty()) continue;
      Object value = registry.lookupValue("middleware", name);
      if (value instanceof GenerationMiddlewareDesc) {
        // Bind config -> a fresh hooks instance, so config and per-call state are per-invocation.
        resolved.add(((GenerationMiddlewareDesc) value).instantiate(config));
      } else if (value instanceof GenerationMiddleware) {
        // Legacy: a bare middleware registered without a descriptor. Fresh instance per call.
        resolved.add(((GenerationMiddleware) value).newInstance());
      } else {
        logger.warn(
            "Middleware '{}' was requested but is not registered. "
                + "Register it via a MiddlewarePlugin or Genkit.Builder.middleware(...).",
            name);
      }
    }
    return resolved;
  }

  /** Chains wrapGenerate hooks. First middleware is outermost. */
  private static GenerateNext chainGenerate(
      List<GenerationMiddleware> middlewares, GenerateNext core) {
    if (middlewares.isEmpty()) return core;
    GenerateNext current = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      final GenerationMiddleware mw = middlewares.get(i);
      final GenerateNext next = current;
      current = (ctx, params) -> mw.wrapGenerate(ctx, params, next);
    }
    return current;
  }

  /** Chains wrapModel hooks. First middleware is outermost. */
  private static ModelNext chainModel(List<GenerationMiddleware> middlewares, ModelNext core) {
    if (middlewares.isEmpty()) return core;
    ModelNext current = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      final GenerationMiddleware mw = middlewares.get(i);
      final ModelNext next = current;
      current = (ctx, params) -> mw.wrapModel(ctx, params, next);
    }
    return current;
  }

  /** Chains wrapTool hooks. First middleware is outermost. */
  private static ToolNext chainTool(List<GenerationMiddleware> middlewares, ToolNext core) {
    if (middlewares.isEmpty()) return core;
    ToolNext current = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      final GenerationMiddleware mw = middlewares.get(i);
      final ToolNext next = current;
      current = (ctx, params) -> mw.wrapTool(ctx, params, next);
    }
    return current;
  }

  /** Extracts tool request parts from a model response. */
  private List<Part> extractToolRequestParts(ModelResponse response) {
    List<Part> toolRequestParts = new ArrayList<>();

    if (response.getMessage() != null && response.getMessage().getContent() != null) {
      for (Part part : response.getMessage().getContent()) {
        if (part.getToolRequest() != null) {
          toolRequestParts.add(part);
        }
      }
    }

    return toolRequestParts;
  }

  /** Executes tools through the wrapTool middleware chain and returns the response parts. */
  private List<Part> executeTools(
      ActionContext ctx,
      List<Part> toolRequestParts,
      List<String> toolNames,
      List<GenerationMiddleware> middlewares) {
    List<Part> responseParts = new ArrayList<>();

    // Core tool invocation — runs after all wrapTool middleware
    ToolNext toolCore =
        (tctx, tparams) -> {
          Tool<?, ?> tool = tparams.getTool();
          ToolRequest toolReq = tparams.getRequest();
          Object toolInput = toolReq.getInput();

          // Convert input if necessary. Use JsonUtils.convert (the centrally configured mapper)
          // rather than a local ObjectMapper, so custom (de)serializers, date formats, and naming
          // strategies registered globally are honored — consistent with Genkit.java.
          if (toolInput instanceof Map
              && tool.getInputClass() != null
              && !Map.class.isAssignableFrom(tool.getInputClass())) {
            toolInput = JsonUtils.convert(toolInput, tool.getInputClass());
          }

          @SuppressWarnings("unchecked")
          Tool<Object, Object> typedTool = (Tool<Object, Object>) tool;
          Object result = typedTool.run(tctx, toolInput);

          Part responsePart = new Part();
          responsePart.setToolResponse(
              new ToolResponse(toolReq.getRef(), toolReq.getName(), result));
          return responsePart;
        };
    ToolNext wrappedTool = chainTool(middlewares, toolCore);

    for (Part toolRequestPart : toolRequestParts) {
      ToolRequest toolRequest = toolRequestPart.getToolRequest();
      String toolName = toolRequest.getName();

      Tool<?, ?> tool = findTool(toolName, toolNames);
      if (tool == null) {
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(), toolName, Map.of("error", "Tool not found: " + toolName));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
        logger.warn("Tool not found: {}", toolName);
        continue;
      }

      try {
        Part responsePart = wrappedTool.apply(ctx, new ToolParams(toolRequestPart, tool));
        responseParts.add(responsePart);
        logger.debug("Executed tool '{}' successfully", toolName);
      } catch (Exception e) {
        logger.error("Tool execution failed for '{}': {}", toolName, e.getMessage());
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(),
                toolName,
                Map.of("error", "Tool execution failed: " + e.getMessage()));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
      }
    }

    return responseParts;
  }

  /** Finds a tool by name from the list of tool names or registry. */
  private Tool<?, ?> findTool(String toolName, List<String> toolNames) {
    // First try to find in registry by name
    String toolKey = toolName.startsWith("/tool/") ? toolName : "/tool/" + toolName;
    Action<?, ?, ?> action = registry.lookupAction(toolKey);
    if (action instanceof Tool) {
      return (Tool<?, ?>) action;
    }

    // Also try without prefix if the toolNames list includes it
    if (toolNames != null) {
      for (String name : toolNames) {
        String key = name.startsWith("/tool/") ? name : "/tool/" + name;
        if (key.equals(toolKey) || name.equals(toolName)) {
          action = registry.lookupAction(key);
          if (action instanceof Tool) {
            return (Tool<?, ?>) action;
          }
        }
      }
    }

    return null;
  }

  @Override
  public JsonNode runJson(ActionContext ctx, JsonNode input, Consumer<JsonNode> streamCallback)
      throws GenkitException {
    try {
      GenerateActionOptions options = objectMapper.treeToValue(input, GenerateActionOptions.class);
      Consumer<ModelResponseChunk> chunkCallback = null;
      if (streamCallback != null) {
        chunkCallback =
            chunk -> {
              try {
                streamCallback.accept(objectMapper.valueToTree(chunk));
              } catch (Exception e) {
                logger.error("Error streaming chunk", e);
              }
            };
      }
      ModelResponse response = run(ctx, options, chunkCallback);
      return objectMapper.valueToTree(response);
    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("Failed to process generate action", e);
    }
  }

  @Override
  public ActionRunResult<JsonNode> runJsonWithTelemetry(
      ActionContext ctx, JsonNode input, Consumer<JsonNode> streamCallback) throws GenkitException {
    // Capture trace info from within the span
    final String[] capturedTraceInfo = new String[2]; // [traceId, spanId]

    SpanMetadata spanMetadata = SpanMetadata.builder().name("generate").type("util").build();

    try {
      JsonNode result =
          Tracer.runInNewSpan(
              ctx,
              spanMetadata,
              input,
              (spanCtx, in) -> {
                // Capture the span context
                capturedTraceInfo[0] = spanCtx.getTraceId();
                capturedTraceInfo[1] = spanCtx.getSpanId();

                return runJson(ctx.withSpanContext(spanCtx), in, streamCallback);
              });

      return new ActionRunResult<>(result, capturedTraceInfo[0], capturedTraceInfo[1]);
    } catch (Exception e) {
      if (e instanceof GenkitException) {
        throw (GenkitException) e;
      }
      throw new GenkitException("Generate action failed: " + e.getMessage(), e);
    }
  }

  @Override
  public Map<String, Object> getInputSchema() {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    Map<String, Object> props = new HashMap<>();
    props.put("model", Map.of("type", "string"));
    props.put("messages", Map.of("type", "array"));
    props.put("config", Map.of("type", "object"));
    props.put("tools", Map.of("type", "array"));
    schema.put("properties", props);
    schema.put("required", List.of("messages"));
    return schema;
  }

  @Override
  public Map<String, Object> getOutputSchema() {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    return schema;
  }

  @Override
  public Map<String, Object> getMetadata() {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("type", "util");
    return metadata;
  }

  @Override
  public void register(Registry registry) {
    registry.registerAction("/util/generate", this);
  }

  /**
   * Resolves a model name to a registry key. Handles formats like "openai/gpt-4o" ->
   * "/model/openai/gpt-4o"
   */
  private String resolveModelKey(String modelName) {
    if (modelName.startsWith("/model/")) {
      return modelName;
    }
    return "/model/" + modelName;
  }

  /** Builds a ModelRequest from GenerateActionOptions. */
  private ModelRequest buildModelRequest(GenerateActionOptions options) {
    ModelRequest.Builder builder = ModelRequest.builder();

    if (options.getMessages() != null) {
      builder.messages(options.getMessages());
    }

    if (options.getConfig() != null) {
      // Convert GenerationConfig to Map<String, Object>
      Map<String, Object> configMap = objectMapper.convertValue(options.getConfig(), Map.class);
      builder.config(configMap);
    }

    if (options.getTools() != null && !options.getTools().isEmpty()) {
      // Resolve tools from registry
      List<ToolDefinition> toolDefs =
          options.getTools().stream()
              .map(this::resolveToolDefinition)
              .filter(t -> t != null)
              .toList();
      builder.tools(toolDefs);
    }

    if (options.getOutput() != null) {
      builder.output(options.getOutput());
    }

    return builder.build();
  }

  /** Resolves a tool name to its definition from the registry. */
  private ToolDefinition resolveToolDefinition(String toolName) {
    String toolKey = toolName.startsWith("/tool/") ? toolName : "/tool/" + toolName;
    Action<?, ?, ?> action = registry.lookupAction(toolKey);
    if (action == null) {
      logger.warn("Tool not found: {}", toolName);
      return null;
    }

    // Get tool definition from the action's desc
    ActionDesc desc = action.getDesc();
    return new ToolDefinition(desc.getName(), desc.getDescription(), desc.getInputSchema(), null);
  }
}
