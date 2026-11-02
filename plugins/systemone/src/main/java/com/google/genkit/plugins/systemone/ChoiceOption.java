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
 * One option of a {@link ChoiceQuestion}: the name that is the answer when it is chosen, what it
 * means, and optionally the structured form of that meaning, as {@link SystemOne.Option#guidance()}
 * gives it for a {@link Choice}. An option with neither criteria nor guidance is described by its
 * name.
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param name the option's name, which is the answer when it is chosen
 * @param criteria what the option means
 * @param guidance the structured form of the criteria, any JSON value, or null
 */
public record ChoiceOption(String name, String criteria, Object guidance) {

  /**
   * Creates an option with criteria and no guidance.
   *
   * @param name the option's name
   * @param criteria what the option means
   * @return the option
   */
  public static ChoiceOption of(String name, String criteria) {
    return new ChoiceOption(name, criteria, null);
  }

  /**
   * Creates an option described by its name alone.
   *
   * @param name the option's name
   * @return the option
   */
  public static ChoiceOption of(String name) {
    return new ChoiceOption(name, null, null);
  }
}
