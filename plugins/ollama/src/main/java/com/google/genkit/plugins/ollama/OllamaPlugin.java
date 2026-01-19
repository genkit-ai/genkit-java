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

package com.google.genkit.plugins.ollama;

import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OllamaPlugin provides local Ollama model integrations for Genkit.
 *
 * <p>This plugin registers Ollama models running locally (or at a configured host) as Genkit
 * actions for text generation with support for streaming.
 *
 * <p>Ollama must be installed and running. Install from: https://ollama.ai
 */
public class OllamaPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(OllamaPlugin.class);

  private final OllamaPluginOptions options;

  /**
   * Creates an OllamaPlugin with default options. Uses localhost:11434 and registers common models.
   */
  public OllamaPlugin() {
    this(OllamaPluginOptions.builder().build());
  }

  /**
   * Creates an OllamaPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public OllamaPlugin(OllamaPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates an OllamaPlugin with the specified base URL.
   *
   * @param baseUrl the Ollama server URL (e.g., "http://localhost:11434")
   * @return a new OllamaPlugin
   */
  public static OllamaPlugin create(String baseUrl) {
    return new OllamaPlugin(OllamaPluginOptions.builder().baseUrl(baseUrl).build());
  }

  /**
   * Creates an OllamaPlugin with the specified models.
   *
   * @param models the models to register
   * @return a new OllamaPlugin
   */
  public static OllamaPlugin create(String... models) {
    return new OllamaPlugin(OllamaPluginOptions.builder().models(models).build());
  }

  /**
   * Creates an OllamaPlugin using default settings. Uses OLLAMA_HOST environment variable or
   * localhost:11434.
   *
   * @return a new OllamaPlugin
   */
  public static OllamaPlugin create() {
    return new OllamaPlugin();
  }

  @Override
  public String getName() {
    return "ollama";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register configured models
    for (String modelName : options.getModels()) {
      OllamaModel model = new OllamaModel(modelName, options);
      actions.add(model);
      logger.debug("Created Ollama model: {}", modelName);
    }

    logger.info(
        "Ollama plugin initialized with {} models at {}",
        options.getModels().size(),
        options.getBaseUrl());

    return actions;
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public OllamaPluginOptions getOptions() {
    return options;
  }
}
