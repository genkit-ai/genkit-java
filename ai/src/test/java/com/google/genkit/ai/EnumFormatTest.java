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

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EnumFormat}. */
class EnumFormatTest {

  private static final Map<String, Object> SCHEMA =
      Map.of("type", "string", "enum", List.of("red", "green", "blue"));

  private static final Map<String, Object> NESTED_SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of("color", Map.of("type", "string", "enum", List.of("red", "green", "blue"))));

  private static Message model(Part... parts) {
    return new Message(Role.MODEL, new ArrayList<>(List.of(parts)));
  }

  @Test
  void requiresASchemaWithEnum() {
    assertThrows(
        GenkitException.class, () -> EnumFormat.parseMessage(model(Part.text("red")), null));
    assertThrows(
        GenkitException.class,
        () -> EnumFormat.parseMessage(model(Part.text("red")), Map.of("type", "string")));
  }

  @Test
  void outputConfigForEnums() {
    OutputConfig config = OutputConfig.enums("red", "green");
    assertEquals(OutputFormat.ENUM, config.getFormat());
    assertEquals("text/enum", config.getContentType());
    assertTrue(config.getConstrained());
    assertTrue(config.getInstructions().contains("red"));
    assertTrue(config.getInstructions().contains("green"));
    assertEquals(List.of("red", "green"), EnumFormat.values(config.getSchema()));
  }

  @Test
  void valuesFromTopLevelOrNestedEnum() {
    assertEquals(List.of("red", "green", "blue"), EnumFormat.values(SCHEMA));
    assertEquals(List.of("red", "green", "blue"), EnumFormat.values(NESTED_SCHEMA));
    assertEquals(
        List.of("a", "b"),
        EnumFormat.values(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("status", Map.of("type", "string", "enum", List.of("a", "b"))))));
    assertEquals(List.of(), EnumFormat.values(Map.of("type", "string")));
  }

  @Test
  void parseValidatesTheValue() {
    assertEquals("green", EnumFormat.parse("green", EnumFormat.values(SCHEMA)));
  }

  @Test
  void parseStripsQuotesAndWhitespace() {
    assertEquals("blue", EnumFormat.parse("  \"blue\"  \n", EnumFormat.values(SCHEMA)));
  }

  @Test
  void parseFailsOnAnInvalidValue() {
    GenkitException e =
        assertThrows(
            GenkitException.class, () -> EnumFormat.parse("yellow", EnumFormat.values(SCHEMA)));
    assertTrue(e.getMessage().contains("yellow"));
    assertEquals("INTERNAL", e.getErrorCode());
    assertThrows(GenkitException.class, () -> EnumFormat.parse("", EnumFormat.values(SCHEMA)));
  }

  @Test
  void parseMessageValidatesAndCleans() {
    Message got = EnumFormat.parseMessage(model(Part.text("  'green'\n")), SCHEMA);
    assertEquals("green", got.getContent().get(0).getText());
  }

  @Test
  void parseMessagePreservesNonTextParts() {
    Part toolPart = Part.toolRequest(new ToolRequest("test", null));
    Message got = EnumFormat.parseMessage(model(Part.text("red"), toolPart), SCHEMA);
    assertEquals(2, got.getContent().size());
    assertEquals("red", got.getContent().get(0).getText());
    assertSame(toolPart, got.getContent().get(1));
  }

  @Test
  void applyOnlyToTheEnumFormat() {
    ModelResponse resp =
        new ModelResponse(List.of(new Candidate(model(Part.text("\"blue\"")), FinishReason.STOP)));
    assertEquals("\"blue\"", EnumFormat.apply(OutputConfig.text(), resp).getText());
    assertEquals("\"blue\"", EnumFormat.apply(null, resp).getText());
    OutputConfig config = OutputConfig.enums("red", "green", "blue");
    assertEquals("blue", EnumFormat.apply(config, resp).getText());
  }
}
