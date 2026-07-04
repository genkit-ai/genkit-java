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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Falls back to alternate models when the primary model fails.
 *
 * <p>Wraps the {@code wrapGenerate} hook: if generation with the primary model (the one on the
 * incoming request) throws, each model in {@link Options#models} is tried in order by re-running
 * the generate iteration with the request's model swapped. The first success is returned; if all
 * fallbacks fail, the original error is rethrown.
 *
 * <p>Implemented at the generate level (rather than {@code wrapModel}) because switching model
 * requires re-resolving the model action, which the core generate loop already does from {@code
 * options.model}. As a consequence a failing iteration re-runs its tool calls under the fallback
 * model; put side-effect-free tools before this middleware if that matters.
 *
 * <p>Mirrors the JS {@code fallback} middleware in {@code @genkit-ai/middleware} and the Go {@code
 * Fallback} middleware.
 */
public class FallbackMiddleware extends BaseGenerationMiddleware {

  private static final Logger logger = LoggerFactory.getLogger(FallbackMiddleware.class);

  private final Options options;

  public FallbackMiddleware(Options options) {
    this.options = options != null ? options : new Options();
  }

  @Override
  public String name() {
    return "fallback";
  }

  @Override
  public GenerationMiddleware newInstance() {
    return this;
  }

  @Override
  public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException {
    try {
      return next.apply(ctx, params);
    } catch (GenkitException primary) {
      if (options.models == null || options.models.isEmpty()) {
        throw primary;
      }
      GenerateActionOptions req = params.getRequest();
      for (String model : options.models) {
        if (model == null || model.isEmpty()) continue;
        // Full copy of the request with the model swapped (withMessages preserves all other
        // fields).
        GenerateActionOptions fallbackReq = req.withMessages(req.getMessages());
        fallbackReq.setModel(model);
        try {
          logger.warn(
              "[fallback] primary model '{}' failed, trying fallback model '{}'",
              req.getModel(),
              model);
          return next.apply(ctx, params.withRequest(fallbackReq));
        } catch (GenkitException e) {
          logger.warn("[fallback] fallback model '{}' failed: {}", model, e.getMessage());
        }
      }
      throw primary;
    }
  }

  /** Configuration parameters for {@link FallbackMiddleware}. */
  public static class Options {

    @JsonProperty("models")
    @JsonPropertyDescription(
        "Ordered list of fallback model names to try when the primary model fails,"
            + " e.g. \"openai/gpt-4o-mini\".")
    public List<String> models = new ArrayList<>();

    public Options() {}
  }
}
