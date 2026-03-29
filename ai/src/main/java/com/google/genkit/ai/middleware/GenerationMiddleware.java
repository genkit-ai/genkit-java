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

package com.google.genkit.ai.middleware;

import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.util.Collections;
import java.util.List;

/**
 * GenerationMiddleware provides hooks for different stages of the generation pipeline.
 *
 * <p>This is the V2 middleware interface that replaces the generic {@code Middleware<I, O>}. It
 * provides three distinct hooks:
 *
 * <ul>
 *   <li>{@link #wrapGenerate} - wraps each iteration of the tool loop
 *   <li>{@link #wrapModel} - wraps each model API call
 *   <li>{@link #wrapTool} - wraps each tool execution
 * </ul>
 *
 * <p>Each {@code generate()} call creates a fresh instance via {@link #newInstance()}, enabling
 * per-invocation state (e.g., counters, timers) without shared mutable state across requests.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public class LoggingMiddleware extends BaseGenerationMiddleware {
 *   private int modelCalls = 0;
 *
 *   @Override
 *   public String name() { return "logging"; }
 *
 *   @Override
 *   public GenerationMiddleware newInstance() { return new LoggingMiddleware(); }
 *
 *   @Override
 *   public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
 *       throws GenkitException {
 *     modelCalls++;
 *     System.out.println("Model call #" + modelCalls);
 *     ModelResponse resp = next.apply(ctx, params);
 *     System.out.println("Model responded with " + resp.getText());
 *     return resp;
 *   }
 * }
 * }</pre>
 */
public interface GenerationMiddleware {

  /** Returns the middleware's unique identifier. */
  String name();

  /**
   * Returns a fresh instance for each {@code generate()} call, enabling per-invocation state.
   *
   * <p>Stable state (e.g., API keys, configuration) should be preserved. Per-request state (e.g.,
   * counters) should be reset.
   */
  GenerationMiddleware newInstance();

  /**
   * Wraps each iteration of the generate tool loop.
   *
   * @param ctx the action context
   * @param params the generate parameters including the current request and iteration
   * @param next the next function in the chain
   * @return the model response
   * @throws GenkitException if processing fails
   */
  ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException;

  /**
   * Wraps each model API call.
   *
   * @param ctx the action context
   * @param params the model parameters including the request
   * @param next the next function in the chain
   * @return the model response
   * @throws GenkitException if processing fails
   */
  ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
      throws GenkitException;

  /**
   * Wraps each tool execution. May be called concurrently when multiple tools execute in parallel.
   * Implementations must be safe for concurrent use.
   *
   * @param ctx the action context
   * @param params the tool parameters including the request and resolved tool
   * @param next the next function in the chain
   * @return the tool response
   * @throws GenkitException if processing fails
   */
  ToolResponse wrapTool(ActionContext ctx, ToolParams params, ToolNext next) throws GenkitException;

  /**
   * Returns additional tools to make available during generation. These tools are dynamically added
   * when the middleware is used.
   *
   * @return the list of additional tools, or empty list if none
   */
  default List<Tool<?, ?>> tools() {
    return Collections.emptyList();
  }
}
