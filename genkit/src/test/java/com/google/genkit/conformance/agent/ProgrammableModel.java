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

package com.google.genkit.conformance.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.JsonUtils;
import java.util.List;
import java.util.function.Consumer;

/**
 * A {@link Model} whose per-call behaviour is programmed per {@code send} step.
 *
 * <p>Mirrors the JS {@code defineProgrammableModel} / Go {@code programmableModel} helpers: for the
 * {@code i}-th generate call of a step it streams {@code streamChunks[i]} (if present) then returns
 * {@code modelResponses[i]}. The request is echoed onto the response ({@link
 * ModelResponse#setRequest}) so the generate tool loop can thread intermediate tool-request /
 * tool-response messages into the session history (the prompt agent relies on this).
 *
 * <p>Responses and chunks are supplied as raw {@link JsonNode} (parsed straight from the spec YAML)
 * and converted to typed objects on demand, so the harness never has to hand-build model types.
 */
final class ProgrammableModel implements Model {

  private final String name;

  /** Per-call responses, set by {@link #program} at the start of each {@code send} step. */
  private List<JsonNode> modelResponses = List.of();

  /** Per-call streaming chunks (outer index = call, inner list = chunks for that call). */
  private List<List<JsonNode>> streamChunks = List.of();

  /** How many generate calls have happened in the current step. */
  private int callCount;

  ProgrammableModel(String name) {
    this.name = name;
  }

  /**
   * Programs the model for the next {@code send} step. Resets the per-step call counter.
   *
   * @param modelResponses one {@link ModelResponse}-shaped node per generate call (may be empty)
   * @param streamChunks per-call lists of {@link ModelResponseChunk}-shaped nodes (may be empty)
   */
  void program(List<JsonNode> modelResponses, List<List<JsonNode>> streamChunks) {
    this.modelResponses = modelResponses != null ? modelResponses : List.of();
    this.streamChunks = streamChunks != null ? streamChunks : List.of();
    this.callCount = 0;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public ModelInfo getInfo() {
    ModelInfo info = new ModelInfo();
    ModelInfo.ModelCapabilities caps = new ModelInfo.ModelCapabilities();
    caps.setMultiturn(true);
    caps.setTools(true);
    caps.setSystemRole(true);
    info.setSupports(caps);
    return info;
  }

  @Override
  public boolean supportsStreaming() {
    return true;
  }

  @Override
  public ModelResponse run(ActionContext ctx, ModelRequest request) {
    return run(ctx, request, null);
  }

  @Override
  public ModelResponse run(
      ActionContext ctx, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
    int i = callCount++;

    if (streamCallback != null && i < streamChunks.size()) {
      for (JsonNode chunkNode : streamChunks.get(i)) {
        ModelResponseChunk chunk = JsonUtils.fromJsonNode(chunkNode, ModelResponseChunk.class);
        streamCallback.accept(chunk);
      }
    }

    if (i >= modelResponses.size()) {
      throw new IllegalStateException(
          "programmableModel: no response programmed for generate call " + i);
    }

    ModelResponse response = buildResponse(modelResponses.get(i));
    // Echo the request back, as every real model does. The tool loop relies on
    // response.getRequest()
    // to thread intermediate tool-request / tool-response messages into history.
    response.setRequest(request);
    return response;
  }

  /**
   * Builds a {@link ModelResponse} from a spec {@code GenerateResponse}-shaped node ({@code
   * {message, finishReason}}). The Java {@link ModelResponse} carries the message under a {@code
   * candidate}, so the message is lifted into a single {@link Candidate} (a plain {@code
   * treeToValue} would silently drop the top-level {@code message} field).
   */
  private static ModelResponse buildResponse(JsonNode node) {
    Message message = null;
    if (node.hasNonNull("message")) {
      message = JsonUtils.fromJsonNode(node.get("message"), Message.class);
    }
    FinishReason finishReason = null;
    if (node.hasNonNull("finishReason")) {
      finishReason =
          JsonUtils.getObjectMapper().convertValue(node.get("finishReason"), FinishReason.class);
    }
    Candidate candidate = new Candidate(message, finishReason);
    ModelResponse response = new ModelResponse(List.of(candidate));
    response.setFinishReason(finishReason);
    return response;
  }
}
