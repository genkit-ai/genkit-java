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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a JSON value against the subset of JSON schema the question schemas use: type,
 * properties, required, additionalProperties, oneOf with const, minimum, and maximum. Extension
 * keywords are ignored, as a validator does.
 */
final class SchemaValidator {

  private SchemaValidator() {}

  /** Returns the violations, empty when the value validates. */
  static List<String> validate(Object value, Map<String, Object> schema) {
    List<String> errors = new ArrayList<>();
    check(value, schema, "$", errors);
    return errors;
  }

  private static void check(Object value, Object rawSchema, String at, List<String> errors) {
    if (rawSchema instanceof Boolean b) {
      if (!b) {
        errors.add(at + ": not allowed");
      }
      return;
    }
    Map<String, Object> schema = Wire.map(rawSchema);
    Object type = schema.get("type");
    if (type instanceof String t && !hasType(value, t)) {
      errors.add(at + ": want " + t + ", got " + value);
      return;
    }
    if (schema.containsKey("const") && !schema.get("const").equals(value)) {
      errors.add(at + ": want const " + schema.get("const"));
    }
    if (value instanceof Number n) {
      if (schema.get("minimum") instanceof Number min && n.doubleValue() < min.doubleValue()) {
        errors.add(at + ": below minimum " + min);
      }
      if (schema.get("maximum") instanceof Number max && n.doubleValue() > max.doubleValue()) {
        errors.add(at + ": above maximum " + max);
      }
    }
    if (schema.get("oneOf") instanceof List<?> oneOf) {
      int matches = 0;
      for (Object option : oneOf) {
        if (validate(value, Wire.map(option)).isEmpty()) {
          matches++;
        }
      }
      if (matches != 1) {
        errors.add(at + ": matches " + matches + " of oneOf");
      }
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> props = Wire.map(schema.get("properties"));
      if (schema.get("required") instanceof List<?> required) {
        for (Object name : required) {
          if (!map.containsKey(name)) {
            errors.add(at + ": missing " + name);
          }
        }
      }
      for (Map.Entry<?, ?> e : map.entrySet()) {
        String key = String.valueOf(e.getKey());
        if (props.containsKey(key)) {
          check(e.getValue(), props.get(key), at + "." + key, errors);
        } else if (schema.containsKey("additionalProperties")) {
          check(e.getValue(), schema.get("additionalProperties"), at + "." + key, errors);
        }
      }
    }
  }

  private static boolean hasType(Object value, String type) {
    return switch (type) {
      case "object" -> value instanceof Map;
      case "array" -> value instanceof List;
      case "string" -> value instanceof String;
      case "number" -> value instanceof Number;
      case "integer" -> value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue());
      case "boolean" -> value instanceof Boolean;
      case "null" -> value == null;
      default -> true;
    };
  }
}
