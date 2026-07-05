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

package com.google.genkit.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SchemaUtils}. */
class SchemaUtilsTest {

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  @interface Nullable {}

  static class TripInput {
    public String destination;
    public int duration;
    @Nullable public String notes;
  }

  @Test
  @SuppressWarnings("unchecked")
  void inferSchemaMarksNonOptionalFieldsRequired() {
    // Regression test: the Dev UI only pre-fills a default input for properties listed in the
    // schema's "required" array, so non-optional POJO fields must be marked required.
    Map<String, Object> schema = SchemaUtils.inferSchema(TripInput.class);

    assertEquals("object", schema.get("type"));
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertTrue(properties.containsKey("destination"));
    assertTrue(properties.containsKey("duration"));

    List<String> required = (List<String>) schema.get("required");
    assertNotNull(required, "schema should declare a required array");
    assertTrue(required.contains("destination"), "non-optional field should be required");
    assertTrue(required.contains("duration"), "non-optional field should be required");
    assertFalse(required.contains("notes"), "@Nullable field should not be required");
  }

  @Test
  void inferSchemaReturnsNullForVoid() {
    assertNull(SchemaUtils.inferSchema(Void.class));
  }
}
