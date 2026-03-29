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
import com.google.genkit.ai.ModelResponseChunk;
import java.util.function.Consumer;

/** Holds parameters for the {@link GenerationMiddleware#wrapModel} hook. */
public class ModelParams {

  private final ModelRequest request;
  private final Consumer<ModelResponseChunk> streamCallback;

  /**
   * Creates ModelParams.
   *
   * @param request the model request about to be sent
   * @param streamCallback the streaming callback, or null if not streaming
   */
  public ModelParams(ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
    this.request = request;
    this.streamCallback = streamCallback;
  }

  /** Returns the model request about to be sent. */
  public ModelRequest getRequest() {
    return request;
  }

  /** Returns the streaming callback, or null if not streaming. */
  public Consumer<ModelResponseChunk> getStreamCallback() {
    return streamCallback;
  }

  /** Returns a new ModelParams with the given request, preserving the stream callback. */
  public ModelParams withRequest(ModelRequest request) {
    return new ModelParams(request, this.streamCallback);
  }
}
