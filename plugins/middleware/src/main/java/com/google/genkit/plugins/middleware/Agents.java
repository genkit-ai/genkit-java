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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.genkit.ai.Tool;
import com.google.genkit.plugins.middleware.internal.Delegation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for sub-agent delegation tools.
 *
 * <p>Each configured sub-agent is exposed to the delegating model as a {@code <prefix>_<name>} tool
 * (default prefix {@code delegate_to}, so {@code delegate_to_<name>}). When the model calls that
 * tool with {@code {"task": "..."}}, the named sub-agent (resolved from the registry at {@code
 * /agent/<name>}) is run for a single turn and its assistant text is returned as {@code response}.
 * Any artifacts the sub-agent produces are namespaced ({@code <invocationId>/<name>}) and merged
 * into the active parent session via {@link
 * com.google.genkit.ai.agent.AgentSessionContext#currentArtifactStore()}.
 *
 * <h2>Pragmatic scope decision (tools-factory, not a generate hook)</h2>
 *
 * <p>This repo's generate pipeline has no first-class "wrap generate / inject system prompt"
 * middleware seam that an agent's synthesized {@code AgentFn} runs through, but {@code AgentConfig}
 * already exposes {@code tools(List)} and {@code system(String)}. {@code Agents} is therefore
 * implemented as a <em>tool factory</em>: {@link #delegationTools(AgentsOptions)} returns the
 * delegation tools and {@link #systemPromptFragment(AgentsOptions)} returns a {@code <sub-agents>}
 * system-prompt fragment. Callers wire them in via {@code AgentConfig.tools(...)} and {@code
 * AgentConfig.system(...)}. This achieves model-driven sub-agent delegation without modifying the
 * generate pipeline.
 *
 * <h2>History forwarding</h2>
 *
 * <p>Each (parent session, sub-agent) pair is delegated to through its own stable sub-session (see
 * {@code Delegation} for the exact key derivation and mechanics). Repeated delegation calls to the
 * SAME sub-agent within the SAME parent session see up to {@code historyLength} trailing messages
 * of that sub-session's own accumulated history; {@code historyLength <= 0} (the default) means
 * task-only, no history forwarded. For client-managed sub-agents this is fully honored via an
 * internal history ledger forwarded as {@code AgentInit.state}. For server-managed sub-agents
 * (configured with a {@code SessionStore}), history is forwarded by resuming the sub-agent's own
 * store-backed session via {@code AgentInit.sessionId} — the store's full accumulated history is
 * used and {@code historyLength} trimming is not applied in that case (no seam exists to truncate a
 * resolved session before the sub-agent's turn runs without mutating its own persisted snapshot).
 *
 * <h2>Structured failure/interrupt propagation</h2>
 *
 * <p>A sub-agent turn that finishes {@code INTERRUPTED} causes the delegation tool to throw {@link
 * com.google.genkit.ai.ToolInterruptException}, carrying the sub-agent's interrupted tool
 * name/input as metadata, so the parent's own generate loop pauses too (nested interrupt
 * visibility). Resuming the parent's interrupt surfaces that information; it does not, by itself,
 * re-invoke the sub-agent with a resume — full nested-resume is out of scope for this fix. A
 * sub-agent turn that finishes {@code FAILED} (or whose {@code runBidiJson} call itself throws)
 * causes the delegation tool to throw {@link com.google.genkit.core.GenkitException} carrying the
 * sub-agent's error message, so the failure propagates like any other tool exception.
 *
 * <h2>Other limitations (v1)</h2>
 *
 * <ul>
 *   <li>{@code maxDelegations} is enforced per {@code delegationTools(...)} invocation, shared
 *       across all delegation tools returned by that call (an in-memory counter).
 * </ul>
 */
public final class Agents {

  private Agents() {}

  /**
   * Builds the delegation tools (one per configured sub-agent).
   *
   * <p>The returned tools share a single delegation counter so that {@code
   * options.getMaxDelegations()} is enforced across all of them for the lifetime of this call's
   * result.
   *
   * @param options the options (must not be null; at least one agent)
   * @return the delegation tools
   */
  public static List<Tool<?, ?>> delegationTools(AgentsOptions options) {
    if (options == null) {
      throw new IllegalArgumentException("options must not be null");
    }
    boolean includeContent = options.getArtifactStrategy() == ArtifactStrategy.INLINE;
    int maxDelegations = options.getMaxDelegations();
    AtomicInteger counter = new AtomicInteger(0);

    List<Tool<?, ?>> tools = new ArrayList<>();
    for (String agentName : options.getAgents()) {
      tools.add(delegationTool(agentName, options, includeContent, maxDelegations, counter));
    }
    return tools;
  }

  /**
   * Builds the tool name for a sub-agent given the configured prefix. An empty prefix yields the
   * bare agent name.
   *
   * @param prefix the tool prefix
   * @param agentName the sub-agent name
   * @return the delegation tool name
   */
  public static String toolName(String prefix, String agentName) {
    if (prefix == null || prefix.isEmpty()) {
      return agentName;
    }
    return prefix + "_" + agentName;
  }

  /**
   * Builds a {@code <sub-agents>} system-prompt fragment listing each delegation tool and the agent
   * it delegates to.
   *
   * @param options the options (must not be null)
   * @return the system-prompt fragment
   */
  public static String systemPromptFragment(AgentsOptions options) {
    if (options == null) {
      throw new IllegalArgumentException("options must not be null");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("<sub-agents>\n");
    sb.append(
        "You can delegate a self-contained task to a specialized sub-agent by calling one of the"
            + " tools below with a 'task' describing what it should do. Use the sub-agent's text"
            + " response to continue your own work.\n");
    for (String agentName : options.getAgents()) {
      String tool = toolName(options.getToolPrefix(), agentName);
      sb.append("  - ")
          .append(tool)
          .append(": delegates to the '")
          .append(agentName)
          .append("' agent.\n");
    }
    sb.append("</sub-agents>");
    return sb.toString();
  }

  // ── Internal ──────────────────────────────────────────────────────────────────

  private static Tool<DelegateInput, DelegateOutput> delegationTool(
      String agentName,
      AgentsOptions options,
      boolean includeContent,
      int maxDelegations,
      AtomicInteger counter) {
    String tool = toolName(options.getToolPrefix(), agentName);
    return Tool.<DelegateInput, DelegateOutput>builder()
        .name(tool)
        .description(
            "Delegate a self-contained task to the '"
                + agentName
                + "' sub-agent and return its text response.")
        .inputClass(DelegateInput.class)
        .outputClass(DelegateOutput.class)
        .handler(
            (ctx, in) -> {
              if (maxDelegations > 0 && counter.incrementAndGet() > maxDelegations) {
                return DelegateOutput.text(
                    "Delegation limit reached ("
                        + maxDelegations
                        + "). Answer using the information already gathered.");
              }
              String task = in != null ? in.task : null;
              Delegation.Result result =
                  Delegation.run(ctx, agentName, task, includeContent, options.getHistoryLength());

              DelegateOutput out = new DelegateOutput();
              out.response = result.response;
              if (result.artifacts != null && !result.artifacts.isEmpty()) {
                out.artifacts = new ArrayList<>();
                for (Delegation.NamedArtifact na : result.artifacts) {
                  ArtifactRef ref = new ArtifactRef();
                  ref.name = na.name;
                  ref.content = na.content;
                  out.artifacts.add(ref);
                }
              }
              return out;
            })
        .build();
  }

  // ── Tool I/O types ──────────────────────────────────────────────────────────────

  /** Input for a delegation tool. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class DelegateInput {
    @JsonProperty("task")
    public String task;

    /** Default constructor for JSON deserialization. */
    public DelegateInput() {}
  }

  /** Output for a delegation tool. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class DelegateOutput {
    @JsonProperty("response")
    public String response;

    @JsonProperty("artifacts")
    public List<ArtifactRef> artifacts;

    /** Default constructor for JSON deserialization. */
    public DelegateOutput() {}

    static DelegateOutput text(String response) {
      DelegateOutput o = new DelegateOutput();
      o.response = response;
      return o;
    }
  }

  /** A namespaced reference to an artifact produced by a sub-agent. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ArtifactRef {
    @JsonProperty("name")
    public String name;

    /** Content; populated only under {@link ArtifactStrategy#INLINE}. */
    @JsonProperty("content")
    public String content;

    /** Default constructor for JSON deserialization. */
    public ArtifactRef() {}
  }
}
