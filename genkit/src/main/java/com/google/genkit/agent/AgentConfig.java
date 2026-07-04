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

package com.google.genkit.agent;

import com.google.genkit.ai.GenerationConfig;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.agent.ClientTransform;
import com.google.genkit.ai.agent.SessionStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Facade-level configuration for the ergonomic, prompt-backed agents created via {@code
 * GenkitBeta.defineAgent} and {@code GenkitBeta.definePromptAgent}.
 *
 * <p>Unlike {@link com.google.genkit.ai.agent.CustomAgentConfig} (which requires the caller to
 * supply the per-turn {@code AgentFn} themselves), this config captures the high-level pieces of a
 * generate-backed agent — model, system prompt, tools, generation config — and the {@code
 * defineAgent} implementation synthesizes the {@code AgentFn} that calls {@code generate}.
 *
 * <p>The only required field is {@link #getName()}.
 *
 * @param <S> the type of custom session state
 */
public final class AgentConfig<S> {

  private final String name;
  private final String description;
  private final String model;
  private final String system;
  private final List<Tool<?, ?>> tools;
  private final GenerationConfig config;
  private final Integer maxTurns;
  private final SessionStore<S> store;
  private final Class<S> stateType;
  private final ClientTransform<S> clientTransform;
  private final String promptName;
  private final Object promptInput;

  private AgentConfig(Builder<S> builder) {
    this.name = builder.name;
    this.description = builder.description;
    this.model = builder.model;
    this.system = builder.system;
    this.tools = builder.tools;
    this.config = builder.config;
    this.maxTurns = builder.maxTurns;
    this.store = builder.store;
    this.stateType = builder.stateType;
    this.clientTransform = builder.clientTransform;
    this.promptName = builder.promptName;
    this.promptInput = builder.promptInput;
  }

  /**
   * Creates a builder for AgentConfig.
   *
   * @param <S> the type of custom session state
   * @return a new builder
   */
  public static <S> Builder<S> builder() {
    return new Builder<>();
  }

  /**
   * Returns the agent's registered name.
   *
   * @return the agent name (never null on a built config)
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the agent's human-readable description.
   *
   * @return the description, or {@code null} if not set
   */
  public String getDescription() {
    return description;
  }

  /**
   * Returns the model name to use for generation.
   *
   * @return the model name, or {@code null} to rely on the Genkit default model
   */
  public String getModel() {
    return model;
  }

  /**
   * Returns the system prompt for the agent.
   *
   * @return the system prompt, or {@code null} if not set
   */
  public String getSystem() {
    return system;
  }

  /**
   * Returns the tools available to the agent.
   *
   * @return the tools, or {@code null} if not set
   */
  public List<Tool<?, ?>> getTools() {
    return tools;
  }

  /**
   * Returns the generation configuration.
   *
   * @return the config, or {@code null} if not set
   */
  public GenerationConfig getConfig() {
    return config;
  }

  /**
   * Returns the maximum number of tool-execution turns for a single generate call.
   *
   * @return the max turns, or {@code null} to use the generate default
   */
  public Integer getMaxTurns() {
    return maxTurns;
  }

  /**
   * Returns the session store for server-managed agents.
   *
   * <p>When {@code null}, the agent operates in client-managed mode.
   *
   * @return the session store, or {@code null} for client-managed mode
   */
  public SessionStore<S> getStore() {
    return store;
  }

  /**
   * Returns the Java class for the agent's custom state type.
   *
   * @return the state type class, or {@code null} if not specified
   */
  public Class<S> getStateType() {
    return stateType;
  }

  /**
   * Returns the client-transform applied to session state before returning it to the caller in
   * client-managed mode.
   *
   * @return the client transform, or {@code null} if not set
   */
  public ClientTransform<S> getClientTransform() {
    return clientTransform;
  }

  /**
   * Returns the name of a registered prompt to drive {@code definePromptAgent}.
   *
   * @return the prompt name, or {@code null} if not set
   */
  public String getPromptName() {
    return promptName;
  }

  /**
   * Returns the input passed to the prompt for {@code definePromptAgent}.
   *
   * @return the prompt input, or {@code null} if not set
   */
  public Object getPromptInput() {
    return promptInput;
  }

  /**
   * Builder for {@link AgentConfig}.
   *
   * @param <S> the type of custom session state
   */
  public static final class Builder<S> {

    private String name;
    private String description;
    private String model;
    private String system;
    private List<Tool<?, ?>> tools;
    private GenerationConfig config;
    private Integer maxTurns;
    private SessionStore<S> store;
    private Class<S> stateType;
    private ClientTransform<S> clientTransform;
    private String promptName;
    private Object promptInput;

    private Builder() {}

    /**
     * Sets the agent's registered name (required).
     *
     * @param name the agent name
     * @return this builder
     */
    public Builder<S> name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the agent's human-readable description.
     *
     * @param description the description
     * @return this builder
     */
    public Builder<S> description(String description) {
      this.description = description;
      return this;
    }

    /**
     * Sets the model name to use for generation. When {@code null}, the Genkit default model is
     * used.
     *
     * @param model the model name
     * @return this builder
     */
    public Builder<S> model(String model) {
      this.model = model;
      return this;
    }

    /**
     * Sets the system prompt for the agent.
     *
     * @param system the system prompt
     * @return this builder
     */
    public Builder<S> system(String system) {
      this.system = system;
      return this;
    }

    /**
     * Sets the tools available to the agent.
     *
     * @param tools the tools
     * @return this builder
     */
    public Builder<S> tools(List<Tool<?, ?>> tools) {
      this.tools = tools != null ? new ArrayList<>(tools) : null;
      return this;
    }

    /**
     * Sets the tools available to the agent.
     *
     * @param tools the tools
     * @return this builder
     */
    public Builder<S> tools(Tool<?, ?>... tools) {
      this.tools = tools != null ? new ArrayList<>(Arrays.asList(tools)) : null;
      return this;
    }

    /**
     * Sets the generation configuration.
     *
     * @param config the config
     * @return this builder
     */
    public Builder<S> config(GenerationConfig config) {
      this.config = config;
      return this;
    }

    /**
     * Sets the maximum number of tool-execution turns for a single generate call.
     *
     * @param maxTurns the max turns
     * @return this builder
     */
    public Builder<S> maxTurns(Integer maxTurns) {
      this.maxTurns = maxTurns;
      return this;
    }

    /**
     * Sets the session store for server-managed mode. Pass {@code null} (or omit) for
     * client-managed mode.
     *
     * @param store the session store
     * @return this builder
     */
    public Builder<S> store(SessionStore<S> store) {
      this.store = store;
      return this;
    }

    /**
     * Sets the Java class for the agent's custom state type.
     *
     * @param stateType the state type class
     * @return this builder
     */
    public Builder<S> stateType(Class<S> stateType) {
      this.stateType = stateType;
      return this;
    }

    /**
     * Sets the client-transform applied to session state before returning it to the caller in
     * client-managed mode.
     *
     * @param clientTransform the transform
     * @return this builder
     */
    public Builder<S> clientTransform(ClientTransform<S> clientTransform) {
      this.clientTransform = clientTransform;
      return this;
    }

    /**
     * Sets the name of a registered prompt to drive {@code definePromptAgent}.
     *
     * @param promptName the prompt name
     * @return this builder
     */
    public Builder<S> promptName(String promptName) {
      this.promptName = promptName;
      return this;
    }

    /**
     * Sets the input passed to the prompt for {@code definePromptAgent}.
     *
     * @param promptInput the prompt input
     * @return this builder
     */
    public Builder<S> promptInput(Object promptInput) {
      this.promptInput = promptInput;
      return this;
    }

    /**
     * Builds the {@link AgentConfig}.
     *
     * @return a new {@link AgentConfig}
     * @throws IllegalStateException if {@code name} is null or blank
     */
    public AgentConfig<S> build() {
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("name is required");
      }
      return new AgentConfig<>(this);
    }
  }
}
