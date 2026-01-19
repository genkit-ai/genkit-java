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

package com.google.genkit.plugins.awsbedrock;

import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AwsBedrockPlugin provides AWS Bedrock model integrations for Genkit.
 *
 * <p>This plugin registers various AWS Bedrock models including Amazon Nova, Amazon Titan,
 * Anthropic Claude, AI21 Jamba, Meta Llama, Cohere Command, Mistral, DeepSeek, Google Gemma, Qwen,
 * NVIDIA, OpenAI, Writer, MiniMax, Moonshot, and TwelveLabs models as Genkit actions for text
 * generation with support for streaming.
 *
 * <p>Supports both direct model IDs and inference profile IDs/ARNs. Inference profiles enable
 * cross-region inference and are required for certain models (e.g., Claude 4.x, Claude 3.5+
 * models).
 *
 * <h2>Using Inference Profiles</h2>
 *
 * <p>For models that require inference profiles, you can use either:
 *
 * <ul>
 *   <li>Inference profile ID: {@code us.anthropic.claude-sonnet-4-5-v2:0}
 *   <li>Model ID (if ON_DEMAND supported): {@code amazon.nova-lite-v1:0}
 * </ul>
 *
 * @see <a href= "https://docs.aws.amazon.com/bedrock/latest/userguide/cross-region-inference.html">
 *     AWS Bedrock Cross-Region Inference</a>
 */
public class AwsBedrockPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(AwsBedrockPlugin.class);

  private final AwsBedrockPluginOptions options;
  private final List<String> customModels = new ArrayList<>();

  /** Supported AWS Bedrock models with ON_DEMAND or INFERENCE_PROFILE support. */
  public static final List<String> SUPPORTED_MODELS =
      Arrays.asList(
          // Amazon Nova models (ON_DEMAND + INFERENCE_PROFILE)
          "amazon.nova-pro-v1:0",
          "amazon.nova-lite-v1:0",
          "amazon.nova-micro-v1:0",
          "amazon.nova-premier-v1:0",
          "amazon.nova-2-lite-v1:0",
          "amazon.nova-sonic-v1:0",
          "amazon.nova-2-sonic-v1:0",
          // Amazon Titan models
          "amazon.titan-tg1-large",
          "amazon.titan-text-express-v1",
          // Anthropic Claude 4 models (INFERENCE_PROFILE)
          "anthropic.claude-sonnet-4-20250514-v1:0",
          "anthropic.claude-sonnet-4-5-20250929-v1:0",
          "anthropic.claude-haiku-4-5-20251001-v1:0",
          "anthropic.claude-opus-4-1-20250805-v1:0",
          "anthropic.claude-opus-4-5-20251101-v1:0",
          // Anthropic Claude 3.x models
          "anthropic.claude-3-7-sonnet-20250219-v1:0",
          "anthropic.claude-3-5-sonnet-20241022-v2:0",
          "anthropic.claude-3-5-sonnet-20240620-v1:0",
          "anthropic.claude-3-5-haiku-20241022-v1:0",
          "anthropic.claude-3-opus-20240229-v1:0",
          "anthropic.claude-3-sonnet-20240229-v1:0",
          "anthropic.claude-3-haiku-20240307-v1:0",
          // AI21 models
          "ai21.jamba-1-5-large-v1:0",
          "ai21.jamba-1-5-mini-v1:0",
          // Meta Llama models
          "meta.llama4-scout-17b-instruct-v1:0",
          "meta.llama4-maverick-17b-instruct-v1:0",
          "meta.llama3-3-70b-instruct-v1:0",
          "meta.llama3-2-90b-instruct-v1:0",
          "meta.llama3-2-11b-instruct-v1:0",
          "meta.llama3-2-3b-instruct-v1:0",
          "meta.llama3-2-1b-instruct-v1:0",
          "meta.llama3-1-70b-instruct-v1:0",
          "meta.llama3-1-8b-instruct-v1:0",
          "meta.llama3-70b-instruct-v1:0",
          "meta.llama3-8b-instruct-v1:0",
          // Cohere models
          "cohere.command-r-plus-v1:0",
          "cohere.command-r-v1:0",
          // Mistral models
          "mistral.pixtral-large-2502-v1:0",
          "mistral.mistral-large-3-675b-instruct",
          "mistral.magistral-small-2509",
          "mistral.mistral-large-2402-v1:0",
          "mistral.mistral-small-2402-v1:0",
          "mistral.ministral-3-14b-instruct",
          "mistral.ministral-3-8b-instruct",
          "mistral.ministral-3-3b-instruct",
          "mistral.mixtral-8x7b-instruct-v0:1",
          "mistral.mistral-7b-instruct-v0:2",
          "mistral.voxtral-mini-3b-2507",
          "mistral.voxtral-small-24b-2507",
          // DeepSeek models
          "deepseek.r1-v1:0",
          // Google Gemma models
          "google.gemma-3-27b-it",
          "google.gemma-3-12b-it",
          "google.gemma-3-4b-it",
          // Qwen models
          "qwen.qwen3-next-80b-a3b",
          "qwen.qwen3-vl-235b-a22b",
          "qwen.qwen3-32b-v1:0",
          "qwen.qwen3-coder-30b-a3b-v1:0",
          // NVIDIA models
          "nvidia.nemotron-nano-12b-v2",
          "nvidia.nemotron-nano-9b-v2",
          "nvidia.nemotron-nano-3-30b",
          // OpenAI models
          "openai.gpt-oss-120b-1:0",
          "openai.gpt-oss-20b-1:0",
          "openai.gpt-oss-safeguard-120b",
          "openai.gpt-oss-safeguard-20b",
          // Writer models
          "writer.palmyra-x5-v1:0",
          "writer.palmyra-x4-v1:0",
          // MiniMax models
          "minimax.minimax-m2",
          // Moonshot models
          "moonshot.kimi-k2-thinking",
          // TwelveLabs models
          "twelvelabs.pegasus-1-2-v1:0");

  /** Creates an AwsBedrockPlugin with default options. */
  public AwsBedrockPlugin() {
    this(AwsBedrockPluginOptions.builder().build());
  }

  /**
   * Creates an AwsBedrockPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public AwsBedrockPlugin(AwsBedrockPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates an AwsBedrockPlugin with the specified region.
   *
   * @param region the AWS region string (e.g., "us-east-1")
   * @return a new AwsBedrockPlugin
   */
  public static AwsBedrockPlugin create(String region) {
    return new AwsBedrockPlugin(AwsBedrockPluginOptions.builder().region(region).build());
  }

  /**
   * Creates an AwsBedrockPlugin using default AWS credentials and us-east-1 region.
   *
   * @return a new AwsBedrockPlugin
   */
  public static AwsBedrockPlugin create() {
    return new AwsBedrockPlugin();
  }

  @Override
  public String getName() {
    return "aws-bedrock";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    List<Action<?, ?, ?>> actions = new ArrayList<>();

    // Register AWS Bedrock models (supports both model IDs and inference profile
    // IDs)
    for (String modelId : SUPPORTED_MODELS) {
      AwsBedrockModel model = new AwsBedrockModel(modelId, options);
      actions.add(model);
      logger.debug("Registered AWS Bedrock model: {}", modelId);
    }

    // Register custom models added via customModel()
    for (String modelId : customModels) {
      AwsBedrockModel model = new AwsBedrockModel(modelId, options);
      actions.add(model);
      logger.debug("Registered custom AWS Bedrock model: {}", modelId);
    }

    logger.info(
        "AWS Bedrock plugin initialized with {} models in region {} (supports inference profiles)",
        SUPPORTED_MODELS.size() + customModels.size(),
        options.getRegion());

    return actions;
  }

  /**
   * Registers a custom model ID or inference profile. Use this to work with models not in the
   * default list or to use inference profiles. Call this method before passing the plugin to
   * Genkit.builder().
   *
   * @param modelId the model ID or inference profile ID (e.g.,
   *     "us.anthropic.claude-sonnet-4-5-v2:0")
   * @return this plugin instance for method chaining
   */
  public AwsBedrockPlugin customModel(String modelId) {
    customModels.add(modelId);
    logger.debug("Added custom model to be registered: {}", modelId);
    return this;
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public AwsBedrockPluginOptions getOptions() {
    return options;
  }
}
