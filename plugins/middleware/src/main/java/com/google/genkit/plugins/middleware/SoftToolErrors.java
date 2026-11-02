/*
 * Copyright 2026 Google LLC
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

package com.google.genkit.plugins.middleware;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.SoftToolErrorPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Returns tool errors to the model instead of failing the generation. The model receives the error
 * as the tool's response, {@code {"error": "<message>"}}, and can correct its input and try again.
 *
 * <p>It covers the errors a tool throws, input conversion included, and calls to tool names that do
 * not exist. These still stop the generation: errors that a {@code wrapTool} hook raises in place
 * of the tool's own, and interrupts. Other {@code wrapTool} hooks, such as a retry, see the tool's
 * error before it becomes a response, wherever SoftToolErrors sits in the chain.
 *
 * <p>With SoftToolErrors on a call, an error of a tool it does not cover fails the generation.
 * Several instances on one call combine: a tool covered by any of them returns its errors to the
 * model.
 *
 * <p>A tool author can return a specific error to the model without this middleware by throwing
 * {@link com.google.genkit.ai.ToolFailException}.
 */
public class SoftToolErrors extends BaseGenerationMiddleware implements SoftToolErrorPolicy {

  /** The registered middleware name. */
  public static final String NAME = GenerationMiddlewarePlugin.PLUGIN_NAME + "/softToolErrors";

  private final Options options;

  /**
   * Creates the middleware.
   *
   * @param options the configuration, or null to cover every tool
   */
  public SoftToolErrors(Options options) {
    this.options = options != null ? options : new Options();
  }

  /** Creates the middleware covering every tool. */
  public SoftToolErrors() {
    this((Options) null);
  }

  /**
   * Creates the middleware covering the named tools.
   *
   * @param tools the tool names
   * @return the middleware
   */
  public static SoftToolErrors forTools(String... tools) {
    Options options = new Options();
    options.tools = new ArrayList<>(List.of(tools));
    return new SoftToolErrors(options);
  }

  /**
   * Returns this middleware's configuration.
   *
   * @return the options
   */
  public Options getOptions() {
    return options;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public GenerationMiddleware newInstance() {
    return this;
  }

  @Override
  public boolean coversToolErrors(String toolName) {
    return options.tools == null || options.tools.isEmpty() || options.tools.contains(toolName);
  }

  /** Configuration for {@link SoftToolErrors}. */
  public static class Options {

    /** Limits the middleware to these tool names; empty covers every tool. */
    @JsonProperty("tools")
    @JsonPropertyDescription(
        "Tool names whose errors are returned to the model. Empty covers every tool, and calls"
            + " to tools that do not exist.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<String> tools = new ArrayList<>();

    /** Creates the default options. */
    public Options() {}
  }
}
