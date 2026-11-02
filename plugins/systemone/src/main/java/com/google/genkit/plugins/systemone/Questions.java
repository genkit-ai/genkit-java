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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads the questions an output schema encodes, and renders the answers to them as the message
 * text.
 */
final class Questions {

  /** Names the one question an enum-format request asks. */
  static final String ENUM_QUESTION_ID = "choice";

  /**
   * The fields of a wire answer per question kind, which are the fields the answer types declare.
   * The answer schemas are closed, so a field the API or a gateway adds later is dropped rather
   * than failing every call; the untouched answer is still on {@link Info#answers()}.
   */
  private static final Map<String, List<String>> ANSWER_FIELDS =
      Map.of(
          Wire.KIND_CHOICE, List.of("choice", "probabilities", "confidence"),
          Wire.KIND_SCORE, List.of("score", "probabilities", "confidence", "legend"),
          Wire.KIND_NOUL, List.of("noul"));

  private Questions() {}

  /**
   * Reads the questions an output schema encodes: one per property, each marked with the
   * x-systemone keyword the answer types emit. A property without the marker is rejected, since the
   * model has no way to answer a shape it does not know. The preamble, the request's system text,
   * goes in front of every question's own instructions, followed by the schema's own description
   * when it has one.
   */
  static Map<String, WireQuestion> compile(Map<String, Object> schema, String preamble) {
    Map<String, Object> props = Wire.map(schema == null ? null : schema.get("properties"));
    if (props.isEmpty()) {
      throw invalid(
          "systemone: the output schema has no properties; each question is a field of the"
              + " output type");
    }
    Object description = schema.get("description");
    preamble = join(preamble, description instanceof String s ? s : "");
    Map<String, WireQuestion> questions = new TreeMap<>();
    for (String id : new TreeSet<>(props.keySet())) {
      Map<String, Object> prop = Wire.map(props.get(id));
      String kind = prop.get(Wire.KIND_KEYWORD) instanceof String s ? s : "";
      if (kind.isEmpty()) {
        throw invalid(
            "systemone: field \""
                + id
                + "\" is not a question; use a Choice, Score, or Noul field");
      }
      Object instructions = instructions(id, prop, preamble);
      WireQuestion q;
      switch (kind) {
        case Wire.KIND_CHOICE -> q = new WireQuestion(kind, instructions, choiceCriteria(id, prop));
        case Wire.KIND_SCORE -> {
          List<String> levels = levels(id, prop);
          q = new WireQuestion(kind, instructions, scoreCriteria(id, prop, levels), levels);
        }
        case Wire.KIND_NOUL -> q = new WireQuestion(kind, instructions, noulCriteria(id, prop));
        default ->
            throw invalid("systemone: question \"" + id + "\" has unknown type \"" + kind + "\"");
      }
      questions.put(id, q);
    }
    return questions;
  }

  /**
   * Reads a question's instructions: the structured value a runtime question carries on the
   * x-instructions keyword, or the property's description. The preamble goes in front: as a
   * paragraph of the text, as the first element of a value that encodes as a JSON array, or as the
   * first element beside any other structured value.
   */
  private static Object instructions(String id, Map<String, Object> prop, String preamble) {
    Object structured = prop.get(Wire.INSTRUCTIONS_KEYWORD);
    if (structured != null) {
      if (preamble.isEmpty()) {
        return structured;
      }
      List<Object> items = jsonArray(structured);
      List<Object> out = new ArrayList<>();
      out.add(preamble);
      if (items != null) {
        out.addAll(items);
      } else {
        out.add(structured);
      }
      return out;
    }
    String text = prop.get("description") instanceof String s ? s : "";
    if (text.isEmpty()) {
      throw invalid(
          "systemone: question \""
              + id
              + "\" has no instructions; set @JsonPropertyDescription on the field, or"
              + " instructions on a runtime question");
    }
    return join(preamble, text);
  }

  /** The elements of a value that encodes as a JSON array, or null when it does not. */
  private static List<Object> jsonArray(Object value) {
    if (value instanceof List<?> list) {
      return new ArrayList<>(list);
    }
    JsonNode node = Wire.MAPPER.valueToTree(value);
    if (node == null || !node.isArray()) {
      return null;
    }
    List<Object> items = new ArrayList<>();
    for (JsonNode item : node) {
      items.add(Wire.MAPPER.convertValue(item, Object.class));
    }
    return items;
  }

  /**
   * Reads the options back out of the oneOf a choice emits, in order, with the guidance of an
   * option in place of its string. An option with neither is described by its own name, since the
   * wire format takes a description per option and some gateways reject a null one.
   */
  private static WireQuestion.Options choiceCriteria(String id, Map<String, Object> prop) {
    Map<String, Object> choice = Wire.map(Wire.map(prop.get("properties")).get("choice"));
    List<?> oneOf = choice.get("oneOf") instanceof List<?> l ? l : List.of();
    if (oneOf.isEmpty()) {
      throw invalid("systemone: choice question \"" + id + "\" lists no options");
    }
    Map<String, Object> guidance = Wire.map(prop.get(Wire.GUIDANCE_KEYWORD));
    List<WireQuestion.Option> options = new ArrayList<>(oneOf.size());
    Set<String> seen = new HashSet<>();
    for (Object raw : oneOf) {
      Map<String, Object> option = Wire.map(raw);
      String key = option.get("const") instanceof String s ? s : "";
      if (key.isEmpty()) {
        throw invalid("systemone: choice question \"" + id + "\" has an option without a const");
      }
      if (!seen.add(key)) {
        throw invalid(
            "systemone: choice question \"" + id + "\" lists option \"" + key + "\" twice");
      }
      String description = option.get("description") instanceof String s ? s : "";
      Object value = withWhat(guidance.get(key), description);
      if ("".equals(value)) {
        value = key;
      }
      options.add(new WireQuestion.Option(key, value));
    }
    for (String key : new TreeSet<>(guidance.keySet())) {
      if (!seen.contains(key)) {
        throw invalid(
            "systemone: choice question \""
                + id
                + "\" has guidance for \""
                + key
                + "\", which is not one of its options");
      }
    }
    return new WireQuestion.Options(options);
  }

  /** Reads a score question's level strings. The API takes two to ten levels. */
  private static List<String> levels(String id, Map<String, Object> prop) {
    List<String> levels = Wire.stringList(prop.get(Wire.LEVELS_KEYWORD));
    if (levels == null) {
      throw invalid("systemone: score question \"" + id + "\" has a level that is not a string");
    }
    if (levels.size() < 2) {
      throw invalid("systemone: score question \"" + id + "\" needs at least two levels");
    }
    return levels;
  }

  /** The ordered levels with the guidance of a level in place of its string. */
  private static List<Object> scoreCriteria(
      String id, Map<String, Object> prop, List<String> levels) {
    List<Object> criteria = new ArrayList<>(levels);
    Map<String, Object> guidance = Wire.map(prop.get(Wire.GUIDANCE_KEYWORD));
    for (String key : new TreeSet<>(guidance.keySet())) {
      int i;
      try {
        i = Integer.parseInt(key);
      } catch (NumberFormatException e) {
        i = -1;
      }
      if (i < 0 || i >= levels.size()) {
        throw invalid(
            "systemone: score question \""
                + id
                + "\" has guidance for level "
                + key
                + ", which its rubric does not have");
      }
      criteria.set(i, withWhat(guidance.get(key), levels.get(i)));
    }
    return criteria;
  }

  /**
   * Reads the optional true and false criteria, with the guidance of a side in place of its string.
   * They are sent as a pair or not at all; no criteria leaves the field absent.
   */
  private static Map<String, Object> noulCriteria(String id, Map<String, Object> prop) {
    Map<String, Object> guidance = Wire.map(prop.get(Wire.GUIDANCE_KEYWORD));
    Map<String, Object> criteria = new TreeMap<>();
    for (String side : List.of("true", "false")) {
      String keyword = side.equals("true") ? Wire.TRUE_KEYWORD : Wire.FALSE_KEYWORD;
      String text = prop.get(keyword) instanceof String s ? s : "";
      Object value = withWhat(guidance.get(side), text);
      if (!"".equals(value)) {
        criteria.put(side, value);
      }
    }
    return switch (criteria.size()) {
      case 0 -> null;
      case 1 ->
          throw invalid(
              "systemone: noul question \""
                  + id
                  + "\" says what only one side means; give both yes and no, in criteria or"
                  + " guidance");
      default -> criteria;
    };
  }

  /**
   * A description as it goes on the wire: the guidance when there is some, the string otherwise. An
   * object with no "what" gets the string as its what, so guidance adds to the description rather
   * than replacing it; any other guidance is sent as it is.
   */
  static Object withWhat(Object guidance, String description) {
    if (guidance == null) {
      return description;
    }
    if (guidance instanceof Map<?, ?> g) {
      if (g.containsKey("what") || description.isEmpty()) {
        return g;
      }
      Map<String, Object> copy = new LinkedHashMap<>();
      g.forEach((k, v) -> copy.put(String.valueOf(k), v));
      copy.put("what", description);
      return copy;
    }
    return guidance;
  }

  /**
   * Turns an enum output schema into a single choice question, so the built-in enum format works
   * with no decision type. The instructions are the preamble followed by the schema's description;
   * the options keep the order the values were given in, each described by its own name.
   */
  static Map<String, WireQuestion> enumQuestion(Map<String, Object> schema, String preamble) {
    List<String> values = Wire.stringList(schema == null ? null : schema.get("enum"));
    if (values == null || values.contains("")) {
      throw invalid("systemone: enum output values must be non-empty strings");
    }
    if (values.isEmpty()) {
      throw invalid("systemone: the enum output schema lists no values");
    }
    List<WireQuestion.Option> options = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String v : values) {
      if (!seen.add(v)) {
        throw invalid("systemone: the enum output schema lists \"" + v + "\" twice");
      }
      options.add(new WireQuestion.Option(v, v));
    }
    Object description = schema.get("description");
    String instructions = join(preamble, description instanceof String s ? s : "");
    if (instructions.isEmpty()) {
      instructions = "Choose the option that best describes the state.";
    }
    Map<String, WireQuestion> questions = new TreeMap<>();
    questions.put(
        ENUM_QUESTION_ID,
        new WireQuestion(Wire.KIND_CHOICE, instructions, new WireQuestion.Options(options)));
    return questions;
  }

  /**
   * Renders the answers as the message text: for the enum format the chosen option itself,
   * otherwise a JSON object keyed by question ID whose values are the answers projected onto the
   * fields the output type declares. A score's legend is rebuilt from the rubric's strings, and a
   * score is clamped to the rubric, since float rounding can land a hair past the top level.
   */
  static String answersText(
      Map<String, Map<String, Object>> answers,
      Map<String, WireQuestion> questions,
      boolean enumFormat) {
    Map<String, Map<String, Object>> out = new TreeMap<>();
    for (String id : new TreeSet<>(questions.keySet())) {
      Map<String, Object> answer = answers.get(id);
      if (answer == null) {
        throw Wire.error("INTERNAL", "systemone: no answer for question \"" + id + "\"");
      }
      WireQuestion q = questions.get(id);
      Map<String, Object> projected = new TreeMap<>();
      for (String field : ANSWER_FIELDS.getOrDefault(q.type, List.of())) {
        if (answer.containsKey(field)) {
          projected.put(field, answer.get(field));
        }
      }
      if (q.labels != null) {
        projected.put("legend", q.legend());
        if (projected.get("score") instanceof Number score) {
          projected.put(
              "score", Math.min(Math.max(score.doubleValue(), 0), (double) q.labels.size() - 1));
        }
      }
      out.put(id, projected);
    }
    if (enumFormat) {
      Map<String, Object> answer = out.get(ENUM_QUESTION_ID);
      String choice = answer != null && answer.get("choice") instanceof String s ? s : "";
      if (choice.isEmpty()) {
        throw Wire.error("INTERNAL", "systemone: the enum answer names no option");
      }
      return choice;
    }
    try {
      return Wire.json(out);
    } catch (IllegalArgumentException e) {
      throw Wire.error("INTERNAL", "systemone: answers are not JSON: " + e.getMessage(), e);
    }
  }

  /** Puts the preamble in front of a question's own text as two paragraphs. */
  static String join(String preamble, String text) {
    if (preamble == null || preamble.isEmpty()) {
      return text == null ? "" : text;
    }
    if (text == null || text.isEmpty()) {
      return preamble;
    }
    return preamble + "\n\n" + text;
  }

  private static GenkitException invalid(String message) {
    return Wire.error("INVALID_ARGUMENT", message);
  }
}
