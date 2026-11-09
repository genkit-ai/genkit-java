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

import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.SchemaProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Encodes questions as JSON schema: the typed questions, {@link Choice}, {@link Score}, and {@link
 * NoulOf}, from their type arguments, and the runtime questions from their values. The question set
 * rides on the output schema, each property one question, with the {@code x-} keywords carrying
 * what plain JSON schema has no keyword for.
 */
final class QuestionSchemas implements SchemaProvider {

  QuestionSchemas() {}

  @Override
  public Map<String, Object> jsonSchema(Type type) {
    Class<?> raw = rawClass(type);
    if (raw == Choice.class) {
      return choice(enumArgument(type, Choice.class, SystemOne.Option.class));
    }
    if (raw == Score.class) {
      return score(enumArgument(type, Score.class, SystemOne.Level.class));
    }
    if (NoulOf.class.isAssignableFrom(raw)) {
      return noul(yesNo(argument(type, NoulOf.class)));
    }
    return null;
  }

  /** The schema of {@code Choice<T>}: the constants of T, sorted by name, with their criteria. */
  static Map<String, Object> choice(Class<?> optionType) {
    List<ChoiceOption> options = new ArrayList<>();
    List<Object> constants = new ArrayList<>(Arrays.asList(optionType.getEnumConstants()));
    constants.sort(Comparator.comparing(QuestionSchemas::wireName));
    for (Object constant : constants) {
      SystemOne.Option option = (SystemOne.Option) constant;
      options.add(new ChoiceOption(wireName(constant), option.criteria(), option.guidance()));
    }
    return choiceSchema(options);
  }

  /** The schema of {@code Score<L>}: the constants of L, lowest first. */
  static Map<String, Object> score(Class<?> rubric) {
    List<String> levels = new ArrayList<>();
    Map<Integer, Object> guidance = new LinkedHashMap<>();
    Object[] constants = rubric.getEnumConstants();
    for (int i = 0; i < constants.length; i++) {
      SystemOne.Level level = (SystemOne.Level) constants[i];
      levels.add(level.description());
      guidance.put(i, level.guidance());
    }
    return scoreSchema(levels, guidance);
  }

  /** The schema of {@code NoulOf<C>}: C's criteria and guidance on each side. */
  static Map<String, Object> noul(SystemOne.YesNo criteria) {
    return noulSchema(criteria.yes(), criteria.no(), criteria.yesGuidance(), criteria.noGuidance());
  }

  /** The schema of a runtime question. */
  static Map<String, Object> schema(Question question) {
    if (question instanceof ChoiceQuestion q) {
      return withInstructions(choiceSchema(q.options()), q.instructions());
    }
    if (question instanceof ScoreQuestion q) {
      return withInstructions(scoreSchema(q.levels(), q.guidance()), q.instructions());
    }
    NoulQuestion q = (NoulQuestion) question;
    return withInstructions(
        noulSchema(q.yes(), q.no(), q.yesGuidance(), q.noGuidance()), q.instructions());
  }

  /** Encodes a choice question's options, in order, with the guidance any of them carries. */
  static Map<String, Object> choiceSchema(List<ChoiceOption> options) {
    List<Object> oneOf = new ArrayList<>(options.size());
    Map<String, Object> guidance = new LinkedHashMap<>();
    for (ChoiceOption option : options) {
      Map<String, Object> constant = new LinkedHashMap<>();
      constant.put("const", option.name());
      if (option.criteria() != null && !option.criteria().isEmpty()) {
        constant.put("description", option.criteria());
      }
      oneOf.add(constant);
      if (option.guidance() != null) {
        guidance.put(option.name(), option.guidance());
      }
    }
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("type", "string");
    choice.put("oneOf", oneOf);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("choice", choice);
    props.put("probabilities", probabilitiesSchema());
    props.put("confidence", Map.of("type", "number"));
    Map<String, Object> s = answerSchema(Wire.KIND_CHOICE, props, "choice");
    setGuidance(s, guidance, Function.identity());
    return s;
  }

  /** Encodes a noul question with its optional criteria and guidance for each side. */
  static Map<String, Object> noulSchema(
      String yes, String no, Object yesGuidance, Object noGuidance) {
    Map<String, Object> noul = new LinkedHashMap<>();
    noul.put("type", "number");
    noul.put("minimum", 0);
    noul.put("maximum", 1);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("noul", noul);
    Map<String, Object> s = answerSchema(Wire.KIND_NOUL, props, "noul");
    yes = yes == null ? "" : yes;
    no = no == null ? "" : no;
    if (!yes.isEmpty() || !no.isEmpty()) {
      s.put(Wire.TRUE_KEYWORD, yes);
      s.put(Wire.FALSE_KEYWORD, no);
    }
    Map<Boolean, Object> guidance = new LinkedHashMap<>();
    guidance.put(true, yesGuidance);
    guidance.put(false, noGuidance);
    setGuidance(s, guidance, String::valueOf);
    return s;
  }

  /**
   * Encodes a score question's ordered levels and the guidance keyed by level index. The level
   * descriptions ride on the x-levels keyword, since a fractional score cannot be a oneOf of
   * levels.
   */
  static Map<String, Object> scoreSchema(List<String> levels, Map<Integer, Object> guidance) {
    Map<String, Object> score = new LinkedHashMap<>();
    score.put("type", "number");
    score.put("minimum", 0);
    score.put("maximum", Math.max(levels.size() - 1, 0));
    Map<String, Object> legend = new LinkedHashMap<>();
    legend.put("type", "object");
    legend.put("additionalProperties", Map.of("type", "string"));
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("score", score);
    props.put("probabilities", probabilitiesSchema());
    props.put("confidence", Map.of("type", "number"));
    props.put("legend", legend);
    Map<String, Object> s = answerSchema(Wire.KIND_SCORE, props, "score");
    s.put(Wire.LEVELS_KEYWORD, new ArrayList<>(levels));
    setGuidance(s, guidance, String::valueOf);
    return s;
  }

  /**
   * Puts a runtime question's instructions on its schema: text as the description, as a field's
   * description gives it, and any other value on the x-instructions keyword.
   */
  static Map<String, Object> withInstructions(Map<String, Object> s, Object instructions) {
    if (instructions instanceof String text) {
      s.put("description", text);
    } else if (instructions != null) {
      s.put(Wire.INSTRUCTIONS_KEYWORD, Wire.plain(instructions));
    }
    return s;
  }

  private static Map<String, Object> probabilitiesSchema() {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("type", "object");
    s.put("additionalProperties", Map.of("type", "number"));
    return s;
  }

  /** Puts the non-null guidance values on the schema's x-guidance keyword, keyed as on the wire. */
  private static <K> void setGuidance(
      Map<String, Object> s, Map<K, Object> guidance, Function<K, String> key) {
    Map<String, Object> wire = new LinkedHashMap<>();
    if (guidance != null) {
      guidance.forEach((k, v) -> {
        if (v != null) {
          wire.put(key.apply(k), Wire.plain(v));
        }
      });
    }
    if (!wire.isEmpty()) {
      s.put(Wire.GUIDANCE_KEYWORD, wire);
    }
  }

  /**
   * Completes the object schema shared by the answer types. It is closed: the model projects a wire
   * answer onto exactly these fields.
   */
  private static Map<String, Object> answerSchema(
      String kind, Map<String, Object> props, String required) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("type", "object");
    s.put("properties", props);
    s.put("required", new ArrayList<>(List.of(required)));
    s.put("additionalProperties", false);
    s.put(Wire.KIND_KEYWORD, kind);
    return s;
  }

  /** The name an option goes by on the wire: the name Jackson gives its enum constant. */
  static String wireName(Object constant) {
    return JsonUtils.getObjectMapper().convertValue(constant, String.class);
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> c) {
      return c;
    }
    if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
      return c;
    }
    throw new IllegalArgumentException("systemone: cannot describe " + type);
  }

  /** The class of a generic type's first type argument, as the type is used. */
  private static Class<?> argument(Type type, Class<?> generic) {
    Type current = type;
    while (current != null) {
      if (current instanceof ParameterizedType p && p.getRawType() == generic) {
        Type arg = p.getActualTypeArguments()[0];
        if (arg instanceof Class<?> c) {
          return c;
        }
        if (arg instanceof ParameterizedType pa && pa.getRawType() instanceof Class<?> c) {
          return c;
        }
        break;
      }
      Class<?> raw = rawClass(current);
      if (raw == generic || raw == Object.class) {
        break;
      }
      current = raw.getGenericSuperclass();
    }
    throw new IllegalArgumentException(
        "systemone: "
            + generic.getSimpleName()
            + " needs its type argument, as in "
            + generic.getSimpleName()
            + "<MyType>");
  }

  private static Class<?> enumArgument(Type type, Class<?> generic, Class<?> bound) {
    Class<?> arg = argument(type, generic);
    if (!arg.isEnum() || !bound.isAssignableFrom(arg)) {
      throw new IllegalArgumentException(
          "systemone: the type argument of "
              + generic.getSimpleName()
              + " must be an enum that implements SystemOne."
              + bound.getSimpleName());
    }
    return arg;
  }

  private static SystemOne.YesNo yesNo(Class<?> type) {
    if (!SystemOne.YesNo.class.isAssignableFrom(type)) {
      throw new IllegalArgumentException(
          "systemone: the type argument of NoulOf must implement SystemOne.YesNo");
    }
    try {
      java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return (SystemOne.YesNo) constructor.newInstance();
    } catch (NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      throw new IllegalArgumentException(
          "systemone: " + type.getName() + " needs a no-argument constructor", e);
    }
  }
}
