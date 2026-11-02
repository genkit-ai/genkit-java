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

import java.util.List;

/**
 * A question that asks for one of its options, which go out in the order given.
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param instructions the question: a string, or any JSON value, such as an object that names the
 *     field of the state the question is about
 * @param options the options, in order
 */
public record ChoiceQuestion(Object instructions, List<ChoiceOption> options) implements Question {

  /**
   * Creates a choice question.
   *
   * @param instructions the question
   * @param options the options, in order
   */
  public ChoiceQuestion {
    options = options == null ? List.of() : List.copyOf(options);
  }

  /**
   * Creates a choice question.
   *
   * @param instructions the question
   * @param options the options, in order
   * @return the question
   */
  public static ChoiceQuestion of(Object instructions, ChoiceOption... options) {
    return new ChoiceQuestion(instructions, List.of(options));
  }
}
