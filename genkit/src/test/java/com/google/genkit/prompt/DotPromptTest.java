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

import com.google.genkit.ai.Prompt;
import com.google.genkit.core.GenkitException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for DotPrompt. */
class DotPromptTest {

  @Test
  void testParseVariant() throws GenkitException {
    String content = "---\nmodel: openai/gpt-4o\n---\nHello {{input}}";
    DotPrompt<Map<String, Object>> dotPrompt = DotPrompt.parse("recipe.robot", content);

    assertEquals("recipe.robot", dotPrompt.getName());
    assertEquals("robot", dotPrompt.getVariant());
    assertEquals("openai/gpt-4o", dotPrompt.getModel());
    assertEquals("Hello {{input}}", dotPrompt.getTemplate());
  }

  @Test
  void testParseWithoutVariant() throws GenkitException {
    String content = "---\nmodel: openai/gpt-4o\n---\nHello {{input}}";
    DotPrompt<Map<String, Object>> dotPrompt = DotPrompt.parse("recipe", content);

    assertEquals("recipe", dotPrompt.getName());
    assertNull(dotPrompt.getVariant());
  }

  @Test
  void testParseWithMultipleDots() throws GenkitException {
    String content = "---\nmodel: openai/gpt-4o\n---\nHello {{input}}";
    // Should use last dot for variant
    DotPrompt<Map<String, Object>> dotPrompt = DotPrompt.parse("my.awesome.recipe.robot", content);

    assertEquals("my.awesome.recipe.robot", dotPrompt.getName());
    assertEquals("robot", dotPrompt.getVariant());
  }

  @Test
  void testToPrompt() throws GenkitException {
    String content = "---\nmodel: openai/gpt-4o\n---\nHello {{input}}";
    DotPrompt<Map<String, Object>> dotPrompt = DotPrompt.parse("recipe.robot", content);

    @SuppressWarnings("unchecked")
    Prompt<Map<String, Object>> prompt =
        (Prompt<Map<String, Object>>) dotPrompt.toPrompt((Class) Map.class);

    assertEquals("recipe.robot", prompt.getName());
    assertEquals("robot", prompt.getVariant());

    @SuppressWarnings("unchecked")
    Map<String, Object> promptMetadata = (Map<String, Object>) prompt.getMetadata().get("prompt");
    assertEquals("recipe", promptMetadata.get("name"));
    assertEquals("robot", promptMetadata.get("variant"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testParseFrontmatterInputSchema() throws GenkitException {
    // Regression test for #184: the prompt runner needs an input schema derived from the .prompt
    // frontmatter so it can render an input box.
    String content =
        "---\n"
            + "model: openai/gpt-4o-mini\n"
            + "input:\n"
            + "  schema:\n"
            + "    code: string\n"
            + "    language: string\n"
            + "    analysisType?: string\n"
            + "---\n"
            + "Review this {{language}} code: {{code}}";
    DotPrompt<Map<String, Object>> dotPrompt = DotPrompt.parse("code-review", content);

    Map<String, Object> inputSchema = dotPrompt.getInputSchema();
    assertNotNull(inputSchema, "input schema should be parsed from frontmatter");
    assertEquals("object", inputSchema.get("type"));

    Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
    assertTrue(properties.containsKey("code"));
    assertTrue(properties.containsKey("language"));
    assertTrue(properties.containsKey("analysisType"));

    java.util.List<String> required = (java.util.List<String>) inputSchema.get("required");
    assertTrue(required.contains("code"));
    assertTrue(required.contains("language"));
    assertFalse(required.contains("analysisType"), "optional field must not be required");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testParseFrontmatterOutputSchema() throws GenkitException {
    String content =
        "---\n"
            + "model: openai/gpt-4o-mini\n"
            + "output:\n"
            + "  format: json\n"
            + "  schema:\n"
            + "    summary: string\n"
            + "    score: integer, from 1 to 10\n"
            + "    issues(array):\n"
            + "      severity: string\n"
            + "      line?: integer\n"
            + "---\n"
            + "Body";
    DotPrompt<Map<String, Object>> dotPrompt = DotPrompt.parse("code-review", content);

    Map<String, Object> outputSchema = dotPrompt.getOutputSchema();
    assertNotNull(outputSchema, "output schema should be parsed from frontmatter");
    Map<String, Object> properties = (Map<String, Object>) outputSchema.get("properties");
    assertEquals("integer", ((Map<String, Object>) properties.get("score")).get("type"));
    assertEquals(
        "from 1 to 10", ((Map<String, Object>) properties.get("score")).get("description"));
    // issues(array) -> array of objects
    Map<String, Object> issues = (Map<String, Object>) properties.get("issues");
    assertEquals("array", issues.get("type"));
    Map<String, Object> items = (Map<String, Object>) issues.get("items");
    assertEquals("object", items.get("type"));
  }

  @Test
  void testFrontmatterlessPromptStillParses() throws GenkitException {
    // Backwards compatibility: a prompt whose frontmatter only has model: must still load.
    DotPrompt<Map<String, Object>> dotPrompt =
        DotPrompt.parse("plain", "---\nmodel: openai/gpt-4o\n---\nHello");
    assertEquals("openai/gpt-4o", dotPrompt.getModel());
    assertNull(dotPrompt.getInputSchema());
  }
}
