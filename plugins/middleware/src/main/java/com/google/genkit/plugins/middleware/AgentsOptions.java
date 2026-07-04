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

package com.google.genkit.plugins.middleware;

import com.google.genkit.ai.agent.AgentRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Options controlling the sub-agent delegation tools produced by {@link Agents}.
 *
 * <p>The only required field is {@link #getAgents()} (at least one sub-agent). The remaining fields
 * have the following defaults:
 *
 * <ul>
 *   <li>{@code toolPrefix} &mdash; {@code "delegate_to"}; an empty string means the bare agent name
 *       is used as the tool name.
 *   <li>{@code maxDelegations} &mdash; {@code 0} (unlimited).
 *   <li>{@code historyLength} &mdash; {@code 0} (task only; see {@link Agents} for exactly how a
 *       non-zero value is forwarded to client-managed vs. server-managed sub-agents).
 *   <li>{@code artifactStrategy} &mdash; {@link ArtifactStrategy#INLINE}.
 * </ul>
 */
public final class AgentsOptions {

  private final List<String> agents;
  private final String toolPrefix;
  private final int maxDelegations;
  private final int historyLength;
  private final ArtifactStrategy artifactStrategy;

  private AgentsOptions(Builder builder) {
    if (builder.agents == null || builder.agents.isEmpty()) {
      throw new IllegalArgumentException("at least one agent is required");
    }
    this.agents = new ArrayList<>(builder.agents);
    this.toolPrefix = builder.toolPrefix != null ? builder.toolPrefix : "delegate_to";
    this.maxDelegations = builder.maxDelegations;
    this.historyLength = builder.historyLength;
    this.artifactStrategy =
        builder.artifactStrategy != null ? builder.artifactStrategy : ArtifactStrategy.INLINE;
  }

  /**
   * Creates a builder for {@link AgentsOptions}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configured sub-agent names.
   *
   * @return the sub-agent names (never empty)
   */
  public List<String> getAgents() {
    return agents;
  }

  /**
   * Returns the tool-name prefix. An empty string means the bare agent name is the tool name.
   *
   * @return the tool prefix
   */
  public String getToolPrefix() {
    return toolPrefix;
  }

  /**
   * Returns the maximum number of delegations per parent generate invocation. {@code 0} means
   * unlimited.
   *
   * @return the max delegations
   */
  public int getMaxDelegations() {
    return maxDelegations;
  }

  /**
   * Returns the number of trailing prior messages (from this sub-agent's own accumulated
   * conversation with the delegating parent session, not the parent's own history) to forward on
   * each delegation call. {@code 0} means task only, no history forwarded.
   *
   * <p>See {@link Agents} for exactly how this is applied for client-managed vs. server-managed
   * sub-agents.
   *
   * @return the history length
   */
  public int getHistoryLength() {
    return historyLength;
  }

  /**
   * Returns the artifact strategy.
   *
   * @return the artifact strategy
   */
  public ArtifactStrategy getArtifactStrategy() {
    return artifactStrategy;
  }

  /** Builder for {@link AgentsOptions}. */
  public static final class Builder {
    private List<String> agents;
    private String toolPrefix;
    private int maxDelegations;
    private int historyLength;
    private ArtifactStrategy artifactStrategy;

    private Builder() {}

    /**
     * Sets the sub-agent names (required, at least one).
     *
     * @param agents the sub-agent names
     * @return this builder
     */
    public Builder agents(List<String> agents) {
      this.agents = agents != null ? new ArrayList<>(agents) : null;
      return this;
    }

    /**
     * Sets the sub-agent names (required, at least one).
     *
     * @param agents the sub-agent names
     * @return this builder
     */
    public Builder agents(String... agents) {
      this.agents = agents != null ? new ArrayList<>(Arrays.asList(agents)) : null;
      return this;
    }

    /**
     * Adds sub-agents from {@link AgentRef}s (uses {@link AgentRef#getName()}).
     *
     * @param refs the agent refs
     * @return this builder
     */
    public Builder agentRefs(AgentRef... refs) {
      List<String> names = new ArrayList<>();
      if (refs != null) {
        for (AgentRef ref : refs) {
          if (ref != null) {
            names.add(ref.getName());
          }
        }
      }
      this.agents = names;
      return this;
    }

    /**
     * Sets the tool-name prefix (default {@code "delegate_to"}). An empty string uses the bare
     * agent name.
     *
     * @param toolPrefix the tool prefix
     * @return this builder
     */
    public Builder toolPrefix(String toolPrefix) {
      this.toolPrefix = toolPrefix;
      return this;
    }

    /**
     * Sets the maximum delegations per parent invocation ({@code 0} = unlimited).
     *
     * @param maxDelegations the max delegations
     * @return this builder
     */
    public Builder maxDelegations(int maxDelegations) {
      this.maxDelegations = maxDelegations;
      return this;
    }

    /**
     * Sets the trailing parent-history length to forward ({@code 0} = task only).
     *
     * @param historyLength the history length
     * @return this builder
     */
    public Builder historyLength(int historyLength) {
      this.historyLength = historyLength;
      return this;
    }

    /**
     * Sets the artifact strategy (default {@link ArtifactStrategy#INLINE}).
     *
     * @param artifactStrategy the artifact strategy
     * @return this builder
     */
    public Builder artifactStrategy(ArtifactStrategy artifactStrategy) {
      this.artifactStrategy = artifactStrategy;
      return this;
    }

    /**
     * Builds the {@link AgentsOptions}.
     *
     * @return a new {@link AgentsOptions}
     * @throws IllegalArgumentException if no agents are configured
     */
    public AgentsOptions build() {
      return new AgentsOptions(this);
    }
  }
}
