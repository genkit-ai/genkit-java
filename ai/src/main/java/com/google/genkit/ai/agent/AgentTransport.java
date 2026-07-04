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

import java.util.function.Consumer;

/**
 * AgentTransport abstracts how a single agent turn is executed. The in-process transport ({@code
 * internal.InProcessTransport}) drives a locally-defined {@link Agent}'s bidi action; a remote
 * transport (future) would issue HTTP/RPC calls. {@link AgentChat} is written against this
 * interface so it can drive either.
 *
 * <p>The transport models the agent's <em>one-turn-per-request</em> contract: {@link #runTurn}
 * feeds exactly one {@link AgentInput} (with its {@link AgentInit}) and returns the turn's final
 * {@link AgentOutput}, streaming {@link AgentStreamChunk}s along the way.
 *
 * @param <S> the type of custom session state
 */
public interface AgentTransport<S> {

  /**
   * Runs ONE turn: feeds {@code input} with {@code init}, collects stream chunks via {@code
   * onChunk}, and returns the final output.
   *
   * @param input the turn input (user message, resume, or detach)
   * @param init the initialization carrying resume context (snapshotId / sessionId / inline state);
   *     may be {@code null} for the very first turn of a fresh session
   * @param onChunk consumer invoked for each streamed chunk; may be a no-op
   * @return the final {@link AgentOutput} for the turn
   */
  AgentOutput<S> runTurn(AgentInput input, AgentInit<S> init, Consumer<AgentStreamChunk> onChunk);

  /**
   * Retrieves a session snapshot.
   *
   * @param req the request specifying which snapshot to retrieve
   * @return the matching snapshot, or {@code null} if not found / not supported
   */
  SessionSnapshot<S> getSnapshot(GetSnapshotRequest req);

  /**
   * Attempts to abort a pending snapshot.
   *
   * @param snapshotId the snapshot to abort
   * @return the resulting status, or {@code null} if not found / not supported
   */
  SnapshotStatus abort(String snapshotId);

  /**
   * Returns whether the underlying agent is server-managed (state persisted server-side) versus
   * client-managed (state round-tripped by the caller).
   *
   * @return {@code true} if server-managed
   */
  boolean serverManaged();
}
