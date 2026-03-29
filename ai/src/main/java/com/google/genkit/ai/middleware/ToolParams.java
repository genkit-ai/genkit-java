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

import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolRequest;

/** Holds parameters for the {@link GenerationMiddleware#wrapTool} hook. */
public class ToolParams {

  private final ToolRequest request;
  private final Tool<?, ?> tool;

  /**
   * Creates ToolParams.
   *
   * @param request the tool request about to be executed
   * @param tool the resolved tool being called
   */
  public ToolParams(ToolRequest request, Tool<?, ?> tool) {
    this.request = request;
    this.tool = tool;
  }

  /** Returns the tool request about to be executed. */
  public ToolRequest getRequest() {
    return request;
  }

  /** Returns the resolved tool being called. */
  public Tool<?, ?> getTool() {
    return tool;
  }
}
