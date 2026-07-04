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

package com.google.genkit.ai.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.agent.AgentStreamChunk;
import com.google.genkit.ai.agent.Artifact;
import com.google.genkit.ai.agent.Session;
import com.google.genkit.core.jsonpatch.JsonPatch;
import java.util.function.Consumer;

/**
 * Bridges Session state-change events to AgentStreamChunk emission for one invocation.
 *
 * <p>Custom-state changes become customPatch chunks (first-of-turn = whole-doc replace, then
 * incremental diffs); artifact add/update become artifact chunks. Suppressed entirely while
 * detached.
 *
 * @param <S> the type of the custom session state object
 */
public final class StreamEmitter<S> {

  private final Consumer<AgentStreamChunk> sink;
  private final ObjectMapper mapper;

  /** Whether this emitter is suppressed (e.g. for detached runs). */
  private volatile boolean suppressed = false;

  /**
   * True at the start of each turn. The first customPatch of a turn is always a whole-document
   * replace; subsequent ones are incremental diffs.
   */
  private boolean firstCustomPatchInTurn = true;

  /**
   * The JsonNode of custom state after the last emitted customPatch. Used to compute incremental
   * diffs. Null before the first patch of a turn.
   */
  private JsonNode lastCustomJson = null;

  /**
   * Constructs a new StreamEmitter.
   *
   * @param sink receives AgentStreamChunk instances; must not be null
   * @param mapper ObjectMapper for converting custom state S to JsonNode; must not be null
   */
  public StreamEmitter(Consumer<AgentStreamChunk> sink, ObjectMapper mapper) {
    this.sink = sink;
    this.mapper = mapper;
  }

  /**
   * Attaches this emitter to a Session. Registers onCustomChanged and onArtifactChanged listeners
   * that emit customPatch and artifact chunks respectively.
   *
   * <p>Since {@link Session#setOnCustomChanged(Runnable)} takes a {@code Runnable}, the current
   * custom state is retrieved via {@code session.getCustom()} inside the callback.
   *
   * @param session the session to attach to
   */
  public void attach(Session<S> session) {
    session.setOnCustomChanged(
        () -> {
          if (suppressed) {
            return;
          }
          S custom = session.getCustom();
          JsonNode cur = mapper.valueToTree(custom);

          if (firstCustomPatchInTurn) {
            // First patch of this turn: whole-document replace
            JsonNode patch = JsonPatch.wholeDocumentReplace(cur);
            sink.accept(AgentStreamChunk.builder().customPatch(patch).build());
            firstCustomPatchInTurn = false;
          } else {
            // Subsequent patches: incremental diff
            JsonNode patch = JsonPatch.diff(lastCustomJson, cur);
            if (patch.size() > 0) {
              sink.accept(AgentStreamChunk.builder().customPatch(patch).build());
            }
            // Skip emitting if the diff is empty (no-op update)
          }
          lastCustomJson = cur;
        });

    session.setOnArtifactChanged(
        (Artifact artifact) -> {
          if (suppressed) {
            return;
          }
          sink.accept(AgentStreamChunk.builder().artifact(artifact).build());
        });
  }

  /**
   * Call at the start of each turn. The next customPatch emitted will be a whole-document replace
   * rather than an incremental diff.
   */
  public void beginTurn() {
    firstCustomPatchInTurn = true;
    lastCustomJson = null;
  }

  /**
   * Controls whether chunks are emitted. When {@code true}, all custom-state and artifact changes
   * are silently dropped. Use for detached (non-streaming) runs.
   *
   * @param suppressed {@code true} to suppress emission; {@code false} to re-enable
   */
  public void setSuppressed(boolean suppressed) {
    this.suppressed = suppressed;
  }
}
