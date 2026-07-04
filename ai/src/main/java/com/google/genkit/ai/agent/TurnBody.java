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
 * The body of one agent turn. Implementations run the actual agent logic (e.g. calling the model,
 * executing tools) and return the {@link AgentFinishReason} for this turn.
 *
 * <p>TurnBody is called by {@link SessionRunner#runTurn} after input validation and message
 * appending. On success the runner persists a {@code COMPLETED} snapshot; on exception the runner
 * persists a {@code FAILED} snapshot and swallows the exception.
 *
 * @param <S> the type of the custom session state
 */
@FunctionalInterface
public interface TurnBody<S> {

  /**
   * Runs the body of this turn.
   *
   * @param input the agent input for this turn
   * @param turnCtx the per-turn context (snapshot IDs, turn index)
   * @return the finish reason for this turn (e.g. {@link AgentFinishReason#STOP})
   * @throws Exception if the turn body fails; the runner will catch and record a FAILED snapshot
   */
  AgentFinishReason run(AgentInput input, TurnContext turnCtx) throws Exception;
}
