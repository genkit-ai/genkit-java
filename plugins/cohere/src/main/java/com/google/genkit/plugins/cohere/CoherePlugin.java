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

package com.google.genkit.plugins.cohere;

import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import com.google.genkit.plugins.compatoai.CompatOAIEmbedder;
import com.google.genkit.plugins.compatoai.CompatOAIModel;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CoherePlugin provides Cohere model integrations for Genkit.
 *
 * <p>This plugin registers Cohere models as Genkit actions using the OpenAI-compatible API.
 */
public class CoherePlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(CoherePlugin.class);

  /** Supported Cohere models. */
  public static final List<String> SUPPORTED_MODELS =
      Arrays.asList(
          // Command A family
          "command-a-plus-05-2026",
          "command-a-reasoning-08-2025",
          "command-a-vision-07-2025",
          "command-a-03-2025",
          // Command R family
          "command-r7b-12-2024",
          "command-r-08-2024",
          "command-r-plus-08-2024");

  /** Supported Cohere embedding models (reachable via the OpenAI-compatible endpoint). */
  public static final List<String> SUPPORTED_EMBEDDING_MODELS =
      Arrays.asList("embed-v4.0", "embed-multilingual-v3.0", "embed-english-v3.0");

  private final CompatOAIPluginOptions options;
  private final List<String> customModels = new ArrayList<>();
  private final List<String> customEmbeddingModels = new ArrayList<>();

  /** Creates a CoherePlugin with default options (using COHERE_API_KEY environment variable). */
  public CoherePlugin() {
    this(
        CompatOAIPluginOptions.builder()
            .apiKey(getApiKeyFromEnv())
            .baseUrl("https://api.cohere.ai/compatibility/v1")
            .build());
  }

  /**
   * Creates a CoherePlugin with the specified options.
   *
   * @param options the plugin options
   */
  public CoherePlugin(CompatOAIPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates a CoherePlugin with the specified API key.
   *
   * @param apiKey the Cohere API key
   * @return a new CoherePlugin
   */
  public static CoherePlugin create(String apiKey) {
    return new CoherePlugin(
        CompatOAIPluginOptions.builder()
            .apiKey(apiKey)
            .baseUrl("https://api.cohere.ai/compatibility/v1")
            .build());
  }

  /**
   * Creates a CoherePlugin using the COHERE_API_KEY environment variable.
   *
   * @return a new CoherePlugin
   */
  public static CoherePlugin create() {
    return new CoherePlugin();
  }

  private static String getApiKeyFromEnv() {
    String apiKey = System.getenv("COHERE_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalStateException(
          "Cohere API key is required. Set COHERE_API_KEY environment variable or provide it in options.");
    }
    return apiKey;
  }

  @Override
  public String getName() {
    return "cohere";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register Cohere models
    for (String modelName : SUPPORTED_MODELS) {
      CompatOAIModel model =
          new CompatOAIModel(
              "cohere/" + modelName,
              modelName, // API model name
              // without prefix
              "Cohere " + modelName,
              options);
      actions.add(model);
      logger.debug("Created Cohere model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      CompatOAIModel model =
          new CompatOAIModel("cohere/" + modelName, modelName, "Cohere " + modelName, options);
      actions.add(model);
      logger.debug("Created custom Cohere model: {}", modelName);
    }

    // Register Cohere embedding models
    for (String modelName : SUPPORTED_EMBEDDING_MODELS) {
      CompatOAIEmbedder embedder =
          new CompatOAIEmbedder("cohere/" + modelName, modelName, "Cohere " + modelName, options);
      actions.add(embedder);
      logger.debug("Created Cohere embedder: {}", modelName);
    }

    // Register custom embedding models added via customEmbeddingModel()
    for (String modelName : customEmbeddingModels) {
      CompatOAIEmbedder embedder =
          new CompatOAIEmbedder("cohere/" + modelName, modelName, "Cohere " + modelName, options);
      actions.add(embedder);
      logger.debug("Created custom Cohere embedder: {}", modelName);
    }

    logger.info(
        "Cohere plugin initialized with {} models and {} embedders",
        SUPPORTED_MODELS.size() + customModels.size(),
        SUPPORTED_EMBEDDING_MODELS.size() + customEmbeddingModels.size());

    return actions;
  }

  /**
   * Registers a custom model name. Use this to work with models not in the default list. Call this
   * method before passing the plugin to Genkit.builder().
   *
   * @param modelName the model name (e.g., "command-r-v2")
   * @return this plugin instance for method chaining
   */
  public CoherePlugin customModel(String modelName) {
    customModels.add(modelName);
    logger.debug("Added custom model to be registered: {}", modelName);
    return this;
  }

  /**
   * Registers a custom embedding model name. Use this to work with embedding models not in the
   * default list. Call this method before passing the plugin to Genkit.builder().
   *
   * @param modelName the embedding model name (e.g., "embed-english-light-v3.0")
   * @return this plugin instance for method chaining
   */
  public CoherePlugin customEmbeddingModel(String modelName) {
    customEmbeddingModels.add(modelName);
    logger.debug("Added custom embedding model to be registered: {}", modelName);
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
