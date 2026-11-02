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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.genkit.core.JsonSchemaProvider;

/**
 * The answer to a yes/no question whose criteria come from {@code C}: the probability that the
 * statement is true. A value near 0.5 means the model could not tell, not that the answer is
 * "somewhat". There is no separate confidence; the probability is the whole answer.
 *
 * <p>As a field of a decision type it is also the question: the field's
 * {@code @JsonPropertyDescription} is the question, and {@code C} says what yes and no mean. {@link
 * Noul} is the question with no criteria.
 *
 * <pre>{@code
 * public static final class Urgent implements SystemOne.YesNo {
 *   public String yes() { return "Names a deadline, or says now or today"; }
 *   public String no() { return "No time pressure is expressed"; }
 * }
 *
 * record Triage(
 *     @JsonPropertyDescription("Does the ticket explicitly communicate time pressure?")
 *     NoulOf<Urgent> isUrgent) {}
 * }</pre>
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param <C> the yes and no criteria
 */
@JsonSchemaProvider(QuestionSchemas.class)
public class NoulOf<C extends SystemOne.YesNo> {

  @JsonProperty("noul")
  private double probability;

  /** Creates an empty answer, for JSON decoding. */
  public NoulOf() {}

  /**
   * Creates an answer.
   *
   * @param probability the probability that the statement is true
   */
  public NoulOf(double probability) {
    this.probability = probability;
  }

  /**
   * Returns the probability that the statement is true, from 0 to 1.
   *
   * @return the probability
   */
  public double probability() {
    return probability;
  }

  /**
   * Returns this answer as a plain {@link Noul}, for a helper that takes any yes/no answer.
   *
   * @return the answer without its criteria type
   */
  public Noul toNoul() {
    return new Noul(probability);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof NoulOf<?> other && probability == other.probability;
  }

  @Override
  public int hashCode() {
    return Double.hashCode(probability);
  }

  @Override
  public String toString() {
    return "Noul{probability=" + probability + "}";
  }
}
