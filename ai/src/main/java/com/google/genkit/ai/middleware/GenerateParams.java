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

import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.ModelResponseChunk;
import java.util.function.Consumer;

/**
 * Holds parameters for the {@link GenerationMiddleware#wrapGenerate} hook.
 *
 * <p>The request is a {@link GenerateActionOptions} — the <em>high-level</em> generate options that
 * have not yet been resolved to a {@link com.google.genkit.ai.ModelRequest ModelRequest}. This
 * allows {@code wrapGenerate} middleware to modify values such as the model name, tool list, or
 * output format before resolution occurs.
 *
 * <p>This mirrors the JS SDK where the {@code generate} middleware hook receives {@code
 * GenerateActionOptions} (high-level), while the {@code model} middleware hook receives {@code
 * GenerateRequest}/{@code ModelRequest} (low-level, resolved).
 */
public class GenerateParams {

  private final GenerateActionOptions request;
  private final int iteration;
  private final int messageIndex;
  private final Consumer<ModelResponseChunk> onChunk;

  /**
   * Creates GenerateParams.
   *
   * @param request the current high-level generate options for this iteration
   * @param iteration the current tool-loop iteration (0-indexed)
   * @param messageIndex the current message index in the conversation (position the next model
   *     response will occupy). This mirrors the JS SDK's {@code messageIndex} and is useful for
   *     streaming chunk attribution and middleware that tracks conversation position.
   */
  public GenerateParams(GenerateActionOptions request, int iteration, int messageIndex) {
    this(request, iteration, messageIndex, null);
  }

  /**
   * Creates GenerateParams with all fields including streaming callback.
   *
   * @param request the current high-level generate options for this iteration
   * @param iteration the current tool-loop iteration (0-indexed)
   * @param messageIndex the current message index
   * @param onChunk the streaming chunk callback, or null if not streaming. Mirrors the JS SDK's
   *     {@code onChunk} in the generate middleware hook, allowing middleware to observe or
   *     transform streaming chunks.
   */
  public GenerateParams(
      GenerateActionOptions request,
      int iteration,
      int messageIndex,
      Consumer<ModelResponseChunk> onChunk) {
    this.request = request;
    this.iteration = iteration;
    this.messageIndex = messageIndex;
    this.onChunk = onChunk;
  }

  /**
   * Creates GenerateParams with messageIndex defaulting to the message count in the request.
   *
   * @param request the current high-level generate options for this iteration
   * @param iteration the current tool-loop iteration (0-indexed)
   */
  public GenerateParams(GenerateActionOptions request, int iteration) {
    this(request, iteration, request.getMessages() != null ? request.getMessages().size() : 0);
  }

  /**
   * Returns the current high-level generate options.
   *
   * <p>Unlike {@link ModelParams#getRequest()}, which returns a resolved {@code ModelRequest}, this
   * returns the unresolved {@link GenerateActionOptions} containing the model name as a string,
   * tool names as string references, etc.
   */
  public GenerateActionOptions getRequest() {
    return request;
  }

  /** Returns the current tool-loop iteration (0-indexed), equivalent to JS {@code currentTurn}. */
  public int getIteration() {
    return iteration;
  }

  /**
   * Returns the current message index — the position in the conversation that the next model
   * response will occupy. Starts at 0 and increments as messages are added (model responses, tool
   * responses, etc.). Equivalent to the JS SDK's {@code messageIndex}.
   */
  public int getMessageIndex() {
    return messageIndex;
  }

  /**
   * Returns the streaming chunk callback, or {@code null} if this is a non-streaming request.
   *
   * <p>Mirrors the JS SDK's {@code onChunk} in the generate middleware hook. When non-null, the
   * model will be called with streaming enabled and each chunk will be forwarded through this
   * callback. Middleware can wrap or replace the callback to observe/transform streaming chunks.
   */
  public Consumer<ModelResponseChunk> getOnChunk() {
    return onChunk;
  }

  /** Returns a new GenerateParams with the given request, preserving other fields. */
  public GenerateParams withRequest(GenerateActionOptions request) {
    return new GenerateParams(request, this.iteration, this.messageIndex, this.onChunk);
  }

  /** Returns a new GenerateParams with the given messageIndex, preserving other fields. */
  public GenerateParams withMessageIndex(int messageIndex) {
    return new GenerateParams(this.request, this.iteration, messageIndex, this.onChunk);
  }

  /** Returns a new GenerateParams with the given onChunk callback, preserving other fields. */
  public GenerateParams withOnChunk(Consumer<ModelResponseChunk> onChunk) {
    return new GenerateParams(this.request, this.iteration, this.messageIndex, onChunk);
  }
}
