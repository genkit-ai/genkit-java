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

import com.azure.core.credential.AccessToken;
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
 * AzureFoundryPlugin provides Azure AI Foundry model integrations for Genkit.
 *
 * <p>This plugin uses Azure's OpenAI-compatible inference endpoints to support various models
 * deployed in Azure AI Foundry, including GPT-4, GPT-3.5, Llama, Mistral, and more.
 */
public class AzureFoundryPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(AzureFoundryPlugin.class);

  /**
   * Azure AI Foundry supported models. Model availability varies by region and Azure subscription.
   * See: https://learn.microsoft.com/en-us/azure/ai-foundry/agents/concepts/model-region-support
   */
  public static final List<String> SUPPORTED_MODELS =
      Arrays.asList(
          // Azure OpenAI models (Global Standard & Provisioned)
          "gpt-5.5",
          "gpt-5.4",
          "gpt-5.4-mini",
          "gpt-5.4-nano",
          "gpt-5.4-pro",
          "gpt-5.2",
          "gpt-5.1",
          "gpt-5",
          "gpt-5-mini",
          "gpt-5-nano",
          "gpt-5-pro",
          "gpt-5-codex",
          "o3",
          "o3-pro",
          "o3-mini",
          "gpt-4.1",
          "gpt-4.1-mini",
          "gpt-4o",
          "gpt-4o-mini",
          "gpt-4",
          // Azure models sold directly by Azure
          "grok-4",
          "grok-4-1-fast-reasoning",
          "grok-4-1-fast-non-reasoning",
          "grok-code-fast-1",
          "llama-3-3-70b-instruct",
          "llama-4-maverick-17b-128e-instruct-fp8",
          "DeepSeek-V3.2",
          "DeepSeek-V3.2-Speciale",
          "Mistral-Large-3",
          "gpt-oss-120b",
          // Partner and community models (Anthropic Claude)
          "claude-opus-4-8",
          "claude-sonnet-5",
          "claude-opus-4-7",
          "claude-opus-4-6",
          "claude-sonnet-4-6",
          "claude-opus-4-5",
          "claude-opus-4-1",
          "claude-sonnet-4-5",
          "claude-haiku-4-5");

  private final AzureFoundryPluginOptions options;
  private final CompatOAIPluginOptions compatOptions;
  private final List<String> customModels = new ArrayList<>();

  /**
   * Creates an AzureFoundryPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public AzureFoundryPlugin(AzureFoundryPluginOptions options) {
    this.options = options;

    // Convert Azure Foundry options to CompatOAI options
    CompatOAIPluginOptions.Builder compatBuilder =
        CompatOAIPluginOptions.builder().baseUrl(buildBaseUrl(options));

    // Pass api-version as a real query parameter so compat-oai appends it AFTER the
    // /chat/completions path. Baking it into the base URL string (as buildBaseUrl used to) corrupts
    // the request URL for non-Azure-OpenAI hosts, e.g. AI Foundry v1 endpoints on
    // services.ai.azure.com.
    if (options.getApiVersion() != null) {
      compatBuilder.queryParams(java.util.Map.of("api-version", options.getApiVersion()));
    }

    // Handle authentication
    if (options.getApiKey() != null) {
      compatBuilder.apiKey(options.getApiKey());
    } else if (options.getCredential() != null) {
      // Get an access token from the Azure credential
      try {
        AccessToken token =
            options
                .getCredential()
                .getToken(
                    new com.azure.core.credential.TokenRequestContext()
                        .addScopes("https://cognitiveservices.azure.com/.default"))
                .block();
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
   * @param endpoint the Azure AI Foundry endpoint
   * @param apiKey the API key
   * @return a new AzureFoundryPlugin
   */
  public static AzureFoundryPlugin create(String endpoint, String apiKey) {
    return new AzureFoundryPlugin(
        AzureFoundryPluginOptions.builder().endpoint(endpoint).apiKey(apiKey).build());
  }

  /**
   * Creates an AzureFoundryPlugin using environment variables or default Azure credentials.
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
    boolean isAzureOpenAI =
        options.getEndpoint().contains("openai.azure.com")
            || options.getEndpoint().contains("cognitiveservices.azure.com");

    String path = endpointPath(options.getEndpoint());
    if (!path.contains("inference") && !path.contains("openai")) {
      if (isAzureOpenAI) {
        // Azure OpenAI Service uses /openai/deployments/{deployment}/ path
        // The deployment name will be added by the model, so we just set the base
        url.append("openai/deployments");
      } else {
        // Azure AI Foundry uses inference/v1 path for OpenAI-compatible endpoints
        url.append("inference/v1");
      }
    }

    // NOTE: api-version is NOT appended here — it is added as a query parameter by the caller so it
    // lands after the /chat/completions path segment (see the constructor and
    // buildAzureOpenAIOptions).
    return url.toString();
  }

  /**
   * Returns the path component of an endpoint URL (empty string when there is none or it can't be
   * parsed). Used to detect whether the endpoint already carries the {@code /openai} or {@code
   * /inference} path, without being fooled by hostnames that contain those words (e.g. a resource
   * named {@code openai-foo}).
   */
  private static String endpointPath(String endpoint) {
    try {
      String p = java.net.URI.create(endpoint).getPath();
      return p == null ? "" : p;
    } catch (RuntimeException e) {
      return "";
    }
  }

  /**
   * Builds CompatOAI options for Azure OpenAI deployments. Azure OpenAI requires the deployment
   * name in the URL path.
   */
  private CompatOAIPluginOptions buildAzureOpenAIOptions(String deploymentName) {
    StringBuilder url = new StringBuilder(options.getEndpoint());
    if (!url.toString().endsWith("/")) {
      url.append("/");
    }

    // Azure OpenAI path: /openai/deployments/{deployment-id}. Inspect the URL *path* (not the whole
    // endpoint) so a resource whose host contains "openai" (e.g.
    // https://openai-foo.openai.azure.com) isn't mistaken for an endpoint that already includes the
    // /openai path.
    if (!endpointPath(options.getEndpoint()).contains("openai")) {
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
    boolean isAzureOpenAI =
        options.getEndpoint().contains("openai.azure.com")
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
        CompatOAIModel model =
            new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName, modelOptions);
        actions.add(model);
      } else {
        CompatOAIModel model =
            new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName, compatOptions);
        actions.add(model);
      }
      logger.debug("Created Azure Foundry model: {}", modelName);
    }

    // Register custom models added via customModel()
    for (String modelName : customModels) {
      String fullName = "azure-foundry/" + modelName;
      if (isAzureOpenAI) {
        CompatOAIPluginOptions modelOptions = buildAzureOpenAIOptions(modelName);
        CompatOAIModel model =
            new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName, modelOptions);
        actions.add(model);
      } else {
        CompatOAIModel model =
            new CompatOAIModel(fullName, modelName, "Azure Foundry " + modelName, compatOptions);
        actions.add(model);
      }
      logger.debug("Created custom Azure Foundry model: {}", modelName);
    }

    logger.info(
        "Azure Foundry plugin initialized with {} models",
        SUPPORTED_MODELS.size() + customModels.size());

    return actions;
  }

  /**
   * Registers a custom model or deployment name. Use this to work with custom deployments not in
   * the default list. Call this method before passing the plugin to Genkit.builder().
   *
   * @param modelName the model deployment name (e.g., "gpt-4.1", "my-custom-deployment")
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
