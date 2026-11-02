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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.google.genkit.core.JsonSchemaProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The answer to a choice question: one option of {@code T}, the probability of every option, and
 * how concentrated that distribution is. As a field of a decision type it is also the question: the
 * options are the constants of {@code T}, each with its {@link SystemOne.Option#criteria()}, and
 * the field's {@code @JsonPropertyDescription} is the question's instructions.
 *
 * <pre>{@code
 * enum Dept implements SystemOne.Option {
 *   @JsonProperty("billing") BILLING("Payments, invoicing, refunds"),
 *   @JsonProperty("technical") TECHNICAL("Bugs, outages, integrations"),
 *   @JsonProperty("other") OTHER("None of the above");
 *   ...
 * }
 *
 * record Triage(
 *     @JsonPropertyDescription("Which team should handle this?") Choice<Dept> department) {}
 * }</pre>
 *
 * <p>An option's name on the wire is the name Jackson gives its constant, so {@code @JsonProperty}
 * on a constant renames it. The options go out sorted by name.
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param <T> the option set
 */
@JsonSchemaProvider(QuestionSchemas.class)
@JsonPropertyOrder({"choice", "probabilities", "confidence"})
public final class Choice<T extends Enum<T> & SystemOne.Option> {

  @JsonProperty("choice")
  private T choice;

  @JsonProperty("probabilities")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  private Map<T, Double> probabilities;

  @JsonProperty("confidence")
  private double confidence;

  /** Creates an empty answer, for JSON decoding. */
  public Choice() {}

  /**
   * Creates an answer.
   *
   * @param choice the option chosen
   * @param probabilities the probability of each option
   * @param confidence how concentrated the distribution is, from 0 to 1
   */
  public Choice(T choice, Map<T, Double> probabilities, double confidence) {
    this.choice = choice;
    this.probabilities = probabilities;
    this.confidence = confidence;
  }

  /**
   * Returns the option with the most of the probability.
   *
   * @return the option chosen
   */
  public T choice() {
    return choice;
  }

  /**
   * Returns the model's distribution over the options, which sums to 1.
   *
   * @return the probability of each option, empty when the answer carries none
   */
  public Map<T, Double> probabilities() {
    return probabilities == null ? Map.of() : Collections.unmodifiableMap(probabilities);
  }

  /**
   * Returns how concentrated the distribution is, from 0 to 1.
   *
   * @return the confidence
   */
  public double confidence() {
    return confidence;
  }

  /**
   * Returns the options from most to least likely, ties broken by name. It is empty when the answer
   * carries no distribution.
   *
   * @return the ranked options
   */
  public List<T> ranked() {
    Map<T, Double> p = probabilities();
    List<T> ranked = new ArrayList<>(p.keySet());
    ranked.sort(
        Comparator.comparing((T option) -> p.get(option), Comparator.reverseOrder())
            .thenComparing(QuestionSchemas::wireName));
    return ranked;
  }

  /**
   * Returns the probability gap between the two most likely options: how decisive the choice is,
   * where {@link #confidence()} reads the whole distribution. It is zero with fewer than two
   * options in the distribution.
   *
   * @return the margin
   */
  public double margin() {
    List<T> ranked = ranked();
    if (ranked.size() < 2) {
      return 0;
    }
    return probabilities.get(ranked.get(0)) - probabilities.get(ranked.get(1));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Choice<?> other
        && Objects.equals(choice, other.choice)
        && probabilities().equals(other.probabilities())
        && confidence == other.confidence;
  }

  @Override
  public int hashCode() {
    return Objects.hash(choice, probabilities(), confidence);
  }

  @Override
  public String toString() {
    Map<String, Double> p = new LinkedHashMap<>();
    probabilities().forEach((k, v) -> p.put(QuestionSchemas.wireName(k), v));
    return "Choice{choice="
        + (choice == null ? null : QuestionSchemas.wireName(choice))
        + ", probabilities="
        + p
        + ", confidence="
        + confidence
        + "}";
  }
}
