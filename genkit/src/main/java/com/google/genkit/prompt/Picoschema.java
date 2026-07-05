/*
 * Copyright 2025 Google LLC
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

package com.google.genkit.prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts Picoschema (the compact schema dialect used in {@code .prompt} frontmatter) into
 * standard JSON Schema.
 *
 * <p>Picoschema is a shorthand for describing an object's fields. Given a YAML block already parsed
 * into Java objects, this converter produces a JSON-Schema {@code Map} suitable for the Dev UI and
 * generic action runners.
 *
 * <p>Supported syntax:
 *
 * <ul>
 *   <li>Scalar fields: {@code fieldName: string} (types: string, boolean, null, number, integer,
 *       any).
 *   <li>Descriptions: {@code fieldName: string, a human description} — text after the first comma.
 *   <li>Optional fields: {@code fieldName?: string} — excluded from {@code required}.
 *   <li>Wrappers: {@code items(array): string}, {@code obj(object): {...}}, {@code color(enum):
 *       [RED, GREEN]}. A comma in the parenthetical adds a description, e.g. {@code tags(array,
 *       list of tags): string}.
 *   <li>Nested objects: a field whose value is a map is treated as a nested object.
 * </ul>
 *
 * <p>This is a pragmatic implementation covering the common cases; if a value already looks like a
 * full JSON Schema (has a JSON-schema {@code type} plus {@code properties}/{@code items}/{@code
 * $schema}) it is passed through unchanged.
 */
public final class Picoschema {

  private static final Set<String> SCALAR_TYPES =
      Set.of("string", "boolean", "null", "number", "integer", "any");

  private static final Set<String> JSON_SCHEMA_TYPES =
      Set.of("object", "array", "string", "boolean", "null", "number", "integer");

  private Picoschema() {}

  /**
   * Converts a parsed Picoschema definition into a JSON Schema map.
   *
   * @param pico the Picoschema value (map, string, or null)
   * @return the JSON schema as a map, or {@code null} if {@code pico} is null
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> convert(Object pico) {
    if (pico == null) {
      return null;
    }
    if (pico instanceof Map) {
      Map<String, Object> map = (Map<String, Object>) pico;
      if (looksLikeJsonSchema(map)) {
        return map;
      }
      return convertObject(map);
    }
    if (pico instanceof String) {
      return convertScalar((String) pico);
    }
    return new LinkedHashMap<>(Map.of("type", "object"));
  }

  private static boolean looksLikeJsonSchema(Map<String, Object> map) {
    Object type = map.get("type");
    boolean hasSchemaType = type instanceof String && JSON_SCHEMA_TYPES.contains(type);
    return map.containsKey("$schema")
        || (hasSchemaType && (map.containsKey("properties") || map.containsKey("items")));
  }

  private static Map<String, Object> convertObject(Map<String, Object> fields) {
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      ParsedKey key = parseKey(entry.getKey());
      properties.put(key.name, convertField(key, entry.getValue()));
      if (!key.optional) {
        required.add(key.name);
      }
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    schema.put("additionalProperties", false);
    return schema;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> convertField(ParsedKey key, Object value) {
    Map<String, Object> schema;
    switch (key.wrapper) {
      case "array":
        schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", convertItems(value));
        break;
      case "object":
        schema =
            (value instanceof Map)
                ? convertObject((Map<String, Object>) value)
                : new LinkedHashMap<>(Map.of("type", "object"));
        break;
      case "enum":
        schema = new LinkedHashMap<>();
        schema.put("enum", value instanceof List ? value : List.of());
        break;
      default:
        if (value instanceof Map) {
          schema = convertObject((Map<String, Object>) value);
        } else if (value instanceof String) {
          schema = convertScalar((String) value);
        } else {
          schema = new LinkedHashMap<>(Map.of("type", "object"));
        }
    }
    if (key.description != null && !schema.containsKey("description")) {
      schema.put("description", key.description);
    }
    return schema;
  }

  @SuppressWarnings("unchecked")
  private static Object convertItems(Object value) {
    if (value instanceof Map) {
      return convertObject((Map<String, Object>) value);
    }
    if (value instanceof String) {
      return convertScalar((String) value);
    }
    return new LinkedHashMap<>(Map.of("type", "object"));
  }

  private static Map<String, Object> convertScalar(String spec) {
    String type = spec.trim();
    String description = null;
    int comma = spec.indexOf(',');
    if (comma >= 0) {
      type = spec.substring(0, comma).trim();
      description = spec.substring(comma + 1).trim();
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    if ("any".equals(type)) {
      // 'any' imposes no type constraint.
    } else if (SCALAR_TYPES.contains(type)) {
      schema.put("type", type);
    } else {
      // Unknown scalar type: default to string rather than emitting an invalid schema.
      schema.put("type", "string");
    }
    if (description != null && !description.isEmpty()) {
      schema.put("description", description);
    }
    return schema;
  }

  /** Parses a Picoschema key such as {@code name}, {@code name?}, or {@code name(array, desc)}. */
  private static ParsedKey parseKey(String rawKey) {
    ParsedKey parsed = new ParsedKey();
    String key = rawKey.trim();

    int paren = key.indexOf('(');
    if (paren >= 0 && key.endsWith(")")) {
      String inner = key.substring(paren + 1, key.length() - 1).trim();
      key = key.substring(0, paren).trim();
      int comma = inner.indexOf(',');
      if (comma >= 0) {
        parsed.wrapper = inner.substring(0, comma).trim();
        parsed.description = inner.substring(comma + 1).trim();
      } else {
        parsed.wrapper = inner;
      }
    }

    if (key.endsWith("?")) {
      parsed.optional = true;
      key = key.substring(0, key.length() - 1).trim();
    }

    parsed.name = key;
    return parsed;
  }

  private static final class ParsedKey {
    private String name;
    private boolean optional = false;
    private String wrapper = "";
    private String description = null;
  }
}
