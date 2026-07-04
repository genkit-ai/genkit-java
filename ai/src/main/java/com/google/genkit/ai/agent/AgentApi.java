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

import com.google.genkit.core.ActionContext;

/**
 * AgentApi is the typed facade interface exposed by an agent, providing snapshot retrieval, abort,
 * identification, and chat-client creation.
 *
 * @param <S> the type of custom session state
 */
public interface AgentApi<S> {

  /**
   * Creates a fresh {@link AgentChat} for a new conversation, using a default action context built
   * from the registry the agent was defined in.
   *
   * <p>This is the convenience overload for the common case. Use {@link #chat(ActionContext)} when
   * you need to supply request-scoped context (e.g. auth / user info that tools should observe).
   *
   * @return a new chat
   */
  AgentChat<S> chat();

  /**
   * Creates an {@link AgentChat} seeded from {@code init} (snapshotId / sessionId / inline state),
   * using a default action context built from the registry the agent was defined in.
   *
   * @param init the seed init (may be {@code null} for a fresh chat)
   * @return a new chat
   */
  AgentChat<S> chat(AgentInit<S> init);

  /**
   * Loads a snapshot and hydrates an {@link AgentChat} whose next send resumes it, using a default
   * action context built from the registry the agent was defined in.
   *
   * @param lookup which snapshot to load (by snapshotId or sessionId)
   * @return a hydrated chat
   */
  AgentChat<S> loadChat(GetSnapshotRequest lookup);

  /**
   * Creates a fresh {@link AgentChat} for a new conversation.
   *
   * @param ctx the action context used to run each turn
   * @return a new chat
   */
  AgentChat<S> chat(ActionContext ctx);

  /**
   * Creates an {@link AgentChat} seeded from {@code init} (snapshotId / sessionId / inline state).
   *
   * @param ctx the action context used to run each turn
   * @param init the seed init (may be {@code null} for a fresh chat)
   * @return a new chat
   */
  AgentChat<S> chat(ActionContext ctx, AgentInit<S> init);

  /**
   * Loads a snapshot and hydrates an {@link AgentChat} whose next send resumes it.
   *
   * @param ctx the action context used to run each turn
   * @param lookup which snapshot to load (by snapshotId or sessionId)
   * @return a hydrated chat
   */
  AgentChat<S> loadChat(ActionContext ctx, GetSnapshotRequest lookup);

  /**
   * Retrieves a session snapshot by snapshot ID or session ID.
   *
   * @param req the request specifying which snapshot to retrieve
   * @return the matching session snapshot, or {@code null} if not found
   */
  SessionSnapshot<S> getSnapshotData(GetSnapshotRequest req);

  /**
   * Attempts to abort a snapshot that is currently in {@link SnapshotStatus#PENDING} state.
   *
   * <p>If the snapshot is already in a terminal state, returns that state unchanged. If the
   * snapshot does not exist, returns {@code null}.
   *
   * @param snapshotId the ID of the snapshot to abort
   * @return the resulting {@link SnapshotStatus}, or {@code null} if the snapshot was not found
   */
  SnapshotStatus abort(String snapshotId);

  /**
   * Returns a lightweight reference to this agent.
   *
   * @return an {@link AgentRef} carrying the agent's name and description
   */
  AgentRef ref();
}
