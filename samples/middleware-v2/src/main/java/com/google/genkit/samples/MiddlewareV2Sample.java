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

package com.google.genkit.samples;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.GenerationConfig;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddlewareDesc;
import com.google.genkit.ai.middleware.GenerationMiddlewares;
import com.google.genkit.ai.middleware.MiddlewarePlugin;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.ai.middleware.ToolParams;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.Flow;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import com.google.genkit.plugins.middleware.GenerationMiddlewarePlugin;
import com.google.genkit.plugins.middleware.RetryMiddleware;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sample application demonstrating the V2 GenerationMiddleware system.
 *
 * <p>V2 middleware hooks into three distinct stages of the generation pipeline:
 *
 * <ul>
 *   <li><b>WrapGenerate</b> — wraps each iteration of the tool loop
 *   <li><b>WrapModel</b> — wraps each model API call
 *   <li><b>WrapTool</b> — wraps each tool execution
 * </ul>
 *
 * <p>Middleware is attached per {@code generate()} call via {@code GenerateOptions.builder().use()}
 * rather than per flow.
 *
 * <p>Each {@code generate()} call creates a fresh middleware instance via {@code newInstance()},
 * enabling per-invocation state (counters, timers) without shared mutable state across requests.
 *
 * <p>To run:
 *
 * <ol>
 *   <li>Set the OPENAI_API_KEY environment variable
 *   <li>Run: mvn exec:java
 * </ol>
 */
public class MiddlewareV2Sample {

  private static final Logger logger = LoggerFactory.getLogger(MiddlewareV2Sample.class);

  // =========================================================================
  // Example 1: Model logging middleware (WrapModel hook)
  // =========================================================================

  /**
   * Logs every model API call with a call counter. The counter resets per generate() invocation
   * because {@code newInstance()} returns a fresh object.
   */
  static class ModelLoggingMiddleware extends BaseGenerationMiddleware {

    private final AtomicInteger modelCalls = new AtomicInteger(0);

    @Override
    public String name() {
      return "model-logging";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return new ModelLoggingMiddleware();
    }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
        throws GenkitException {
      int callNum = modelCalls.incrementAndGet();
      logger.info("[model-logging] Model call #{}", callNum);
      ModelResponse resp = next.apply(ctx, params);
      logger.info(
          "[model-logging] Model call #{} returned ({} chars)",
          callNum,
          resp.getText() != null ? resp.getText().length() : 0);
      return resp;
    }
  }

  // =========================================================================
  // Example 2: Generate timing middleware (WrapGenerate hook)
  // =========================================================================

  /**
   * Measures the wall-clock time of each generate loop iteration including model call + tool
   * execution within that iteration.
   */
  static class GenerateTimingMiddleware extends BaseGenerationMiddleware {

    @Override
    public String name() {
      return "generate-timing";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return new GenerateTimingMiddleware();
    }

    @Override
    public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
        throws GenkitException {
      long start = System.currentTimeMillis();
      logger.info("[generate-timing] Starting iteration {}", params.getIteration());
      ModelResponse resp = next.apply(ctx, params);
      logger.info(
          "[generate-timing] Iteration {} completed in {}ms",
          params.getIteration(),
          System.currentTimeMillis() - start);
      return resp;
    }
  }

  // =========================================================================
  // Example 3: Tool monitor middleware (WrapTool hook)
  // =========================================================================

  /** Logs tool execution name and duration. Stateless, so newInstance() returns {@code this}. */
  static class ToolMonitorMiddleware extends BaseGenerationMiddleware {

    @Override
    public String name() {
      return "tool-monitor";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return this; // stateless — safe to reuse
    }

    @Override
    public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next)
        throws GenkitException {
      String toolName = params.getRequest().getName();
      logger.info("[tool-monitor] Executing tool: {}", toolName);
      long start = System.currentTimeMillis();
      Part resp = next.apply(ctx, params);
      logger.info(
          "[tool-monitor] Tool {} completed in {}ms", toolName, System.currentTimeMillis() - start);
      return resp;
    }
  }

  // =========================================================================
  // Example 4: Combined multi-hook middleware
  // =========================================================================

  /**
   * A single middleware that implements all three hooks. Demonstrates that one middleware can
   * observe every stage of the pipeline.
   */
  static class FullObservabilityMiddleware extends BaseGenerationMiddleware {

    private final AtomicInteger iterations = new AtomicInteger(0);
    private final AtomicInteger modelCalls = new AtomicInteger(0);
    private final AtomicInteger toolCalls = new AtomicInteger(0);

    @Override
    public String name() {
      return "full-observability";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return new FullObservabilityMiddleware();
    }

    @Override
    public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
        throws GenkitException {
      int iter = iterations.incrementAndGet();
      logger.info("[observability] === Generate iteration {} ===", iter);
      ModelResponse resp = next.apply(ctx, params);
      logger.info(
          "[observability] === Iteration {} done (model calls: {}, tool calls: {}) ===",
          iter,
          modelCalls.get(),
          toolCalls.get());
      return resp;
    }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
        throws GenkitException {
      int call = modelCalls.incrementAndGet();
      logger.info("[observability]   Model call #{}", call);
      return next.apply(ctx, params);
    }

    @Override
    public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next)
        throws GenkitException {
      int call = toolCalls.incrementAndGet();
      logger.info("[observability]   Tool call #{}: {}", call, params.getRequest().getName());
      return next.apply(ctx, params);
    }
  }

  // =========================================================================
  // Example 5: Custom PARAMETERIZED middleware shared via a plugin
  // =========================================================================

  /**
   * A parameterized middleware: logs each model call tagged with a configurable {@code tag}, and
   * optionally the response length. Because it is registered through a {@link MiddlewarePlugin}
   * with a config schema (see {@link SampleMiddlewarePlugin}), the Dev UI renders its parameters
   * ({@code tag}, {@code logResponseLength}) as a form.
   */
  static class TaggedLoggingMiddleware extends BaseGenerationMiddleware {

    private final Options options;

    TaggedLoggingMiddleware(Options options) {
      this.options = options != null ? options : new Options();
    }

    @Override
    public String name() {
      return "tagged-logging";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return this;
    }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
        throws GenkitException {
      logger.info("[{}] model call", options.tag);
      ModelResponse resp = next.apply(ctx, params);
      if (options.logResponseLength) {
        logger.info(
            "[{}] response length: {}",
            options.tag,
            resp.getText() != null ? resp.getText().length() : 0);
      }
      return resp;
    }

    /** Configuration parameters — rendered as a form in the Dev UI Middleware panel. */
    public static class Options {
      @JsonProperty("tag")
      @JsonPropertyDescription("Label prefixed to each log line.")
      public String tag = "tagged";

      @JsonProperty("logResponseLength")
      @JsonPropertyDescription("Whether to log the model response length.")
      public boolean logResponseLength = false;

      public Options() {}
    }
  }

  /**
   * The sample's own middleware plugin. Demonstrates how an application (or a third-party library)
   * shares middleware the JS/Go way: implement {@link MiddlewarePlugin} and return descriptors
   * built with {@link GenerationMiddlewares#define}. Once added to the builder, {@code
   * tagged-logging} shows up in the Dev UI Middleware panel with a parameters form and can be
   * attached by name.
   */
  static class SampleMiddlewarePlugin implements Plugin, MiddlewarePlugin {
    @Override
    public String getName() {
      return "sample-middleware";
    }

    @Override
    public List<Action<?, ?, ?>> init() {
      return List.of();
    }

    @Override
    public List<GenerationMiddlewareDesc> middlewares(Registry registry) {
      return List.of(
          GenerationMiddlewares.define(
              "tagged-logging",
              "Logs each model call with a configurable tag.",
              TaggedLoggingMiddleware.Options.class,
              TaggedLoggingMiddleware::new));
    }
  }

  // =========================================================================
  // Main
  // =========================================================================

  public static void main(String[] args) throws Exception {
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Instantiate middleware (templates — newInstance() is called per generate())
    GenerationMiddleware modelLogging = new ModelLoggingMiddleware();
    GenerationMiddleware generateTiming = new GenerateTimingMiddleware();
    GenerationMiddleware toolMonitor = new ToolMonitorMiddleware();
    GenerationMiddleware fullObservability = new FullObservabilityMiddleware();

    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
            .plugin(OpenAIPlugin.create())
            .plugin(jetty)
            // Shared, parameterized middleware imported from the built-in plugin (retry, fallback,
            // simulateSystemPrompt). These appear in the Dev UI Middleware panel WITH a parameters
            // form and can be attached to any generate() call by name.
            .plugin(GenerationMiddlewarePlugin.create())
            // The sample's OWN middleware, shared as a plugin (parameterized "tagged-logging").
            .plugin(new SampleMiddlewarePlugin())
            // Ad-hoc live middleware registered directly (no parameters).
            .middleware(modelLogging, generateTiming, toolMonitor, fullObservability)
            .build();

    // Define a simple tool so the WrapTool hook gets exercised
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, Map<String, Object>> weatherTool =
        genkit.defineTool(
            "getWeather",
            "Gets the current weather for a given city",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("city", Map.of("type", "string", "description", "The city name")),
                "required",
                new String[] {"city"}),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, input) -> {
              String city = (String) input.get("city");
              Map<String, Object> weather = new HashMap<>();
              weather.put("city", city);
              weather.put("temperature", "22°C");
              weather.put("conditions", "Sunny");
              return weather;
            });

    // =======================================================
    // Flow 1: Simple chat with model logging + generate timing
    // =======================================================

    Flow<String, String, Void> chatFlow =
        genkit.defineFlow(
            "v2-chat",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("openai/gpt-4o-mini")
                          .system("You are a helpful assistant. Be concise.")
                          .prompt(userMessage)
                          .use(modelLogging, generateTiming)
                          .config(
                              GenerationConfig.builder()
                                  .temperature(0.7)
                                  .maxOutputTokens(200)
                                  .build())
                          .build());
              return response.getText();
            });

    // =======================================================
    // Flow 2: Chat with all three hooks via full observability
    // =======================================================

    Flow<String, String, Void> observableFlow =
        genkit.defineFlow(
            "v2-observable",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("openai/gpt-4o-mini")
                          .system(
                              "You are a helpful assistant. Use the getWeather tool when asked about weather.")
                          .prompt(userMessage)
                          .tools(List.of(weatherTool))
                          .use(fullObservability)
                          .config(
                              GenerationConfig.builder()
                                  .temperature(0.7)
                                  .maxOutputTokens(300)
                                  .build())
                          .build());
              return response.getText();
            });

    // =======================================================
    // Flow 3: Stacking multiple middleware together
    // =======================================================

    Flow<String, String, Void> stackedFlow =
        genkit.defineFlow(
            "v2-stacked",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("openai/gpt-4o-mini")
                          .prompt(userMessage)
                          .use(modelLogging, generateTiming, toolMonitor)
                          .config(
                              GenerationConfig.builder()
                                  .temperature(0.7)
                                  .maxOutputTokens(200)
                                  .build())
                          .build());
              return response.getText();
            });

    // =======================================================
    // Flow 4: No middleware (baseline for comparison)
    // =======================================================

    Flow<String, String, Void> baselineFlow =
        genkit.defineFlow(
            "v2-baseline",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("openai/gpt-4o-mini")
                          .prompt(userMessage)
                          .config(
                              GenerationConfig.builder()
                                  .temperature(0.7)
                                  .maxOutputTokens(200)
                                  .build())
                          .build());
              return response.getText();
            });

    // =======================================================
    // Flow 5: Uses the imported plugin middleware programmatically
    // =======================================================

    // The plugin ships `retry` for Dev-UI use; its RetryMiddleware class can also be used directly.
    RetryMiddleware.Options retryOpts = new RetryMiddleware.Options();
    retryOpts.maxRetries = 2;
    retryOpts.initialDelayMs = 500;

    Flow<String, String, Void> resilientFlow =
        genkit.defineFlow(
            "v2-resilient",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("openai/gpt-4o-mini")
                          .prompt(userMessage)
                          // Imported from the middleware plugin, configured in code.
                          .use(new RetryMiddleware(retryOpts), modelLogging)
                          .config(
                              GenerationConfig.builder()
                                  .temperature(0.7)
                                  .maxOutputTokens(200)
                                  .build())
                          .build());
              return response.getText();
            });

    logger.info("\n========================================");
    logger.info("Genkit Middleware V2 Sample Started!");
    logger.info("========================================\n");

    logger.info("Available flows:");
    logger.info("  - v2-chat:       Model logging + generate timing middleware");
    logger.info("  - v2-observable: Full observability (all 3 hooks in one middleware)");
    logger.info("  - v2-stacked:    Three separate middleware stacked together");
    logger.info("  - v2-baseline:   No middleware (baseline comparison)");
    logger.info(
        "  - v2-resilient:  retry middleware imported from the plugin (configured in code)\n");

    logger.info(
        "Dev UI Middleware panel now lists (open http://localhost:4000 via `genkit start`):");
    logger.info("  Parameterized (from plugins, with a config form):");
    logger.info("    - retry, fallback, simulateSystemPrompt  (genkit-middleware plugin)");
    logger.info(
        "    - tagged-logging                         (this sample's SampleMiddlewarePlugin)");
    logger.info("  Parameterless (registered via .middleware(...)):");
    logger.info("    - model-logging, generate-timing, tool-monitor, full-observability\n");

    logger.info("Server running on http://localhost:8080");
    logger.info("Reflection server running on http://localhost:3100");
    logger.info("\nExample requests:");
    logger.info(
        "  curl -X POST http://localhost:8080/v2-chat -H 'Content-Type: application/json' -d '\"What is middleware?\"'");
    logger.info(
        "  curl -X POST http://localhost:8080/v2-observable -H 'Content-Type: application/json' -d '\"Explain Java records\"'");
    logger.info(
        "  curl -X POST http://localhost:8080/v2-stacked -H 'Content-Type: application/json' -d '\"Hello world\"'");
    logger.info(
        "  curl -X POST http://localhost:8080/v2-baseline -H 'Content-Type: application/json' -d '\"Hello world\"'");

    jetty.start();
  }
}
