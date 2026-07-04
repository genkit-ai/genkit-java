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

package com.google.genkit.plugins.middleware;

import com.google.genkit.ai.middleware.GenerationMiddlewareDesc;
import com.google.genkit.ai.middleware.GenerationMiddlewares;
import com.google.genkit.ai.middleware.MiddlewarePlugin;
import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import java.util.List;

/**
 * A plugin that ships a set of ready-to-use, parameterized generation middleware.
 *
 * <p>This is the Java analog of the JS {@code @genkit-ai/middleware} package and the Go {@code
 * plugins/middleware} package. Adding it to the {@code Genkit} builder registers each middleware
 * into the {@code "middleware"} registry bucket, so it appears in the Genkit Dev UI Middleware
 * panel (with a parameters form derived from its {@code configSchema}) and can be attached to any
 * {@code generate()} call by name.
 *
 * <p>Provided middleware:
 *
 * <ul>
 *   <li>{@code retry} — retry failed model calls with exponential backoff ({@link RetryMiddleware})
 *   <li>{@code fallback} — fall back to alternate models on failure ({@link FallbackMiddleware})
 *   <li>{@code simulateSystemPrompt} — rewrite the system message into a user/model exchange
 *       ({@link SimulateSystemPromptMiddleware})
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(OpenAIPlugin.create())
 *     .plugin(GenerationMiddlewarePlugin.create())
 *     .build();
 *
 * // Attach by name (config optional) from code or the Dev UI:
 * genkit.generate(GenerateOptions.builder()
 *     .model("openai/gpt-4o-mini")
 *     .prompt("Hello")
 *     .build());
 * }</pre>
 */
public class GenerationMiddlewarePlugin implements Plugin, MiddlewarePlugin {

  /** The plugin name. */
  public static final String PLUGIN_NAME = "genkit-middleware";

  /**
   * Creates the plugin.
   *
   * @return a new plugin instance
   */
  public static GenerationMiddlewarePlugin create() {
    return new GenerationMiddlewarePlugin();
  }

  @Override
  public String getName() {
    return PLUGIN_NAME;
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    // This plugin provides middleware (via MiddlewarePlugin), not actions.
    return List.of();
  }

  @Override
  public List<GenerationMiddlewareDesc> middlewares(Registry registry) {
    return List.of(
        GenerationMiddlewares.define(
            "retry",
            "Retries failed model calls with exponential backoff.",
            RetryMiddleware.Options.class,
            RetryMiddleware::new),
        GenerationMiddlewares.define(
            "fallback",
            "Falls back to alternate models when the primary model fails.",
            FallbackMiddleware.Options.class,
            FallbackMiddleware::new),
        GenerationMiddlewares.define(
            "simulateSystemPrompt",
            "Rewrites the system message into a user/model exchange for models without native"
                + " system-prompt support.",
            SimulateSystemPromptMiddleware.Options.class,
            SimulateSystemPromptMiddleware::new));
  }
}
