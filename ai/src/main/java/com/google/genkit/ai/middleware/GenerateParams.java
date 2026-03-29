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

import com.google.genkit.ai.ModelRequest;

/** Holds parameters for the {@link GenerationMiddleware#wrapGenerate} hook. */
public class GenerateParams {

  private final ModelRequest request;
  private final int iteration;

  /**
   * Creates GenerateParams.
   *
   * @param request the current model request for this iteration
   * @param iteration the current tool-loop iteration (0-indexed)
   */
  public GenerateParams(ModelRequest request, int iteration) {
    this.request = request;
    this.iteration = iteration;
  }

  /** Returns the current model request with accumulated messages. */
  public ModelRequest getRequest() {
    return request;
  }

  /** Returns the current tool-loop iteration (0-indexed). */
  public int getIteration() {
    return iteration;
  }

  /** Returns a new GenerateParams with the given request, preserving the iteration. */
  public GenerateParams withRequest(ModelRequest request) {
    return new GenerateParams(request, this.iteration);
  }
}
