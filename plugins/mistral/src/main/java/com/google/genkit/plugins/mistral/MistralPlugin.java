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

package com.google.genkit.plugins.mistral;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import com.google.genkit.plugins.compatoai.CompatOAIModel;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;

/**
 * MistralPlugin provides Mistral AI model integrations for Genkit.
 *
 * This plugin registers Mistral models as Genkit actions using the
 * OpenAI-compatible API.
 */
public class MistralPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(MistralPlugin.class);

  /**
   * Supported Mistral models.
   */
  public static final List<String> SUPPORTED_MODELS = Arrays.asList(
      // Flagship models
      "mistral-large-2512", "mistral-medium-2508", "mistral-small-2506",

      // Reasoning models
      "magistral-medium-2509", "magistral-small-2509",

      // Compact models
      "ministral-3b-2512", "ministral-8b-2512", "ministral-14b-2512",

      // Vision models
      "pixtral-large-2411",

      // Code models
      "codestral-2508", "devstral-2512",

      // Open source
      "open-mistral-nemo");

  private final CompatOAIPluginOptions options;
  private final List<String> customModels = new ArrayList<>();

  /**
   * Creates a MistralPlugin with default options (using MISTRAL_API_KEY
   * environment variable).
   */
  public MistralPlugin() {
    this(CompatOAIPluginOptions.builder().apiKey(getApiKeyFromEnv()).baseUrl("https://api.mistral.ai/v1").build());
  }

  /**
   * Creates a MistralPlugin with the specified options.
   *
   * @param options
   *            the plugin options
   */
  public MistralPlugin(CompatOAIPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates a MistralPlugin with the specified API key.
   *
   * @param apiKey
   *            the Mistral API key
   * @return a new MistralPlugin
   */
  public static MistralPlugin create(String apiKey) {
    return new MistralPlugin(
        CompatOAIPluginOptions.builder().apiKey(apiKey).baseUrl("https://api.mistral.ai/v1").build());
  }

  /**
   * Creates a MistralPlugin using the MISTRAL_API_KEY environment variable.
   *
   * @return a new MistralPlugin
   */
  public static MistralPlugin create() {
    return new MistralPlugin();
  }

  private static String getApiKeyFromEnv() {
    String apiKey = System.getenv("MISTRAL_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalStateException(
          "Mistral API key is required. Set MISTRAL_API_KEY environment variable or provide it in options.");
    }
    return apiKey;
  }

  @Override
  public String getName() {
    return "mistral";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register Mistral models
    for (String modelName : SUPPORTED_MODELS) {
      CompatOAIModel model = new CompatOAIModel("mistral/" + modelName, modelName, // API model name without
          // prefix
          "Mistral " + modelName, options);
      actions.add(model);
      logger.debug("Created Mistral model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      CompatOAIModel model = new CompatOAIModel("mistral/" + modelName, modelName,
          "Mistral " + modelName, options);
      actions.add(model);
      logger.debug("Created custom Mistral model: {}", modelName);
    }

    logger.info("Mistral plugin initialized with {} models", SUPPORTED_MODELS.size() + customModels.size());

    return actions;
  }

  /**
   * Registers a custom model name. Use this to work with models not in the
   * default list. Call this method before passing the plugin to Genkit.builder().
   * 
   * @param modelName
   *            the model name (e.g., "mistral-large-2601")
   * @return this plugin instance for method chaining
   */
  public MistralPlugin customModel(String modelName) {
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
