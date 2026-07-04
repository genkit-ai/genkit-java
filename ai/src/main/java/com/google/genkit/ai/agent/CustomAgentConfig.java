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

package com.google.genkit.ai.agent;

/**
 * Configuration for defining a custom agent via {@code AgentActions.defineCustomAgent}.
 *
 * <p>Use the {@link Builder} to configure the agent. The only required field is {@link #getName()}.
 *
 * @param <S> the type of custom session state
 */
public final class CustomAgentConfig<S> {

  private final String name;
  private final String description;
  private final Class<S> stateType;
  private final SessionStore<S> store;
  private final ClientTransform<S> clientTransform;
  private final SessionStoreOptions storeOptions;

  private CustomAgentConfig(Builder<S> builder) {
    this.name = builder.name;
    this.description = builder.description;
    this.stateType = builder.stateType;
    this.store = builder.store;
    this.clientTransform = builder.clientTransform;
    this.storeOptions = builder.storeOptions;
  }

  /**
   * Creates a builder for CustomAgentConfig.
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
   * @return the agent name (never null)
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
   * Returns the Java class for the agent's custom state type.
   *
   * @return the state type class, or {@code null} if not specified
   */
  public Class<S> getStateType() {
    return stateType;
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
   * Returns the client-transform applied to session state before returning it to the caller in
   * client-managed mode.
   *
   * @return the client transform, or {@code null} if not set
   */
  public ClientTransform<S> getClientTransform() {
    return clientTransform;
  }

  /**
   * Returns the options forwarded to store operations.
   *
   * @return the store options, or {@code null} if not set
   */
  public SessionStoreOptions getStoreOptions() {
    return storeOptions;
  }

  /**
   * Builder for {@link CustomAgentConfig}.
   *
   * @param <S> the type of custom session state
   */
  public static final class Builder<S> {

    private String name;
    private String description;
    private Class<S> stateType;
    private SessionStore<S> store;
    private ClientTransform<S> clientTransform;
    private SessionStoreOptions storeOptions;

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
     * Sets the options forwarded to store operations.
     *
     * @param storeOptions the store options
     * @return this builder
     */
    public Builder<S> storeOptions(SessionStoreOptions storeOptions) {
      this.storeOptions = storeOptions;
      return this;
    }

    /**
     * Builds the {@link CustomAgentConfig}.
     *
     * @return a new {@link CustomAgentConfig}
     * @throws IllegalStateException if {@code name} is null or blank
     */
    public CustomAgentConfig<S> build() {
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("name is required");
      }
      return new CustomAgentConfig<>(this);
    }
  }
}
