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

import java.util.Arrays;
import java.util.List;

/** Options for configuring the Ollama plugin. */
public class OllamaPluginOptions {

  private final String baseUrl;
  private final int timeout;
  private final List<String> models;

  private OllamaPluginOptions(Builder builder) {
    this.baseUrl = builder.baseUrl;
    this.timeout = builder.timeout;
    this.models = builder.models;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the base URL for Ollama API requests.
   *
   * @return the base URL
   */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Gets the request timeout in seconds.
   *
   * @return the timeout
   */
  public int getTimeout() {
    return timeout;
  }

  /**
   * Gets the list of models to register. If empty, will attempt to auto-discover models from
   * Ollama.
   *
   * @return the list of models
   */
  public List<String> getModels() {
    return models;
  }

  /** Builder for OllamaPluginOptions. */
  public static class Builder {
    private String baseUrl = getBaseUrlFromEnv();
    private int timeout = 300; // Ollama models can be slow to respond
    private List<String> models =
        Arrays.asList(
            "llama3.2",
            "llama3.1",
            "llama3",
            "llama2",
            "mistral",
            "mixtral",
            "codellama",
            "phi3",
            "phi",
            "gemma2",
            "gemma",
            "qwen2.5",
            "qwen2",
            "deepseek-coder-v2",
            "command-r",
            "llava");

    private static String getBaseUrlFromEnv() {
      String url = System.getenv("OLLAMA_HOST");
      return url != null ? url : "http://localhost:11434";
    }

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder timeout(int timeout) {
      this.timeout = timeout;
      return this;
    }

    public Builder models(List<String> models) {
      this.models = models;
      return this;
    }

    public Builder models(String... models) {
      this.models = Arrays.asList(models);
      return this;
    }

    public OllamaPluginOptions build() {
      return new OllamaPluginOptions(this);
    }
  }
}
