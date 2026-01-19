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

package com.google.genkit.plugins.groq;

import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import com.google.genkit.plugins.compatoai.CompatOAIModel;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GroqPlugin provides Groq model integrations for Genkit.
 *
 * <p>This plugin registers Groq models as Genkit actions using the OpenAI-compatible API.
 */
public class GroqPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(GroqPlugin.class);

  /** Supported Groq production models. */
  public static final List<String> SUPPORTED_MODELS =
      Arrays.asList(
          // Meta Llama models
          "llama-3.1-8b-instant",
          "llama-3.3-70b-versatile",
          "meta-llama/llama-guard-4-12b",

          // OpenAI GPT-OSS models
          "openai/gpt-oss-120b",
          "openai/gpt-oss-20b");

  private final CompatOAIPluginOptions options;
  private final List<String> customModels = new ArrayList<>();

  /** Creates a GroqPlugin with default options (using GROQ_API_KEY environment variable). */
  public GroqPlugin() {
    this(
        CompatOAIPluginOptions.builder()
            .apiKey(getApiKeyFromEnv())
            .baseUrl("https://api.groq.com/openai/v1")
            .build());
  }

  /**
   * Creates a GroqPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public GroqPlugin(CompatOAIPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates a GroqPlugin with the specified API key.
   *
   * @param apiKey the Groq API key
   * @return a new GroqPlugin
   */
  public static GroqPlugin create(String apiKey) {
    return new GroqPlugin(
        CompatOAIPluginOptions.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.groq.com/openai/v1")
            .build());
  }

  /**
   * Creates a GroqPlugin using the GROQ_API_KEY environment variable.
   *
   * @return a new GroqPlugin
   */
  public static GroqPlugin create() {
    return new GroqPlugin();
  }

  private static String getApiKeyFromEnv() {
    String apiKey = System.getenv("GROQ_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalStateException(
          "Groq API key is required. Set GROQ_API_KEY environment variable or provide it in options.");
    }
    return apiKey;
  }

  @Override
  public String getName() {
    return "groq";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register Groq models
    for (String modelName : SUPPORTED_MODELS) {
      CompatOAIModel model =
          new CompatOAIModel(
              "groq/" + modelName,
              modelName, // API model name
              // without prefix
              "Groq " + modelName,
              options);
      actions.add(model);
      logger.debug("Created Groq model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      CompatOAIModel model =
          new CompatOAIModel("groq/" + modelName, modelName, "Groq " + modelName, options);
      actions.add(model);
      logger.debug("Created custom Groq model: {}", modelName);
    }

    logger.info(
        "Groq plugin initialized with {} models", SUPPORTED_MODELS.size() + customModels.size());

    return actions;
  }

  /**
   * Registers a custom model name. Use this to work with models not in the default list. Call this
   * method before passing the plugin to Genkit.builder().
   *
   * @param modelName the model name (e.g., "llama-4-90b-preview")
   * @return this plugin instance for method chaining
   */
  public GroqPlugin customModel(String modelName) {
    customModels.add(modelName);
    logger.debug("Added custom model to be registered: {}", modelName);
    return this;
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public CompatOAIPluginOptions getOptions() {
    return options;
  }
}
