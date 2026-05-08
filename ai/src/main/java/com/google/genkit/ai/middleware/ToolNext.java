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

import com.google.genkit.ai.Part;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;

/** Next function in the {@link GenerationMiddleware#wrapTool} hook chain. */
@FunctionalInterface
public interface ToolNext {

  /**
   * Calls the next handler in the tool chain.
   *
   * @param ctx the action context
   * @param params the tool parameters
   * @return the tool response part (includes part-level metadata)
   * @throws GenkitException if processing fails
   */
  Part apply(ActionContext ctx, ToolParams params) throws GenkitException;
}
