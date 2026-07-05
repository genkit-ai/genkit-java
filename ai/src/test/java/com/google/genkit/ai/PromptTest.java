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

package com.google.genkit.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.core.ActionType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for Prompt. */
class PromptTest {

  @Test
  void testPromptVariantAndMetadata() {
    Prompt<String> prompt =
        Prompt.<String>builder()
            .name("recipe.robot")
            .variant("robot")
            .model("openai/gpt-4o")
            .template("Tell me a recipe for {{input}}")
            .renderer((ctx, input) -> ModelRequest.builder().addUserMessage(input).build())
            .build();

    assertEquals("recipe.robot", prompt.getName());
    assertEquals("robot", prompt.getVariant());
    assertEquals(ActionType.EXECUTABLE_PROMPT, prompt.getType());

    Map<String, Object> metadata = prompt.getMetadata();
    assertEquals(ActionType.EXECUTABLE_PROMPT.getValue(), metadata.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> promptMetadata = (Map<String, Object>) metadata.get("prompt");
    assertNotNull(promptMetadata);
    assertEquals("recipe", promptMetadata.get("name"));
    assertEquals("robot", promptMetadata.get("variant"));
    assertEquals("openai/gpt-4o", promptMetadata.get("model"));
    assertEquals("Tell me a recipe for {{input}}", promptMetadata.get("template"));
  }

  @Test
  void testPromptWithoutVariant() {
    Prompt<String> prompt =
        Prompt.<String>builder()
            .name("recipe")
            .model("openai/gpt-4o")
            .template("Tell me a recipe for {{input}}")
            .renderer((ctx, input) -> ModelRequest.builder().addUserMessage(input).build())
            .build();

    assertEquals("recipe", prompt.getName());
    assertNull(prompt.getVariant());

    Map<String, Object> metadata = prompt.getMetadata();
    @SuppressWarnings("unchecked")
    Map<String, Object> promptMetadata = (Map<String, Object>) metadata.get("prompt");
    assertEquals("recipe", promptMetadata.get("name"));
    assertNull(promptMetadata.get("variant"));
  }

  @Test
  void testPromptVariantMismatch() {
    // If name doesn't end with variant, baseName should be the full name
    Prompt<String> prompt =
        Prompt.<String>builder()
            .name("recipe")
            .variant("robot")
            .model("openai/gpt-4o")
            .template("Tell me a recipe for {{input}}")
            .renderer((ctx, input) -> ModelRequest.builder().addUserMessage(input).build())
            .build();

    assertEquals("recipe", prompt.getName());
    assertEquals("robot", prompt.getVariant());

    Map<String, Object> metadata = prompt.getMetadata();
    @SuppressWarnings("unchecked")
    Map<String, Object> promptMetadata = (Map<String, Object>) metadata.get("prompt");
    assertEquals("recipe", promptMetadata.get("name"));
    assertEquals("robot", promptMetadata.get("variant"));
  }

  /** Sample input POJO used to verify schema inference from the Java input class. */
  static class ReviewInput {
    public String code;
    public String language;
  }

  @Test
  @SuppressWarnings("unchecked")
  void testInputSchemaInferredFromInputClass() {
    // Regression test for #184: when no explicit input schema is given, one is inferred from the
    // Java input class so the Dev UI can render an input box.
    Prompt<ReviewInput> prompt =
        Prompt.<ReviewInput>builder()
            .name("review")
            .model("openai/gpt-4o")
            .template("Review {{code}}")
            .inputClass(ReviewInput.class)
            .renderer((ctx, input) -> ModelRequest.builder().addUserMessage(input.code).build())
            .build();

    Map<String, Object> inputSchema = prompt.getInputSchema();
    assertNotNull(inputSchema, "input schema should be inferred from the input class");
    Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
    assertTrue(properties.containsKey("code"));
    assertTrue(properties.containsKey("language"));
    assertNotNull(prompt.getDesc().getInputSchema());

    // The inferred schema is also surfaced in metadata.prompt.input.schema.
    Map<String, Object> promptMetadata = (Map<String, Object>) prompt.getMetadata().get("prompt");
    assertNotNull(((Map<String, Object>) promptMetadata.get("input")).get("schema"));
  }

  @Test
  void testExplicitInputSchemaWinsOverInputClass() {
    Map<String, Object> explicit = Map.of("type", "object", "properties", Map.of());
    Prompt<ReviewInput> prompt =
        Prompt.<ReviewInput>builder()
            .name("review")
            .template("x")
            .inputSchema(explicit)
            .inputClass(ReviewInput.class)
            .renderer((ctx, input) -> ModelRequest.builder().addUserMessage("x").build())
            .build();

    assertSame(explicit, prompt.getInputSchema());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testOutputSchemaPropagated() {
    Map<String, Object> output = Map.of("type", "object", "properties", Map.of());
    Prompt<String> prompt =
        Prompt.<String>builder()
            .name("p")
            .template("x")
            .outputSchema(output)
            .renderer((ctx, input) -> ModelRequest.builder().addUserMessage("x").build())
            .build();

    assertSame(output, prompt.getOutputSchema());
    assertSame(output, prompt.getDesc().getOutputSchema());
    Map<String, Object> promptMetadata = (Map<String, Object>) prompt.getMetadata().get("prompt");
    assertNotNull(((Map<String, Object>) promptMetadata.get("output")).get("schema"));
  }
}
