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

/**
 * A question that asks whether a statement is true, with what yes and no mean as {@link
 * SystemOne.YesNo} gives them for a {@link NoulOf}: each side needs a string or guidance, or
 * neither does.
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param instructions the question: a string, or any JSON value
 * @param yes what yes means, or null
 * @param no what no means, or null
 * @param yesGuidance the structured form of what yes means, or null
 * @param noGuidance the structured form of what no means, or null
 */
public record NoulQuestion(
    Object instructions, String yes, String no, Object yesGuidance, Object noGuidance)
    implements Question {

  /**
   * Creates a yes/no question with no criteria.
   *
   * @param instructions the question
   * @return the question
   */
  public static NoulQuestion of(Object instructions) {
    return new NoulQuestion(instructions, null, null, null, null);
  }

  /**
   * Creates a yes/no question with what yes and no mean.
   *
   * @param instructions the question
   * @param yes what yes means
   * @param no what no means
   * @return the question
   */
  public static NoulQuestion of(Object instructions, String yes, String no) {
    return new NoulQuestion(instructions, yes, no, null, null);
  }
}
