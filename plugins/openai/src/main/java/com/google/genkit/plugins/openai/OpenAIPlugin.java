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

package com.google.genkit.plugins.openai;

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
 * OpenAIPlugin provides OpenAI model integrations for Genkit.
 *
 * <p>This plugin registers OpenAI models (GPT-4, GPT-3.5-turbo, etc.), embeddings
 * (text-embedding-ada-002, etc.), and image generation models (DALL-E, gpt-image-1) as Genkit
 * actions.
 */
public class OpenAIPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(OpenAIPlugin.class);

  /** Supported GPT models. */
  public static final List<String> SUPPORTED_MODELS =
      Arrays.asList(
          "gpt-5.2",
          "gpt-5.1",
          "gpt-5",
          "gpt-4o",
          "gpt-4o-mini",
          "gpt-4-turbo",
          "gpt-4-turbo-preview",
          "gpt-4",
          "gpt-4-32k",
          "gpt-3.5-turbo",
          "gpt-3.5-turbo-16k",
          "o1-preview",
          "o1-mini");

  /** Supported embedding models. */
  public static final List<String> SUPPORTED_EMBEDDING_MODELS =
      Arrays.asList("text-embedding-3-small", "text-embedding-3-large", "text-embedding-ada-002");

  /** Supported image generation models. */
  public static final List<String> SUPPORTED_IMAGE_MODELS =
      Arrays.asList("dall-e-3", "dall-e-2", "gpt-image-1");

  private final OpenAIPluginOptions legacyOptions;
  private final CompatOAIPluginOptions compatOptions;
  private final List<String> customModels = new ArrayList<>();
  private final List<String> customEmbeddingModels = new ArrayList<>();
  private final List<String> customImageModels = new ArrayList<>();

  /** Creates an OpenAIPlugin with default options. */
  public OpenAIPlugin() {
    this(OpenAIPluginOptions.builder().build());
  }

  /**
   * Creates an OpenAIPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public OpenAIPlugin(OpenAIPluginOptions options) {
    this.legacyOptions = options;
    this.compatOptions =
        CompatOAIPluginOptions.builder()
            .apiKey(options.getApiKey())
            .baseUrl(options.getBaseUrl())
            .organization(options.getOrganization())
            .timeout(options.getTimeout())
            .build();
  }

  /**
   * Creates an OpenAIPlugin with the specified API key.
   *
   * @param apiKey the OpenAI API key
   * @return a new OpenAIPlugin
   */
  public static OpenAIPlugin create(String apiKey) {
    return new OpenAIPlugin(OpenAIPluginOptions.builder().apiKey(apiKey).build());
  }

  /**
   * Creates an OpenAIPlugin using the OPENAI_API_KEY environment variable.
   *
   * @return a new OpenAIPlugin
   */
  public static OpenAIPlugin create() {
    return new OpenAIPlugin();
  }

  @Override
  public String getName() {
    return "openai";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register chat models using compat-oai
    for (String modelName : SUPPORTED_MODELS) {
      CompatOAIModel model =
          new CompatOAIModel(
              "openai/" + modelName, modelName, "OpenAI " + modelName, compatOptions);
      actions.add(model);
      logger.debug("Created OpenAI model: {}", modelName);
    }

    // Register custom chat models
    for (String modelName : customModels) {
      CompatOAIModel model =
          new CompatOAIModel(
              "openai/" + modelName, modelName, "OpenAI " + modelName, compatOptions);
      actions.add(model);
      logger.debug("Created custom OpenAI model: {}", modelName);
    }

    // Register embedding models (still using legacy implementation)
    for (String modelName : SUPPORTED_EMBEDDING_MODELS) {
      OpenAIEmbedder embedder = new OpenAIEmbedder(modelName, legacyOptions);
      actions.add(embedder);
      logger.debug("Created OpenAI embedder: {}", modelName);
    }

    // Register custom embedding models
    for (String modelName : customEmbeddingModels) {
      OpenAIEmbedder embedder = new OpenAIEmbedder(modelName, legacyOptions);
      actions.add(embedder);
      logger.debug("Created custom OpenAI embedder: {}", modelName);
    }

    // Register image generation models (still using legacy implementation)
    for (String modelName : SUPPORTED_IMAGE_MODELS) {
      OpenAIImageModel imageModel = new OpenAIImageModel(modelName, legacyOptions);
      actions.add(imageModel);
      logger.debug("Created OpenAI image model: {}", modelName);
    }

    // Register custom image generation models
    for (String modelName : customImageModels) {
      OpenAIImageModel imageModel = new OpenAIImageModel(modelName, legacyOptions);
      actions.add(imageModel);
      logger.debug("Created custom OpenAI image model: {}", modelName);
    }

    logger.info(
        "OpenAI plugin initialized with {} models, {} embedders, and {} image models",
        SUPPORTED_MODELS.size() + customModels.size(),
        SUPPORTED_EMBEDDING_MODELS.size() + customEmbeddingModels.size(),
        SUPPORTED_IMAGE_MODELS.size() + customImageModels.size());

    return actions;
  }

  /**
   * Registers a custom chat model name. Use this to work with models not in the default list. Call
   * this method before passing the plugin to Genkit.builder().
   *
   * @param modelName the model name (e.g., "gpt-5.3")
   * @return this plugin instance for method chaining
   */
  public OpenAIPlugin customModel(String modelName) {
    customModels.add(modelName);
    logger.debug("Added custom model to be registered: {}", modelName);
    return this;
  }

  /**
   * Registers a custom embedding model name. Use this to work with embedding models not in the
   * default list. Call this method before passing the plugin to Genkit.builder().
   *
   * @param modelName the embedding model name (e.g., "text-embedding-4-large")
   * @return this plugin instance for method chaining
   */
  public OpenAIPlugin customEmbeddingModel(String modelName) {
    customEmbeddingModels.add(modelName);
    logger.debug("Added custom embedding model to be registered: {}", modelName);
    return this;
  }

  /**
   * Registers a custom image generation model name. Use this to work with image models not in the
   * default list. Call this method before passing the plugin to Genkit.builder().
   *
   * @param modelName the image model name (e.g., "dall-e-4")
   * @return this plugin instance for method chaining
   */
  public OpenAIPlugin customImageModel(String modelName) {
    customImageModels.add(modelName);
    logger.debug("Added custom image model to be registered: {}", modelName);
    return this;
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public OpenAIPluginOptions getOptions() {
    return legacyOptions;
  }
}
