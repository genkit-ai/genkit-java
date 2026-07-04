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

/**
 * Custom agent handler: runs one invocation's turn loop body for ONE turn at a time via the runner.
 *
 * <p>Implementations receive the {@link SessionRunner} (giving access to session state, messages,
 * and artifacts) and an {@link AgentFnContext} (chunk emitter, abort signal). Task 4.5 ({@code
 * defineCustomAgent}) wraps this interface.
 *
 * @param <S> the type of the custom session state
 */
@FunctionalInterface
public interface AgentFn<S> {

  /**
   * Runs one turn of agent logic.
   *
   * @param sess the session runner for reading/mutating session state
   * @param ctx per-invocation context (streaming and abort support)
   * @return the agent result for this invocation
   * @throws Exception if the agent fails; callers should propagate or handle appropriately
   */
  AgentResult run(SessionRunner<S> sess, AgentFnContext ctx) throws Exception;
}
