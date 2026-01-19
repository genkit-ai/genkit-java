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

package com.google.genkit.plugins.evaluators.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptUtilsTest {

  @Test
  void testLoadPrompt() throws IOException {
    String prompt = PromptUtils.loadPrompt("faithfulness_nli.prompt");
    assertNotNull(prompt);
    assertFalse(prompt.isEmpty());
    assertTrue(prompt.contains("faithfulness"));
  }

  @Test
  void testLoadPromptNotFound() {
    assertThrows(IOException.class, () -> PromptUtils.loadPrompt("nonexistent.prompt"));
  }

  @Test
  void testRenderPrompt() {
    String template = "Hello {{name}}, you have {{count}} messages.";
    Map<String, String> vars = new HashMap<>();
    vars.put("name", "John");
    vars.put("count", "5");

    String result = PromptUtils.renderPrompt(template, vars);

    assertEquals("Hello John, you have 5 messages.", result);
  }

  @Test
  void testRenderPromptWithMissingVar() {
    String template = "Hello {{name}}, your score is {{score}}.";
    Map<String, String> vars = new HashMap<>();
    vars.put("name", "John");
    // score not provided

    String result = PromptUtils.renderPrompt(template, vars);

    // Missing variables should be replaced with empty string
    assertEquals("Hello John, your score is .", result);
  }

  @Test
  void testRenderPromptWithSpecialChars() {
    String template = "Query: {{query}}";
    Map<String, String> vars = new HashMap<>();
    vars.put("query", "What is $100 + 50%?");

    String result = PromptUtils.renderPrompt(template, vars);

    assertEquals("Query: What is $100 + 50%?", result);
  }

  @Test
  void testLoadAndRender() throws IOException {
    Map<String, String> vars = new HashMap<>();
    vars.put("question", "What is the capital of France?");
    vars.put("answer", "Paris is the capital of France.");

    String result = PromptUtils.loadAndRender("faithfulness_long_form.prompt", vars);

    assertNotNull(result);
    assertTrue(result.contains("What is the capital of France?"));
    assertTrue(result.contains("Paris is the capital of France."));
  }

  @Test
  void testStringifyString() {
    assertEquals("hello", PromptUtils.stringify("hello"));
  }

  @Test
  void testStringifyNull() {
    assertEquals("", PromptUtils.stringify(null));
  }

  @Test
  void testStringifyNumber() {
    assertEquals("42", PromptUtils.stringify(42));
    assertEquals("3.14", PromptUtils.stringify(3.14));
  }

  @Test
  void testStringifyMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("key", "value");

    String result = PromptUtils.stringify(map);

    assertTrue(result.contains("key"));
    assertTrue(result.contains("value"));
  }
}
