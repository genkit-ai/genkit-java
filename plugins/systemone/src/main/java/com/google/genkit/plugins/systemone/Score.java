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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.genkit.core.JsonSchemaProvider;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The answer to a rubric question: the expected level, computed from the probability of each level,
 * so it falls between levels when the model is split. As a field of a decision type it is also the
 * question: the levels are the constants of {@code L}, lowest first, each with its {@link
 * SystemOne.Level#description()}, and the field's {@code @JsonPropertyDescription} is the
 * question's instructions. A level's index is its number on the wire, so a three-level rubric
 * scores 0 to 2.
 *
 * <pre>{@code
 * enum Anger implements SystemOne.Level {
 *   CALM("Calm"), CONCERNED("Concerned but civil"), VERY_ANGRY("Very angry");
 *   ...
 * }
 *
 * record Triage(
 *     @JsonPropertyDescription("How frustrated is the customer?") Score<Anger> frustration) {}
 * }</pre>
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param <L> the rubric
 */
@JsonSchemaProvider(QuestionSchemas.class)
@JsonDeserialize(using = Score.Deserializer.class)
@JsonPropertyOrder({"score", "probabilities", "confidence", "legend"})
public final class Score<L extends Enum<L> & SystemOne.Level> {

  @JsonProperty("score")
  private final double score;

  @JsonProperty("probabilities")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  private final Map<String, Double> probabilities;

  @JsonProperty("confidence")
  private final double confidence;

  @JsonProperty("legend")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  private final Map<String, String> legend;

  /** The rubric, when known, which {@link #level()} and {@link #label()} read. */
  private final transient Class<L> rubric;

  /**
   * Creates an answer.
   *
   * @param rubric the rubric's enum class
   * @param score the expected level
   * @param probabilities the probability of each level, keyed by level number
   * @param confidence how concentrated the distribution is, from 0 to 1
   * @param legend each level number's description
   */
  public Score(
      Class<L> rubric,
      double score,
      Map<String, Double> probabilities,
      double confidence,
      Map<String, String> legend) {
    this.rubric = rubric;
    this.score = score;
    this.probabilities = probabilities;
    this.confidence = confidence;
    this.legend = legend;
  }

  /**
   * Creates an answer with only a score.
   *
   * @param rubric the rubric's enum class
   * @param score the expected level
   */
  public Score(Class<L> rubric, double score) {
    this(rubric, score, null, 0, null);
  }

  /**
   * Returns the expected level, from 0 to the number of levels less one; it falls between two
   * levels when the model is split.
   *
   * @return the score
   */
  public double score() {
    return score;
  }

  /**
   * Returns the distribution over the levels, keyed by level number.
   *
   * @return the probabilities, empty when the answer carries none
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
   * Returns each level number's description, the rubric's string even when the level was sent with
   * guidance.
   *
   * @return the legend, empty when the answer carries none
   */
  public Map<String, String> legend() {
    return legend == null ? Map.of() : Collections.unmodifiableMap(legend);
  }

  /**
   * Returns the index of the nearest whole level, clamped to the rubric.
   *
   * @return the level index
   */
  public int levelIndex() {
    int n = levelCount();
    if (n == 0) {
      return 0;
    }
    return (int) Math.min(Math.max(Math.round(score), 0), n - 1);
  }

  /**
   * Returns the nearest whole level, clamped to the rubric, or null when the rubric is unknown,
   * which it is only for an answer decoded without its type.
   *
   * @return the level
   */
  public L level() {
    if (rubric == null || rubric.getEnumConstants().length == 0) {
      return null;
    }
    return rubric.getEnumConstants()[levelIndex()];
  }

  /**
   * Returns the description of {@link #level()}. It comes from the rubric, so it does not depend on
   * the answer carrying a legend; without the rubric it is read from the legend.
   *
   * @return the label, or the empty string when there are no levels
   */
  public String label() {
    L level = level();
    if (level != null) {
      return level.description();
    }
    return legend().getOrDefault(Integer.toString(levelIndex()), "");
  }

  private int levelCount() {
    if (rubric != null) {
      return rubric.getEnumConstants().length;
    }
    return legend().size();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Score<?> other
        && score == other.score
        && confidence == other.confidence
        && probabilities().equals(other.probabilities())
        && legend().equals(other.legend());
  }

  @Override
  public int hashCode() {
    return Objects.hash(score, confidence, probabilities(), legend());
  }

  @Override
  public String toString() {
    return "Score{score="
        + score
        + ", probabilities="
        + probabilities()
        + ", confidence="
        + confidence
        + ", legend="
        + legend()
        + "}";
  }

  /** Decodes a score with the rubric of the type it is declared as. */
  static final class Deserializer extends StdDeserializer<Score<?>>
      implements ContextualDeserializer {

    private final Class<?> rubric;

    Deserializer() {
      this(null);
    }

    private Deserializer(Class<?> rubric) {
      super(Score.class);
      this.rubric = rubric;
    }

    @Override
    public JsonDeserializer<?> createContextual(
        DeserializationContext ctxt, BeanProperty property) {
      JavaType type = property != null ? property.getType() : ctxt.getContextualType();
      Class<?> rubric = null;
      if (type != null && type.containedTypeCount() > 0) {
        Class<?> arg = type.containedType(0).getRawClass();
        if (arg.isEnum() && SystemOne.Level.class.isAssignableFrom(arg)) {
          rubric = arg;
        }
      }
      return new Deserializer(rubric);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Score<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = p.readValueAsTree();
      if (node == null || !node.isObject()) {
        return (Score<?>) ctxt.handleUnexpectedToken(Score.class, p);
      }
      return new Score(
          (Class) rubric,
          node.path("score").asDouble(0),
          doubles(node.get("probabilities")),
          node.path("confidence").asDouble(0),
          strings(node.get("legend")));
    }

    private static Map<String, Double> doubles(JsonNode node) {
      if (node == null || !node.isObject()) {
        return null;
      }
      Map<String, Double> out = new LinkedHashMap<>();
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        out.put(e.getKey(), e.getValue().asDouble());
      }
      return out;
    }

    private static Map<String, String> strings(JsonNode node) {
      if (node == null || !node.isObject()) {
        return null;
      }
      Map<String, String> out = new LinkedHashMap<>();
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        out.put(
            e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString());
      }
      return out;
    }
  }
}
