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
 * ClientTransform allows a client-managed agent to transform session state before returning it to
 * the caller.
 *
 * @param <S> the type of custom state
 */
@FunctionalInterface
public interface ClientTransform<S> {

  /**
   * Transforms the given session state before it is returned to the caller.
   *
   * @param state the current session state
   * @return the transformed session state
   */
  SessionState<S> transformState(SessionState<S> state);
}
