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
}
