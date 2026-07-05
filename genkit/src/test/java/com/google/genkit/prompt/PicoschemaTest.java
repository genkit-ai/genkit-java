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

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link Picoschema} converter (#184). */
class PicoschemaTest {

  @Test
  void nullReturnsNull() {
    assertNull(Picoschema.convert(null));
  }

  @Test
  @SuppressWarnings("unchecked")
  void scalarFieldsBecomeTypedProperties() {
    Map<String, Object> pico = new LinkedHashMap<>();
    pico.put("code", "string");
    pico.put("count", "integer");

    Map<String, Object> schema = Picoschema.convert(pico);

    assertEquals("object", schema.get("type"));
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    assertEquals("string", ((Map<String, Object>) props.get("code")).get("type"));
    assertEquals("integer", ((Map<String, Object>) props.get("count")).get("type"));
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("code"));
    assertTrue(required.contains("count"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void optionalFieldExcludedFromRequired() {
    Map<String, Object> pico = new LinkedHashMap<>();
    pico.put("required", "string");
    pico.put("maybe?", "string");

    Map<String, Object> schema = Picoschema.convert(pico);
    List<String> required = (List<String>) schema.get("required");
    assertTrue(required.contains("required"));
    assertFalse(required.contains("maybe"));
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    assertTrue(props.containsKey("maybe"), "optional field must still be a property");
  }

  @Test
  @SuppressWarnings("unchecked")
  void scalarDescriptionAfterComma() {
    Map<String, Object> pico = new LinkedHashMap<>();
    pico.put("score", "integer, from 1 to 10");

    Map<String, Object> schema = Picoschema.convert(pico);
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    Map<String, Object> score = (Map<String, Object>) props.get("score");
    assertEquals("integer", score.get("type"));
    assertEquals("from 1 to 10", score.get("description"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void arrayWrapperOfObjects() {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("severity", "string");
    item.put("line?", "integer");
    Map<String, Object> pico = new LinkedHashMap<>();
    pico.put("issues(array)", item);

    Map<String, Object> schema = Picoschema.convert(pico);
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    Map<String, Object> issues = (Map<String, Object>) props.get("issues");
    assertEquals("array", issues.get("type"));
    Map<String, Object> items = (Map<String, Object>) issues.get("items");
    assertEquals("object", items.get("type"));
    Map<String, Object> itemProps = (Map<String, Object>) items.get("properties");
    assertTrue(itemProps.containsKey("severity"));
    assertTrue(itemProps.containsKey("line"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void arrayWrapperOfScalars() {
    Map<String, Object> pico = new LinkedHashMap<>();
    pico.put("tags(array)", "string");

    Map<String, Object> schema = Picoschema.convert(pico);
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    Map<String, Object> tags = (Map<String, Object>) props.get("tags");
    assertEquals("array", tags.get("type"));
    assertEquals("string", ((Map<String, Object>) tags.get("items")).get("type"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void nestedObjectFromMap() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("level", "string");
    nested.put("score", "integer");
    Map<String, Object> pico = new LinkedHashMap<>();
    pico.put("complexity", nested);

    Map<String, Object> schema = Picoschema.convert(pico);
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    Map<String, Object> complexity = (Map<String, Object>) props.get("complexity");
    assertEquals("object", complexity.get("type"));
    assertTrue(((Map<String, Object>) complexity.get("properties")).containsKey("level"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void enumWrapper() {
    Map<String, Object> pico = new LinkedHashMap<>();
    pico.put("color(enum)", List.of("RED", "GREEN", "BLUE"));

    Map<String, Object> schema = Picoschema.convert(pico);
    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
    Map<String, Object> color = (Map<String, Object>) props.get("color");
    assertEquals(List.of("RED", "GREEN", "BLUE"), color.get("enum"));
  }

  @Test
  void rawJsonSchemaPassthrough() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("type", "object");
    raw.put("properties", Map.of("x", Map.of("type", "string")));
    // Already a JSON schema: returned unchanged.
    assertSame(raw, Picoschema.convert(raw));
  }
}
