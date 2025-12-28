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

package com.google.genkit.plugins.azurefoundry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.core.credential.AccessToken;
import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import com.google.genkit.plugins.compatoai.CompatOAIModel;
import com.google.genkit.plugins.compatoai.CompatOAIPluginOptions;

/**
 * AzureFoundryPlugin provides Azure AI Foundry model integrations for Genkit.
 *
 * This plugin uses Azure's OpenAI-compatible inference endpoints to support
 * various models deployed in Azure AI Foundry, including GPT-4, GPT-3.5, Llama,
 * Mistral, and more.
 */
public class AzureFoundryPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(AzureFoundryPlugin.class);

  /**
   * Azure AI Foundry supported models. Model availability varies by region and
   * Azure subscription. See:
   * https://learn.microsoft.com/en-us/azure/ai-foundry/agents/concepts/model-region-support
   */
  public static final List<String> SUPPORTED_MODELS = Arrays.asList(
      // Azure OpenAI models (Global Standard & Provisioned)
      "gpt-5", "gpt-5-mini", "gpt-5-turbo", "o1", "o3-mini-high", "o3-mini-medium", "o3-mini-low", "gpt-4o",
      "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-35-turbo",
      // Azure models sold directly by Azure
      "mai-ds-r1", "grok-4", "grok-4-fast-reasoning", "grok-4-fast-non-reasoning", "grok-3", "grok-3-mini",
      "llama-3-3-70b-instruct", "llama-4-maverick-17b-128e-instruct-fp8", "deepseek-v3-0324", "deepseek-v3-1",
      "deepseek-r1-0528", "gpt-oss-120b",
      // Partner and community models
      "claude-opus-4-5", "claude-opus-4-1", "claude-sonnet-4-5", "claude-haiku-4-5");

  private final AzureFoundryPluginOptions options;
  private final CompatOAIPluginOptions compatOptions;
  private final List<String> customModels = new ArrayList<>();

  /**
   * Creates an AzureFoundryPlugin with the specified options.
   *
   * @param options
   *            the plugin options
   */
  public AzureFoundryPlugin(AzureFoundryPluginOptions options) {
    this.options = options;

    // Convert Azure Foundry options to CompatOAI options
    CompatOAIPluginOptions.Builder compatBuilder = CompatOAIPluginOptions.builder().baseUrl(buildBaseUrl(options));

    // Handle authentication
    if (options.getApiKey() != null) {
      compatBuilder.apiKey(options.getApiKey());
    } else if (options.getCredential() != null) {
      // Get an access token from the Azure credential
      try {
        AccessToken token = options.getCredential().getToken(new com.azure.core.credential.TokenRequestContext()
            .addScopes("https://cognitiveservices.azure.com/.default")).block();
        if (token != null) {
          compatBuilder.apiKey(token.getToken());
        }
      } catch (Exception e) {
        logger.warn("Failed to get Azure access token, will retry on first API call", e);
        compatBuilder.apiKey("placeholder"); // Will be replaced on first call
      }
    }

    this.compatOptions = compatBuilder.build();
  }

  /**
   * Creates an AzureFoundryPlugin with the specified endpoint and API key.
   *
   * @param endpoint
   *            the Azure AI Foundry endpoint
   * @param apiKey
   *            the API key
   * @return a new AzureFoundryPlugin
   */
  public static AzureFoundryPlugin create(String endpoint, String apiKey) {
    return new AzureFoundryPlugin(AzureFoundryPluginOptions.builder().endpoint(endpoint).apiKey(apiKey).build());
  }

  /**
   * Creates an AzureFoundryPlugin using environment variables or default Azure
   * credentials.
   *
   * @return a new AzureFoundryPlugin
   */
  public static AzureFoundryPlugin create() {
    return new AzureFoundryPlugin(AzureFoundryPluginOptions.builder().build());
  }

  private String buildBaseUrl(AzureFoundryPluginOptions options) {
    StringBuilder url = new StringBuilder(options.getEndpoint());
    if (!options.getEndpoint().endsWith("/")) {
      url.append("/");
    }

    // Detect endpoint type:
    // - Azure OpenAI Service: *.openai.azure.com or *.cognitiveservices.azure.com
    // Uses: /openai/deployments/{deployment}/chat/completions
    // - Azure AI Foundry: *.models.ai.azure.com
    // Uses: /inference/v1/chat/completions
    boolean isAzureOpenAI = options.getEndpoint().contains("openai.azure.com")
        || options.getEndpoint().contains("cognitiveservices.azure.com");

    if (!options.getEndpoint().contains("/inference") && !options.getEndpoint().contains("/openai")) {
      if (isAzureOpenAI) {
        // Azure OpenAI Service uses /openai/deployments/{deployment}/ path
        // The deployment name will be added by the model, so we just set the base
        url.append("openai/deployments");
      } else {
        // Azure AI Foundry uses inference/v1 path for OpenAI-compatible endpoints
        url.append("inference/v1");
      }
    }

    // Add API version as query parameter
    if (options.getApiVersion() != null) {
      url.append("?api-version=").append(options.getApiVersion());
    }

    return url.toString();
  }

  /**
   * Builds CompatOAI options for Azure OpenAI deployments. Azure OpenAI requires
   * the deployment name in the URL path.
   */
  private CompatOAIPluginOptions buildAzureOpenAIOptions(String deploymentName) {
    StringBuilder url = new StringBuilder(options.getEndpoint());
    if (!url.toString().endsWith("/")) {
      url.append("/");
    }

    // Azure OpenAI path: /openai/deployments/{deployment-id}
    if (!options.getEndpoint().contains("/openai")) {
      url.append("openai/deployments/").append(deploymentName);
    }

    String baseUrl = url.toString();
    logger.info("Azure OpenAI base URL for deployment '{}': {}", deploymentName, baseUrl);

    // Build new CompatOAI options with deployment-specific URL and query parameters
    CompatOAIPluginOptions.Builder builder = CompatOAIPluginOptions.builder().baseUrl(baseUrl);

    // Add API version as query parameter
    if (options.getApiVersion() != null) {
      builder.queryParams(java.util.Map.of("api-version", options.getApiVersion()));
    }

    // Copy authentication from original options
    if (compatOptions.getApiKey() != null) {
      builder.apiKey(compatOptions.getApiKey());
    }

    return builder.build();
  }

  @Override
  public String getName() {
    return "azure-foundry";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Check if this is an Azure OpenAI endpoint
    boolean isAzureOpenAI = options.getEndpoint().contains("openai.azure.com")
        || options.getEndpoint().contains("cognitiveservices.azure.com");

    logger.info("Initializing Azure Foundry plugin");
    logger.info("Endpoint: {}", options.getEndpoint());
    logger.info("Detected as Azure OpenAI Service: {}", isAzureOpenAI);
    logger.info("API Version: {}", options.getApiVersion());

    // Register Azure AI Foundry models
    for (String modelName : SUPPORTED_MODELS) {
      String fullName = "azure-foundry/" + modelName;
      if (isAzureOpenAI) {
        CompatOAIPluginOptions modelOptions = buildAzureOpenAIOptions(modelName);
        CompatOAIModel model = new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName,
            modelOptions);
        actions.add(model);
      } else {
        CompatOAIModel model = new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName,
            compatOptions);
        actions.add(model);
      }
      logger.debug("Created Azure Foundry model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      String fullName = "azure-foundry/" + modelName;
      if (isAzureOpenAI) {
        CompatOAIPluginOptions modelOptions = buildAzureOpenAIOptions(modelName);
        CompatOAIModel model = new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName,
            modelOptions);
        actions.add(model);
      } else {
        CompatOAIModel model = new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName,
            compatOptions);
        actions.add(model);
      }
      logger.debug("Created custom Azure Foundry model: {}", modelName);
    }

    logger.info("Azure Foundry plugin initialized with {} models", SUPPORTED_MODELS.size() + customModels.size());

    return actions;
  }

  /**
   * Registers a custom model or deployment name. Use this to work with custom
   * deployments not in the default list. Call this method before passing the
   * plugin to Genkit.builder().
   * 
   * @param modelName
   *            the model deployment name (e.g., "gpt-4.1",
   *            "my-custom-deployment")
   * @return this plugin instance for method chaining
   */
  public AzureFoundryPlugin customModel(String modelName) {
    customModels.add(modelName);
    logger.debug("Added custom model to be registered: {}", modelName);
    return this;
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public AzureFoundryPluginOptions getOptions() {
    return options;
  }
}
