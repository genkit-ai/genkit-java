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

package com.google.genkit.plugins.anthropic;

import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AnthropicPlugin provides Anthropic Claude model integrations for Genkit.
 *
 * <p>This plugin registers Claude models (Claude 3.5, Claude 3, Claude 2) as Genkit actions for
 * text generation with support for streaming.
 */
public class AnthropicPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(AnthropicPlugin.class);

  /** Supported Claude models. */
  public static final List<String> SUPPORTED_MODELS =
      Arrays.asList(
          // Claude 4.5 family
          "claude-opus-4-5-20251101",
          "claude-sonnet-4-5-20250929",
          "claude-haiku-4-5-20251001",
          // Claude 4.x family
          "claude-opus-4-1-20250805",
          "claude-opus-4-20250514",
          "claude-sonnet-4-20250514",
          // Claude 3.x family
          "claude-3-7-sonnet-20250219",
          "claude-3-5-haiku-20241022",
          "claude-3-opus-20240229",
          "claude-3-haiku-20240307");

  private final AnthropicPluginOptions options;
  private final List<String> customModels = new ArrayList<>();

  /** Creates an AnthropicPlugin with default options. */
  public AnthropicPlugin() {
    this(AnthropicPluginOptions.builder().build());
  }

  /**
   * Creates an AnthropicPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public AnthropicPlugin(AnthropicPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates an AnthropicPlugin with the specified API key.
   *
   * @param apiKey the Anthropic API key
   * @return a new AnthropicPlugin
   */
  public static AnthropicPlugin create(String apiKey) {
    return new AnthropicPlugin(AnthropicPluginOptions.builder().apiKey(apiKey).build());
  }

  /**
   * Creates an AnthropicPlugin using the ANTHROPIC_API_KEY environment variable.
   *
   * @return a new AnthropicPlugin
   */
  public static AnthropicPlugin create() {
    return new AnthropicPlugin();
  }

  @Override
  public String getName() {
    return "anthropic";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register Claude models (still using native Anthropic API implementation)
    // Note: Anthropic API has significant differences from OpenAI-compatible APIs
    // including system message handling, content blocks, and streaming format
    for (String modelName : SUPPORTED_MODELS) {
      AnthropicModel model = new AnthropicModel(modelName, options);
      actions.add(model);
      logger.debug("Created Anthropic model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      AnthropicModel model = new AnthropicModel(modelName, options);
      actions.add(model);
      logger.debug("Created custom Anthropic model: {}", modelName);
    }

    logger.info(
        "Anthropic plugin initialized with {} models",
        SUPPORTED_MODELS.size() + customModels.size());

    return actions;
  }

  /**
   * Registers a custom model name. Use this to work with models not in the default list. Call this
   * method before passing the plugin to Genkit.builder().
   *
   * @param modelName the model name (e.g., "claude-4-opus-20260101")
   * @return this plugin instance for method chaining
   */
  public AnthropicPlugin customModel(String modelName) {
    customModels.add(modelName);
    logger.debug("Added custom model to be registered: {}", modelName);
    return this;
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public AnthropicPluginOptions getOptions() {
    return options;
  }
}
