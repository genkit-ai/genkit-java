/*
 * Copyright 2026 Google LLC
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

package com.google.genkit.plugins.systemone;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A question that rates the state on its levels, lowest first, as a {@link SystemOne.Level} enum
 * gives them, with guidance keyed by level index as {@link SystemOne.Level#guidance()} gives it.
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param instructions the question: a string, or any JSON value
 * @param levels the level descriptions, lowest first
 * @param guidance the structured form of a level's description, keyed by level index
 */
public record ScoreQuestion(Object instructions, List<String> levels, Map<Integer, Object> guidance)
    implements Question {

  /**
   * Creates a score question.
   *
   * @param instructions the question
   * @param levels the level descriptions, lowest first
   * @param guidance the guidance by level index, or null
   */
  public ScoreQuestion {
    levels = levels == null ? List.of() : List.copyOf(levels);
    guidance =
        guidance == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(guidance));
  }

  /**
   * Creates a score question with no guidance.
   *
   * @param instructions the question
   * @param levels the level descriptions, lowest first
   * @return the question
   */
  public static ScoreQuestion of(Object instructions, String... levels) {
    return new ScoreQuestion(instructions, List.of(levels), null);
  }
}
