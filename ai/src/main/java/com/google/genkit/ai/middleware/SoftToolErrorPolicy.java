/*
 * Copyright 2026 Google LLC
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

/**
 * Implemented by a {@link GenerationMiddleware} that has the generate loop return tool errors to
 * the model instead of failing the generation.
 *
 * <p>For each failed tool call, the loop asks every middleware on the call that implements this
 * interface whether it {@linkplain #coversToolErrors(String) covers} the tool. When one does, the
 * error answers the call as {@code {"error": "<message>"}} and the generation continues. Errors a
 * {@code wrapTool} hook raises in place of the tool's own, interrupts, and cancellation are never
 * covered.
 */
public interface SoftToolErrorPolicy {

  /**
   * Reports whether errors of the named tool, or a call to that tool name when no such tool exists,
   * are returned to the model.
   *
   * @param toolName the name of the tool the model called
   * @return whether the error is returned to the model
   */
  boolean coversToolErrors(String toolName);

  /**
   * Reports whether this policy is an explicit error policy for the whole call: with one present,
   * an error of a tool that no policy covers fails the generation. Without any explicit policy on
   * the call, tool errors are returned to the model as {@code "Tool execution failed: <message>"}.
   *
   * @return whether uncovered tool errors fail the generation
   */
  default boolean strictToolErrors() {
    return true;
  }
}
