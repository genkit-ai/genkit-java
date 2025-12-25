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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for loading and rendering prompt templates.
 */
public class PromptUtils {

  private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

  private PromptUtils() {
    // Utility class
  }

  /**
   * Loads a prompt template from the classpath.
   *
   * @param promptName
   *            the name of the prompt file (without path prefix)
   * @return the prompt template content
   * @throws IOException
   *             if the prompt file cannot be read
   */
  public static String loadPrompt(String promptName) throws IOException {
    String resourcePath = "prompts/" + promptName;
    try (InputStream is = PromptUtils.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Prompt template not found: " + resourcePath);
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
    }
  }

  /**
   * Renders a prompt template by substituting variables.
   *
   * @param template
   *            the prompt template
   * @param variables
   *            the variables to substitute
   * @return the rendered prompt
   */
  public static String renderPrompt(String template, Map<String, String> variables) {
    StringBuffer result = new StringBuffer();
    Matcher matcher = TEMPLATE_PATTERN.matcher(template);
    while (matcher.find()) {
      String varName = matcher.group(1);
      String replacement = variables.getOrDefault(varName, "");
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Loads and renders a prompt template.
   *
   * @param promptName
   *            the name of the prompt file
   * @param variables
   *            the variables to substitute
   * @return the rendered prompt
   * @throws IOException
   *             if the prompt file cannot be read
   */
  public static String loadAndRender(String promptName, Map<String, String> variables) throws IOException {
    String template = loadPrompt(promptName);
    return renderPrompt(template, variables);
  }

  /**
   * Converts an object to a string representation for use in prompts.
   *
   * @param obj
   *            the object to convert
   * @return the string representation
   */
  public static String stringify(Object obj) {
    if (obj == null) {
      return "";
    }
    if (obj instanceof String) {
      return (String) obj;
    }
    try {
      return com.google.genkit.core.JsonUtils.toJson(obj);
    } catch (Exception e) {
      return obj.toString();
    }
  }
}
