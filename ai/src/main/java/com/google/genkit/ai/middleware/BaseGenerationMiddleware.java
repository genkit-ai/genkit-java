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
 * BaseGenerationMiddleware provides default pass-through implementations for all three hooks.
 * Extend this class and override only the hooks you need.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public class TimingMiddleware extends BaseGenerationMiddleware {
 *   @Override
 *   public String name() { return "timing"; }
 *
 *   @Override
 *   public GenerationMiddleware newInstance() { return new TimingMiddleware(); }
 *
 *   @Override
 *   public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
 *       throws GenkitException {
 *     long start = System.currentTimeMillis();
 *     ModelResponse resp = next.apply(ctx, params);
 *     System.out.println("Model call took " + (System.currentTimeMillis() - start) + "ms");
 *     return resp;
 *   }
 * }
 * }</pre>
 */
public abstract class BaseGenerationMiddleware implements GenerationMiddleware {

  @Override
  public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException {
    return next.apply(ctx, params);
  }

  @Override
  public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
      throws GenkitException {
    return next.apply(ctx, params);
  }

  @Override
  public ToolResponse wrapTool(ActionContext ctx, ToolParams params, ToolNext next)
      throws GenkitException {
    return next.apply(ctx, params);
  }

  @Override
  public List<Tool<?, ?>> tools() {
    return Collections.emptyList();
  }
}
