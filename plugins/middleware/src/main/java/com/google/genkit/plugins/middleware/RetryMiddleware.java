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
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries a failed model call with exponential backoff.
 *
 * <p>Wraps the {@code wrapModel} hook: if the underlying model call throws, it is retried up to
 * {@link Options#maxRetries} times, sleeping {@code initialDelayMs} before the first retry and
 * multiplying the delay by {@code backoffFactor} (capped at {@code maxDelayMs}) after each attempt.
 *
 * <p>Mirrors the JS {@code retry} middleware in {@code @genkit-ai/middleware} and the Go {@code
 * Retry} middleware.
 */
public class RetryMiddleware extends BaseGenerationMiddleware {

  private static final Logger logger = LoggerFactory.getLogger(RetryMiddleware.class);

  private final Options options;

  public RetryMiddleware(Options options) {
    this.options = options != null ? options : new Options();
  }

  @Override
  public String name() {
    return "retry";
  }

  @Override
  public GenerationMiddleware newInstance() {
    // Stateless: the backoff state is local to each wrapModel invocation.
    return this;
  }

  @Override
  public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
      throws GenkitException {
    int attempts = 0;
    long delay = Math.max(0, options.initialDelayMs);
    while (true) {
      try {
        return next.apply(ctx, params);
      } catch (GenkitException e) {
        if (attempts >= options.maxRetries) {
          throw e;
        }
        attempts++;
        logger.warn(
            "[retry] model call failed (retry {}/{}), retrying in {}ms: {}",
            attempts,
            options.maxRetries,
            delay,
            e.getMessage());
        if (delay > 0) {
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
          }
        }
        delay = Math.min((long) (delay * options.backoffFactor), options.maxDelayMs);
      }
    }
  }

  /** Configuration parameters for {@link RetryMiddleware}. */
  public static class Options {

    @JsonProperty("maxRetries")
    @JsonPropertyDescription("Maximum number of retries after the initial attempt.")
    public int maxRetries = 3;

    @JsonProperty("initialDelayMs")
    @JsonPropertyDescription("Delay before the first retry, in milliseconds.")
    public long initialDelayMs = 1000;

    @JsonProperty("maxDelayMs")
    @JsonPropertyDescription("Maximum delay between retries, in milliseconds.")
    public long maxDelayMs = 60000;

    @JsonProperty("backoffFactor")
    @JsonPropertyDescription("Multiplier applied to the delay after each retry.")
    public double backoffFactor = 2.0;

    public Options() {}
  }
}
