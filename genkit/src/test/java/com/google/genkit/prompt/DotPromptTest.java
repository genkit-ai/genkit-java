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
}
