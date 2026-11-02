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

package com.google.genkit.ai;

import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code enum} output format: the response is exactly one of the values listed by the output
 * schema's {@code enum}.
 *
 * <p>When a generate call asks for {@link OutputFormat#ENUM}, the final message's text is rewritten
 * to the bare value it names: quotes and surrounding whitespace are removed, and a text that is not
 * one of the values is an error. Parts other than text are kept after it.
 */
public final class EnumFormat {

  private EnumFormat() {}

  /**
   * Returns the values an enum output schema allows: the schema's own {@code enum}, or, for an
   * object schema, the {@code enum} of its first property that has one.
   *
   * @param schema the output schema
   * @return the values, or an empty list when the schema lists none
   */
  public static List<String> values(Map<String, Object> schema) {
    if (schema == null) {
      return List.of();
    }
    List<String> values = strings(schema.get("enum"));
    if (!values.isEmpty()) {
      return values;
    }
    if (schema.get("properties") instanceof Map<?, ?> properties) {
      for (Object property : properties.values()) {
        if (property instanceof Map<?, ?> map) {
          values = strings(map.get("enum"));
          if (!values.isEmpty()) {
            return values;
          }
        }
      }
    }
    return List.of();
  }

  /**
   * Returns the enum value a text names: the text without quotes and surrounding whitespace.
   *
   * @param text the model's text
   * @param values the allowed values
   * @return the value
   * @throws GenkitException if the text is empty or names none of the values
   */
  public static String parse(String text, List<String> values) throws GenkitException {
    if (text == null || text.isEmpty()) {
      throw invalid("message has no enum value, want one of: " + String.join(", ", values));
    }
    String trimmed = text.replace("'", "").replace("\"", "").strip();
    if (!values.contains(trimmed)) {
      throw invalid(
          "message " + trimmed + " not in list of valid enums: " + String.join(", ", values));
    }
    return trimmed;
  }

  /**
   * Rewrites a message's text as the single enum value it names, keeping any parts other than text
   * after it.
   *
   * @param message the model's message
   * @param schema the output schema
   * @return the message, rewritten in place
   * @throws GenkitException if the schema lists no values or the text names none of them
   */
  public static Message parseMessage(Message message, Map<String, Object> schema)
      throws GenkitException {
    List<String> values = values(schema);
    if (values.isEmpty()) {
      throw GenkitException.builder()
          .message("schema must be an object with an 'enum' property for enum format")
          .errorCode("INVALID_ARGUMENT")
          .build();
    }
    if (message == null || message.getContent() == null || message.getContent().isEmpty()) {
      throw invalid("message has no content");
    }
    StringBuilder text = new StringBuilder();
    List<Part> other = new ArrayList<>();
    for (Part part : message.getContent()) {
      if (part.isText()) {
        text.append(part.getText());
      } else {
        other.add(part);
      }
    }
    List<Part> content = new ArrayList<>();
    content.add(Part.text(parse(text.toString(), values)));
    content.addAll(other);
    message.setContent(content);
    return message;
  }

  /**
   * Applies the enum format to a response when its output config asks for it, rewriting the
   * response's message as {@link #parseMessage} does. Any other response is returned unchanged.
   *
   * @param output the output config of the request
   * @param response the model response
   * @return the response
   * @throws GenkitException if the response names none of the values
   */
  public static ModelResponse apply(OutputConfig output, ModelResponse response)
      throws GenkitException {
    if (output == null
        || output.getFormat() != OutputFormat.ENUM
        || response == null
        || response.getMessage() == null) {
      return response;
    }
    parseMessage(response.getMessage(), output.getSchema());
    return response;
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> values = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof String s) {
        values.add(s);
      }
    }
    return values;
  }

  private static GenkitException invalid(String message) {
    return GenkitException.builder().message(message).errorCode("INTERNAL").build();
  }
}
