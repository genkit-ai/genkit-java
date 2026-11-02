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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The System One protocol's names and its JSON: the schema keywords a question is encoded with, the
 * question kinds, and the encoding of what goes on the wire.
 */
final class Wire {

  /** Marks a property as a question and names its kind. */
  static final String KIND_KEYWORD = "x-systemone";

  /** Carries a score question's ordered level descriptions. */
  static final String LEVELS_KEYWORD = "x-levels";

  /** Carries a noul question's yes criteria. */
  static final String TRUE_KEYWORD = "x-true";

  /** Carries a noul question's no criteria. */
  static final String FALSE_KEYWORD = "x-false";

  /**
   * Carries a question's structured guidance, keyed as on the wire: by option, by level index, or
   * by "true" and "false".
   */
  static final String GUIDANCE_KEYWORD = "x-guidance";

  /** Carries a runtime question's instructions when they are structured rather than text. */
  static final String INSTRUCTIONS_KEYWORD = "x-instructions";

  static final String KIND_CHOICE = "choice";
  static final String KIND_SCORE = "score";
  static final String KIND_NOUL = "noul";

  /**
   * The wire encoding: maps go out with their keys sorted, and a whole number held as a double goes
   * out without a fraction, so the JSON is stable from one call to the next.
   */
  static final ObjectMapper MAPPER = newMapper();

  private Wire() {}

  private static ObjectMapper newMapper() {
    ObjectMapper mapper = JsonUtils.getObjectMapper().copy();
    mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    mapper.configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, false);
    mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    SimpleModule numbers = new SimpleModule("systemone-numbers");
    numbers.addSerializer(Double.class, new WholeDoubleSerializer());
    numbers.addSerializer(double.class, new WholeDoubleSerializer());
    mapper.registerModule(numbers);
    return mapper;
  }

  /** Encodes a value as wire JSON. */
  static String json(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("value is not JSON: " + e.getOriginalMessage(), e);
    }
  }

  /** Parses wire JSON into a tree. */
  static JsonNode tree(String json) throws JsonProcessingException {
    return MAPPER.readTree(json);
  }

  /**
   * A value as JSON decodes it: maps, lists, strings, numbers, booleans, and null, so a caller's
   * object reads the same as the JSON it encodes to.
   */
  static Object plain(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    return MAPPER.convertValue(value, Object.class);
  }

  /** A number as Go's encoder writes it: a whole double without a fraction. */
  static Number number(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
      return (long) value;
    }
    return value;
  }

  /** Reads a list of strings, or reports {@code null} when an element is not a string. */
  static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> strings = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof String s)) {
        return null;
      }
      strings.add(s);
    }
    return strings;
  }

  /** A map view of a value, or an empty map when it is not one. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return new LinkedHashMap<>();
  }

  /** An error with a Genkit status. */
  static GenkitException error(String status, String message) {
    return GenkitException.builder().message(message).errorCode(status).build();
  }

  /** An error with a Genkit status and a cause. */
  static GenkitException error(String status, String message, Throwable cause) {
    return GenkitException.builder().message(message).errorCode(status).cause(cause).build();
  }

  /** Writes a whole double without a fraction, as Go's encoder does. */
  private static final class WholeDoubleSerializer extends StdSerializer<Double> {
    WholeDoubleSerializer() {
      super(Double.class);
    }

    @Override
    public void serialize(Double value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      Number n = number(value);
      if (n instanceof Long l) {
        gen.writeNumber(l);
      } else {
        gen.writeNumber(value);
      }
    }
  }
}
