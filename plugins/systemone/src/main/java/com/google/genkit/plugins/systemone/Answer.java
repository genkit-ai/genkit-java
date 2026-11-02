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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The answer to a question of a {@link SystemOne#schema(Map)}, with the fields of its kind set.
 * {@link #type()} names the kind: "choice" sets {@link #choice()}, {@link #probabilities()} over
 * the options, and {@link #confidence()}; "score" sets {@link #score()}, {@link #probabilities()}
 * keyed by level number, {@link #confidence()}, and {@link #legend()}; "noul" sets {@link
 * #probability()} alone. They mean what the same accessors of {@link Choice}, {@link Score}, and
 * {@link NoulOf} mean.
 *
 * <p>Decoding an answer sets the type from the field that names the kind, so a response's answers
 * need no discriminator. Encoding one writes the fields of its kind, zeros included, so a score of
 * 0 survives a round trip, and none of another kind's.
 *
 * <p>Preview: this API may change in any minor release.
 */
public final class Answer {

  private final String type;
  private final String choice;
  private final double score;
  private final double probability;
  private final Map<String, Double> probabilities;
  private final double confidence;
  private final Map<String, String> legend;

  private Answer(
      String type,
      String choice,
      double score,
      double probability,
      Map<String, Double> probabilities,
      double confidence,
      Map<String, String> legend) {
    this.type = type == null ? "" : type;
    this.choice = choice == null ? "" : choice;
    this.score = score;
    this.probability = probability;
    this.probabilities = probabilities;
    this.confidence = confidence;
    this.legend = legend;
  }

  /**
   * Creates the answer to a choice question.
   *
   * @param choice the option chosen
   * @param probabilities the probability of each option
   * @param confidence how concentrated the distribution is
   * @return the answer
   */
  public static Answer choice(String choice, Map<String, Double> probabilities, double confidence) {
    return new Answer(Wire.KIND_CHOICE, choice, 0, 0, probabilities, confidence, null);
  }

  /**
   * Creates the answer to a score question.
   *
   * @param score the expected level
   * @param probabilities the probability of each level, keyed by level number
   * @param confidence how concentrated the distribution is
   * @param legend each level number's description
   * @return the answer
   */
  public static Answer score(
      double score,
      Map<String, Double> probabilities,
      double confidence,
      Map<String, String> legend) {
    return new Answer(Wire.KIND_SCORE, null, score, 0, probabilities, confidence, legend);
  }

  /**
   * Creates the answer to a yes/no question.
   *
   * @param probability the probability that the statement is true
   * @return the answer
   */
  public static Answer noul(double probability) {
    return new Answer(Wire.KIND_NOUL, null, 0, probability, null, 0, null);
  }

  /**
   * Decodes an answer from its JSON fields. A missing type is read from the field every answer of a
   * kind carries: choice, score, or noul.
   *
   * @param fields the JSON fields
   * @return the answer
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static Answer fromJson(Map<String, Object> fields) {
    String type = fields.get("type") instanceof String s ? s : "";
    if (type.isEmpty()) {
      for (String kind : List.of(Wire.KIND_CHOICE, Wire.KIND_SCORE, Wire.KIND_NOUL)) {
        if (fields.containsKey(kind)) {
          type = kind;
          break;
        }
      }
    }
    return new Answer(
        type,
        fields.get("choice") instanceof String s ? s : "",
        number(fields.get("score")),
        number(fields.get("noul")),
        doubles(fields.get("probabilities")),
        number(fields.get("confidence")),
        strings(fields.get("legend")));
  }

  /**
   * Encodes the answer: the fields of its kind, zeros included, with its type. An answer with no
   * type writes its non-zero fields.
   *
   * @return the JSON fields, keys sorted
   */
  @JsonValue
  public Map<String, Object> toJson() {
    Map<String, Object> out = new TreeMap<>();
    switch (type) {
      case Wire.KIND_CHOICE -> {
        out.put("choice", choice);
        out.put("confidence", Wire.number(confidence));
        putIfPresent(out, "probabilities", numbers(probabilities));
      }
      case Wire.KIND_SCORE -> {
        out.put("score", Wire.number(score));
        out.put("confidence", Wire.number(confidence));
        putIfPresent(out, "probabilities", numbers(probabilities));
        putIfPresent(out, "legend", legend == null ? null : new TreeMap<>(legend));
      }
      case Wire.KIND_NOUL -> out.put("noul", Wire.number(probability));
      default -> {
        if (!choice.isEmpty()) {
          out.put("choice", choice);
        }
        if (score != 0) {
          out.put("score", Wire.number(score));
        }
        if (probability != 0) {
          out.put("noul", Wire.number(probability));
        }
        if (probabilities != null && !probabilities.isEmpty()) {
          out.put("probabilities", numbers(probabilities));
        }
        if (confidence != 0) {
          out.put("confidence", Wire.number(confidence));
        }
        if (legend != null && !legend.isEmpty()) {
          out.put("legend", new TreeMap<>(legend));
        }
        return out;
      }
    }
    out.put("type", type);
    return out;
  }

  /**
   * Returns the kind of question answered: "choice", "score", or "noul".
   *
   * @return the kind
   */
  public String type() {
    return type;
  }

  /**
   * Returns the option chosen, for a choice.
   *
   * @return the option, or the empty string
   */
  public String choice() {
    return choice;
  }

  /**
   * Returns the expected level, for a score.
   *
   * @return the score
   */
  public double score() {
    return score;
  }

  /**
   * Returns the probability that the statement is true, for a noul.
   *
   * @return the probability
   */
  public double probability() {
    return probability;
  }

  /**
   * Returns the distribution over the options or the levels.
   *
   * @return the probabilities, empty when there are none
   */
  public Map<String, Double> probabilities() {
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
   * Returns each level number's description, for a score.
   *
   * @return the legend, empty when there is none
   */
  public Map<String, String> legend() {
    return legend == null ? Map.of() : Collections.unmodifiableMap(legend);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Answer other && toJson().equals(other.toJson());
  }

  @Override
  public int hashCode() {
    return Objects.hash(toJson());
  }

  @Override
  public String toString() {
    return "Answer" + toJson();
  }

  private static void putIfPresent(Map<String, Object> out, String key, Object value) {
    if (value != null) {
      out.put(key, value);
    }
  }

  private static Map<String, Number> numbers(Map<String, Double> values) {
    if (values == null) {
      return null;
    }
    Map<String, Number> out = new TreeMap<>();
    values.forEach((k, v) -> out.put(k, v == null ? null : Wire.number(v)));
    return out;
  }

  private static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0;
  }

  private static Map<String, Double> doubles(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    Map<String, Double> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(String.valueOf(k), number(v)));
    return out;
  }

  private static Map<String, String> strings(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    Map<String, String> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(String.valueOf(k), v instanceof String s ? s : Wire.json(v)));
    return out;
  }
}
