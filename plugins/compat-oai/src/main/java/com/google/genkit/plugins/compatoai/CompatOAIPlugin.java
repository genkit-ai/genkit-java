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

package com.google.genkit.plugins.compatoai;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;

/**
 * CompatOAIPlugin provides a generic OpenAI-compatible API integration for
 * Genkit.
 * 
 * This plugin allows you to use any OpenAI-compatible API endpoint by
 * configuring the base URL and providing model definitions. Useful for: -
 * Custom or self-hosted OpenAI-compatible endpoints - New providers before
 * dedicated plugins are available - Testing and experimentation
 */
public class CompatOAIPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(CompatOAIPlugin.class);

  private final String pluginName;
  private final CompatOAIPluginOptions options;
  private final List<ModelDefinition> models;

  /**
   * Creates a CompatOAIPlugin with the specified configuration.
   *
   * @param pluginName
   *            the plugin name (e.g., "my-provider")
   * @param options
   *            the plugin options (API key, base URL, etc.)
   * @param models
   *            the list of models to register
   */
  public CompatOAIPlugin(String pluginName, CompatOAIPluginOptions options, List<ModelDefinition> models) {
    this.pluginName = pluginName;
    this.options = options;
    this.models = models;
  }

  /**
   * Creates a CompatOAIPlugin builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String getName() {
    return pluginName;
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    for (ModelDefinition modelDef : models) {
      CompatOAIModel model = new CompatOAIModel(modelDef.getFullName(), modelDef.getLabel(), options);
      actions.add(model);
      logger.debug("Created model: {}", modelDef.getFullName());
    }

    logger.info("{} plugin initialized with {} models", pluginName, models.size());

    return actions;
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public CompatOAIPluginOptions getOptions() {
    return options;
  }

  /**
   * Model definition for a compat-oai model.
   */
  public static class ModelDefinition {
    private final String modelId;
    private final String label;
    private final String prefix;

    /**
     * Creates a model definition.
     *
     * @param prefix
     *            the plugin prefix (e.g., "my-provider")
     * @param modelId
     *            the model ID (e.g., "my-model-v1")
     * @param label
     *            the display label (e.g., "My Provider my-model-v1")
     */
    public ModelDefinition(String prefix, String modelId, String label) {
      this.prefix = prefix;
      this.modelId = modelId;
      this.label = label;
    }

    /**
     * Creates a model definition with auto-generated label.
     *
     * @param prefix
     *            the plugin prefix
     * @param modelId
     *            the model ID
     */
    public ModelDefinition(String prefix, String modelId) {
      this(prefix, modelId, prefix + " " + modelId);
    }

    /**
     * Gets the full model name (prefix/modelId).
     *
     * @return the full name
     */
    public String getFullName() {
      return prefix + "/" + modelId;
    }

    /**
     * Gets the display label.
     *
     * @return the label
     */
    public String getLabel() {
      return label;
    }
  }

  /**
   * Builder for CompatOAIPlugin.
   */
  public static class Builder {
    private String pluginName;
    private String apiKey;
    private String baseUrl;
    private String organization;
    private int timeout = 60;
    private final List<ModelDefinition> models = new ArrayList<>();

    /**
     * Sets the plugin name.
     *
     * @param pluginName
     *            the plugin name (e.g., "my-provider")
     * @return this builder
     */
    public Builder pluginName(String pluginName) {
      this.pluginName = pluginName;
      return this;
    }

    /**
     * Sets the API key.
     *
     * @param apiKey
     *            the API key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the base URL for API requests.
     *
     * @param baseUrl
     *            the base URL (e.g., "https://api.example.com/v1")
     * @return this builder
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Sets the organization ID (optional).
     *
     * @param organization
     *            the organization ID
     * @return this builder
     */
    public Builder organization(String organization) {
      this.organization = organization;
      return this;
    }

    /**
     * Sets the request timeout in seconds.
     *
     * @param timeout
     *            the timeout
     * @return this builder
     */
    public Builder timeout(int timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Adds a model to register.
     *
     * @param modelId
     *            the model ID (e.g., "my-model-v1")
     * @return this builder
     */
    public Builder addModel(String modelId) {
      if (pluginName == null) {
        throw new IllegalStateException("pluginName must be set before adding models");
      }
      models.add(new ModelDefinition(pluginName, modelId));
      return this;
    }

    /**
     * Adds a model with a custom label.
     *
     * @param modelId
     *            the model ID
     * @param label
     *            the display label
     * @return this builder
     */
    public Builder addModel(String modelId, String label) {
      if (pluginName == null) {
        throw new IllegalStateException("pluginName must be set before adding models");
      }
      models.add(new ModelDefinition(pluginName, modelId, label));
      return this;
    }

    /**
     * Adds multiple models.
     *
     * @param modelIds
     *            the model IDs to add
     * @return this builder
     */
    public Builder addModels(String... modelIds) {
      for (String modelId : modelIds) {
        addModel(modelId);
      }
      return this;
    }

    /**
     * Builds the plugin.
     *
     * @return the plugin
     */
    public CompatOAIPlugin build() {
      if (pluginName == null || pluginName.isEmpty()) {
        throw new IllegalStateException("pluginName is required");
      }
      if (models.isEmpty()) {
        throw new IllegalStateException("At least one model must be added");
      }

      CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey(apiKey).baseUrl(baseUrl)
          .organization(organization).timeout(timeout).build();

      return new CompatOAIPlugin(pluginName, options, models);
    }
  }
}
