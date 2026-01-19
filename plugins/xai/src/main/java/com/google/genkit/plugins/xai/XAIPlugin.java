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

package com.google.genkit.plugins.xai;

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
 * XAIPlugin provides XAI (x.ai / Grok) model integrations for Genkit.
 *
 * <p>This plugin registers Grok models as Genkit actions using the OpenAI-compatible API.
 */
public class XAIPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(XAIPlugin.class);

  /** Supported XAI models. */
  public static final List<String> SUPPORTED_MODELS =
      Arrays.asList(
          // Latest flagship models
          "grok-4",
          "grok-4-1-fast",

          // Reasoning variants
          "grok-4-1-fast-reasoning",
          "grok-4-1-fast-non-reasoning",
          "grok-4-fast-reasoning",
          "grok-4-fast-non-reasoning",

          // Code model
          "grok-code-fast-1",

          // Previous generation
          "grok-4-0709",
          "grok-3",
          "grok-3-mini");

  private final CompatOAIPluginOptions options;
  private final List<String> customModels = new ArrayList<>();

  /** Creates an XAIPlugin with default options (using XAI_API_KEY environment variable). */
  public XAIPlugin() {
    this(
        CompatOAIPluginOptions.builder()
            .apiKey(getApiKeyFromEnv())
            .baseUrl("https://api.x.ai/v1")
            .build());
  }

  /**
   * Creates an XAIPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public XAIPlugin(CompatOAIPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates an XAIPlugin with the specified API key.
   *
   * @param apiKey the XAI API key
   * @return a new XAIPlugin
   */
  public static XAIPlugin create(String apiKey) {
    return new XAIPlugin(
        CompatOAIPluginOptions.builder().apiKey(apiKey).baseUrl("https://api.x.ai/v1").build());
  }

  /**
   * Creates an XAIPlugin using the XAI_API_KEY environment variable.
   *
   * @return a new XAIPlugin
   */
  public static XAIPlugin create() {
    return new XAIPlugin();
  }

  private static String getApiKeyFromEnv() {
    String apiKey = System.getenv("XAI_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalStateException(
          "XAI API key is required. Set XAI_API_KEY environment variable or provide it in options.");
    }
    return apiKey;
  }

  @Override
  public String getName() {
    return "xai";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register Grok models
    for (String modelName : SUPPORTED_MODELS) {
      CompatOAIModel model =
          new CompatOAIModel(
              "xai/" + modelName,
              modelName, // API model name
              // without prefix
              "XAI " + modelName,
              options);
      actions.add(model);
      logger.debug("Created XAI model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      CompatOAIModel model =
          new CompatOAIModel("xai/" + modelName, modelName, "XAI " + modelName, options);
      actions.add(model);
      logger.debug("Created custom XAI model: {}", modelName);
    }

    logger.info(
        "XAI plugin initialized with {} models", SUPPORTED_MODELS.size() + customModels.size());

    return actions;
  }

  /**
   * Registers a custom model name. Use this to work with models not in the default list. Call this
   * method before passing the plugin to Genkit.builder().
   *
   * @param modelName the model name (e.g., "grok-5")
   * @return this plugin instance for method chaining
   */
  public XAIPlugin customModel(String modelName) {
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
