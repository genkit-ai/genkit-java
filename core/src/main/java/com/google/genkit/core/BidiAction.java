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

package com.google.genkit.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;

/**
 * A bidirectional streaming action.
 *
 * <p>A {@code BidiAction} accepts a one-time session-init value ({@code Init}), a stream of
 * per-turn inputs ({@link InputSource}{@code <I>}), streams chunk values of type {@code S} via a
 * callback, and returns a final output of type {@code O}.
 *
 * <p>Callers that only need a single input can use the inherited {@link Action#run} methods, which
 * wrap the single input in a {@link BufferedInputSource} and invoke the bidi handler transparently.
 *
 * @param <I> per-turn input type
 * @param <O> final output type
 * @param <S> stream chunk type
 * @param <Init> session-init type (pass {@code Void} / {@code null} when not needed)
 */
public interface BidiAction<I, O, S, Init> extends Action<I, O, S> {

  /**
   * Runs the action in bidirectional-streaming mode.
   *
   * @param ctx the action context
   * @param init the one-time session-init value; may be {@code null}
   * @param inputs the stream of per-turn inputs
   * @param streamCallback callback invoked for each emitted chunk; may be {@code null}
   * @return the final output
   * @throws GenkitException if execution fails
   */
  O runBidi(ActionContext ctx, Init init, InputSource<I> inputs, Consumer<S> streamCallback)
      throws GenkitException;

  /**
   * Runs the action in bidirectional-streaming mode with JSON-typed arguments.
   *
   * <p>Deserializes {@code init} and each element from {@code inputs} before handing them to the
   * typed handler, and serializes all chunks and the final result back to {@link JsonNode}.
   *
   * @param ctx the action context
   * @param init the one-time session-init as a {@link JsonNode}; may be {@code null}
   * @param inputs the stream of per-turn inputs as {@link JsonNode} values
   * @param streamCallback callback invoked for each emitted chunk serialized to {@link JsonNode};
   *     may be {@code null}
   * @return the final output serialized to {@link JsonNode}
   * @throws GenkitException if execution fails
   */
  JsonNode runBidiJson(
      ActionContext ctx,
      JsonNode init,
      InputSource<JsonNode> inputs,
      Consumer<JsonNode> streamCallback)
      throws GenkitException;

  /**
   * Runs the action in bidirectional-streaming mode with JSON-typed arguments and telemetry.
   *
   * <p>Behaves like {@link #runBidiJson} (deserializing {@code init} and each element from {@code
   * inputs}, serializing all chunks and the final result back to {@link JsonNode}) but additionally
   * creates a new tracing span and captures the resulting trace/span IDs, returning them in an
   * {@link ActionRunResult}. Unlike the unary {@link Action#runJsonWithTelemetry}, this threads the
   * real {@code init} and the full stream of {@code inputs} through to the handler.
   *
   * @param ctx the action context
   * @param init the one-time session-init as a {@link JsonNode}; may be {@code null}
   * @param inputs the stream of per-turn inputs as {@link JsonNode} values
   * @param streamCallback callback invoked for each emitted chunk serialized to {@link JsonNode};
   *     may be {@code null}
   * @return the final output serialized to {@link JsonNode} together with the captured trace/span
   *     IDs
   * @throws GenkitException if execution fails
   */
  ActionRunResult<JsonNode> runBidiJsonWithTelemetry(
      ActionContext ctx,
      JsonNode init,
      InputSource<JsonNode> inputs,
      Consumer<JsonNode> streamCallback)
      throws GenkitException;

  // -------------------------------------------------------------------------
  // Nested functional interface for the handler
  // -------------------------------------------------------------------------

  /**
   * Functional interface that implements the bidirectional streaming logic.
   *
   * @param <I> per-turn input type
   * @param <O> final output type
   * @param <S> stream chunk type
   * @param <Init> session-init type
   */
  @FunctionalInterface
  interface BidiHandler<I, O, S, Init> {

    /**
     * Handles one invocation of the bidirectional action.
     *
     * @param ctx the action context
     * @param init the one-time session-init value; may be {@code null}
     * @param inputs the stream of per-turn inputs
     * @param streamCallback callback for emitting chunks
     * @return the final output
     * @throws Exception if handling fails
     */
    O handle(ActionContext ctx, Init init, InputSource<I> inputs, Consumer<S> streamCallback)
        throws Exception;
  }
}
