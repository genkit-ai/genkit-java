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

package com.google.genkit.plugins.deepseek;

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
 * DeepSeekPlugin provides DeepSeek model integrations for Genkit.
 *
 * This plugin registers DeepSeek models as Genkit actions using the
 * OpenAI-compatible API.
 */
public class DeepSeekPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(DeepSeekPlugin.class);

  /**
   * Supported DeepSeek models.
   */
  public static final List<String> SUPPORTED_MODELS = Arrays.asList("deepseek-chat", "deepseek-reasoner");

  private final CompatOAIPluginOptions options;
  private final List<String> customModels = new ArrayList<>();

  /**
   * Creates a DeepSeekPlugin with default options (using DEEPSEEK_API_KEY
   * environment variable).
   */
  public DeepSeekPlugin() {
    this(CompatOAIPluginOptions.builder().apiKey(getApiKeyFromEnv()).baseUrl("https://api.deepseek.com/v1")
        .build());
  }

  /**
   * Creates a DeepSeekPlugin with the specified options.
   *
   * @param options
   *            the plugin options
   */
  public DeepSeekPlugin(CompatOAIPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates a DeepSeekPlugin with the specified API key.
   *
   * @param apiKey
   *            the DeepSeek API key
   * @return a new DeepSeekPlugin
   */
  public static DeepSeekPlugin create(String apiKey) {
    return new DeepSeekPlugin(
        CompatOAIPluginOptions.builder().apiKey(apiKey).baseUrl("https://api.deepseek.com/v1").build());
  }

  /**
   * Creates a DeepSeekPlugin using the DEEPSEEK_API_KEY environment variable.
   *
   * @return a new DeepSeekPlugin
   */
  public static DeepSeekPlugin create() {
    return new DeepSeekPlugin();
  }

  private static String getApiKeyFromEnv() {
    String apiKey = System.getenv("DEEPSEEK_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalStateException(
          "DeepSeek API key is required. Set DEEPSEEK_API_KEY environment variable or provide it in options.");
    }
    return apiKey;
  }

  @Override
  public String getName() {
    return "deepseek";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register DeepSeek models
    for (String modelName : SUPPORTED_MODELS) {
      CompatOAIModel model = new CompatOAIModel("deepseek/" + modelName, modelName, // API model name without
          // prefix
          "DeepSeek " + modelName, options);
      actions.add(model);
      logger.debug("Created DeepSeek model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      CompatOAIModel model = new CompatOAIModel("deepseek/" + modelName, modelName, "DeepSeek " + modelName,
          options);
      actions.add(model);
      logger.debug("Created custom DeepSeek model: {}", modelName);
    }

    logger.info("DeepSeek plugin initialized with {} models", SUPPORTED_MODELS.size() + customModels.size());

    return actions;
  }

  /**
   * Registers a custom model name. Use this to work with models not in the
   * default list. Call this method before passing the plugin to Genkit.builder().
   * 
   * @param modelName
   *            the model name (e.g., "deepseek-v3")
   * @return this plugin instance for method chaining
   */
  public DeepSeekPlugin customModel(String modelName) {
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
