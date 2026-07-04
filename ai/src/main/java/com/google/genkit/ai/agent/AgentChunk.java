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

package com.google.genkit.ai.agent;

import com.google.genkit.ai.ModelResponseChunk;

/**
 * AgentChunk is the ergonomic wrapper {@link AgentChat#sendStream} hands to the per-chunk callback.
 *
 * <p>It exposes the common slices of an {@link AgentStreamChunk}: streamed model text, an artifact
 * update, and the <em>post-patch</em> custom state (the running client-side custom state with this
 * chunk's {@code customPatch} already applied). The raw chunk is available via {@link #raw()}.
 *
 * @param <S> the type of custom session state
 */
public final class AgentChunk<S> {

  private final AgentStreamChunk raw;
  private final S custom;

  /**
   * Constructs an AgentChunk.
   *
   * @param raw the underlying stream chunk
   * @param custom the custom state after applying this chunk's patch, or {@code null} if this chunk
   *     carried no custom patch
   */
  AgentChunk(AgentStreamChunk raw, S custom) {
    this.raw = raw;
    this.custom = custom;
  }

  /**
   * Returns the streamed model text for this chunk.
   *
   * @return the chunk text, or {@code null} if this chunk carried no model content
   */
  public String text() {
    ModelResponseChunk mc = raw != null ? raw.getModelChunk() : null;
    return mc != null ? mc.getText() : null;
  }

  /**
   * Returns the model response chunk.
   *
   * @return the model chunk, or {@code null}
   */
  public ModelResponseChunk modelChunk() {
    return raw != null ? raw.getModelChunk() : null;
  }

  /**
   * Returns the artifact carried by this chunk.
   *
   * @return the artifact, or {@code null}
   */
  public Artifact artifact() {
    return raw != null ? raw.getArtifact() : null;
  }

  /**
   * Returns the custom state after this chunk's {@code customPatch} was applied to the running
   * client-side state.
   *
   * @return the post-patch custom state, or {@code null} if this chunk carried no custom patch
   */
  public S custom() {
    return custom;
  }

  /**
   * Returns the underlying raw stream chunk.
   *
   * @return the raw chunk
   */
  public AgentStreamChunk raw() {
    return raw;
  }
}
