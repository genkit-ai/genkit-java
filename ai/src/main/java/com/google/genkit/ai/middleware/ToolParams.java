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
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolRequest;

/** Holds parameters for the {@link GenerationMiddleware#wrapTool} hook. */
public class ToolParams {

  private final Part requestPart;
  private final Tool<?, ?> tool;
  private final boolean released;

  /**
   * Creates ToolParams.
   *
   * @param requestPart the tool request part (includes metadata) about to be executed
   * @param tool the resolved tool being called
   */
  public ToolParams(Part requestPart, Tool<?, ?> tool) {
    this(requestPart, tool, false);
  }

  /**
   * Creates ToolParams.
   *
   * @param requestPart the tool request part (includes metadata) about to be executed
   * @param tool the resolved tool being called
   * @param released whether this hook already let this call through before it interrupted (see
   *     {@link #isReleased()})
   */
  public ToolParams(Part requestPart, Tool<?, ?> tool, boolean released) {
    this.requestPart = requestPart;
    this.tool = tool;
    this.released = released;
  }

  /**
   * Reports whether the call is a restart that answers a later stage (a hook after this one, or the
   * tool's own interrupt) and the interrupted call records that this hook let it through. A hook
   * that holds calls for approval passes such a restart on rather than holding it again.
   *
   * <p>False in a fresh call, in the hook the restart answers (which reads the answer from {@link
   * com.google.genkit.core.ActionContext#getResumed()}), in a hook the interrupted call does not
   * record, and in every hook when the restart replaced the call's input.
   *
   * @return whether this hook already released the call
   */
  public boolean isReleased() {
    return released;
  }

  /**
   * Returns a copy of these params with the given released flag.
   *
   * @param released the released flag
   * @return the copy
   */
  public ToolParams withReleased(boolean released) {
    return new ToolParams(requestPart, tool, released);
  }

  /** Returns the full tool request part, including part-level metadata. */
  public Part getRequestPart() {
    return requestPart;
  }

  /** Convenience method: returns the tool request from the request part. */
  public ToolRequest getRequest() {
    return requestPart.getToolRequest();
  }

  /** Returns the resolved tool being called. */
  public Tool<?, ?> getTool() {
    return tool;
  }
}
