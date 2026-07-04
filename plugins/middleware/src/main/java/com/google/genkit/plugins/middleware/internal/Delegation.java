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

package com.google.genkit.plugins.middleware.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ToolInterruptException;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentOutput;
import com.google.genkit.ai.agent.AgentSessionContext;
import com.google.genkit.ai.agent.Artifact;
import com.google.genkit.ai.agent.ArtifactStore;
import com.google.genkit.ai.agent.Session;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.BidiAction;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.Registry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal helper that runs a single one-shot turn against a sub-agent and packages the result.
 *
 * <p>This isolates the registry lookup, the {@code runBidiJson} one-shot invocation, output
 * parsing, artifact namespacing/merging, and cross-call history bookkeeping from the public {@code
 * Agents} façade.
 *
 * <h2>History forwarding (per parent-session, per-sub-agent)</h2>
 *
 * <p>Each (parent session id, sub-agent name) pair gets its own stable sub-session key ({@link
 * #subSessionKey}). For a <b>client-managed</b> sub-agent (no {@code SessionStore}), this class
 * keeps a small in-memory ledger ({@link #CLIENT_HISTORY}) of that sub-session's accumulated
 * messages and forwards the trailing {@code historyLength} of them via {@code
 * AgentInit.state.messages} on every call, then updates the ledger from the returned {@code
 * AgentOutput.state} afterward. This leans on the existing client-managed hydration path in {@code
 * SessionResolver.resolve} (state → {@code new Session<>(state)}) rather than inventing new
 * persistence.
 *
 * <p>For a <b>server-managed</b> sub-agent (has a {@code SessionStore}), the sub-session key is
 * forwarded as {@code AgentInit.sessionId}; the sub-agent's own store then naturally accumulates
 * history via {@code SessionResolver.resolveBySessionId} across repeated delegation calls. Note:
 * {@code historyLength} trimming is <em>not</em> applied in the server-managed case — the store
 * resolves and hands the agent its full accumulated history, and there is no seam in {@code
 * defineCustomAgent} to truncate what a resolved session sees before {@code AgentFn} runs without
 * mutating the sub-agent's own persisted snapshot (out of scope for this fix; see {@code
 * Agents}/{@code AgentsOptions} javadoc).
 */
public final class Delegation {

  private Delegation() {}

  /**
   * Ledger of accumulated message history per sub-session key, for client-managed sub-agents only.
   * Server-managed sub-agents accumulate history in their own {@code SessionStore} instead.
   */
  private static final Map<String, List<Message>> CLIENT_HISTORY = new ConcurrentHashMap<>();

  /** Result of a single delegation run, ready to be mapped onto the delegation tool output. */
  public static final class Result {
    public final String response;
    public final List<NamedArtifact> artifacts;

    Result(String response, List<NamedArtifact> artifacts) {
      this.response = response;
      this.artifacts = artifacts;
    }
  }

  /** A sub-agent artifact after namespacing, ready for the tool output. */
  public static final class NamedArtifact {
    public final String name;
    public final String content;

    NamedArtifact(String name, String content) {
      this.name = name;
      this.content = content;
    }
  }

  /**
   * Runs the sub-agent named {@code agentName} for a single turn with {@code task} as the user
   * message, merges any produced artifacts (namespaced by a fresh invocation id) into the active
   * parent session, and returns the sub-agent's text plus the (namespaced) artifacts.
   *
   * <p>Up to {@code historyLength} prior messages from this (parent session, sub-agent) pair's own
   * accumulated conversation are forwarded to the sub-agent (see class Javadoc for the
   * client-managed vs. server-managed mechanics). A {@code historyLength <= 0} means no history is
   * forwarded (task-only), matching the pre-fix behavior.
   *
   * @param ctx the calling tool's action context (provides the registry)
   * @param agentName the bare sub-agent name (looked up at {@code /agent/<name>})
   * @param task the task text to send as the sub-agent's user message
   * @param includeArtifactContent whether to include artifact content in the returned artifacts
   *     ({@link com.google.genkit.plugins.middleware.ArtifactStrategy#INLINE}) or names only
   *     ({@link com.google.genkit.plugins.middleware.ArtifactStrategy#SESSION})
   * @param historyLength the number of trailing prior messages to forward to the sub-agent ({@code
   *     <= 0} means task-only, no history forwarded)
   * @return the delegation result
   * @throws ToolInterruptException if the sub-agent's turn finished {@code INTERRUPTED}; carries
   *     the sub-agent's interrupted tool name/input as metadata so the parent's own generate loop
   *     pauses too
   * @throws GenkitException if the sub-agent's turn finished {@code FAILED}, or the sub-agent's
   *     {@code runBidiJson} call itself threw
   */
  public static Result run(
      ActionContext ctx,
      String agentName,
      String task,
      boolean includeArtifactContent,
      int historyLength) {
    Registry registry = ctx.getRegistry();
    Action<?, ?, ?> action = registry.lookupAction(ActionType.AGENT.keyFromName(agentName));
    if (action == null) {
      return new Result(
          "Error: sub-agent '" + agentName + "' is not registered.", new ArrayList<>());
    }
    if (!(action instanceof BidiAction)) {
      return new Result(
          "Error: '" + agentName + "' is not a bidirectional agent action.", new ArrayList<>());
    }
    BidiAction<?, ?, ?, ?> agent = (BidiAction<?, ?, ?, ?>) action;

    boolean serverManaged = isServerManaged(agent);
    String subSessionKey = subSessionKey(agentName);

    // Build a one-shot input source carrying the task as a single user turn.
    AgentInput input = AgentInput.builder().message(Message.user(task != null ? task : "")).build();
    BufferedInputSource<JsonNode> inputs = new BufferedInputSource<>();
    inputs.offer(JsonUtils.toJsonNode(input));
    inputs.end();

    AgentInit<Object> init = new AgentInit<>();
    List<Message> forwardedHistory = null;
    if (serverManaged) {
      // Server-managed: resume the sub-agent's own persistent sub-session by sessionId so its
      // store naturally accumulates history across repeated delegation calls (see class Javadoc).
      init.setSessionId(subSessionKey);
    } else if (historyLength > 0) {
      // Client-managed: hydrate from our own ledger, trimmed to historyLength.
      forwardedHistory = trimTrailing(CLIENT_HISTORY.get(subSessionKey), historyLength);
      if (!forwardedHistory.isEmpty()) {
        SessionState<Object> state = new SessionState<>();
        state.setMessages(forwardedHistory);
        init.setState(state);
      }
    }
    JsonNode initJson = JsonUtils.toJsonNode(init);

    JsonNode outJson;
    try {
      outJson = agent.runBidiJson(ctx, initJson, inputs, chunk -> {});
    } catch (Exception e) {
      throw new GenkitException("Sub-agent '" + agentName + "' failed: " + e.getMessage(), e);
    }

    AgentOutput<?> output = JsonUtils.fromJsonNode(outJson, AgentOutput.class);

    // Update the client-managed history ledger with this turn's full message history (task +
    // response), trimmed to historyLength, ready for the next delegation call to this sub-agent.
    if (!serverManaged && historyLength > 0) {
      List<Message> updated =
          output != null && output.getState() != null ? output.getState().getMessages() : null;
      if (updated == null) {
        // Fall back to reconstructing task + response if the sub-agent didn't return state.
        updated = new ArrayList<>();
        if (forwardedHistory != null) {
          updated.addAll(forwardedHistory);
        }
        updated.add(input.getMessage());
        if (output != null && output.getMessage() != null) {
          updated.add(output.getMessage());
        }
      }
      CLIENT_HISTORY.put(subSessionKey, trimTrailing(updated, historyLength));
    }

    throwIfInterruptedOrFailed(agentName, output);

    String response = extractResponse(output);

    // Namespace + merge artifacts into the parent session.
    List<NamedArtifact> namespaced = new ArrayList<>();
    if (output != null && output.getArtifacts() != null && !output.getArtifacts().isEmpty()) {
      String invocationId = UUID.randomUUID().toString();
      ArtifactStore parentStore = AgentSessionContext.currentArtifactStore();
      for (Artifact a : output.getArtifacts()) {
        String baseName = a.getName() != null ? a.getName() : "artifact";
        String namespacedName = invocationId + "/" + baseName;
        Artifact merged =
            Artifact.builder()
                .name(namespacedName)
                .parts(a.getParts())
                .metadata(a.getMetadata())
                .build();
        if (parentStore != null) {
          parentStore.addArtifacts(merged);
        }
        namespaced.add(
            new NamedArtifact(namespacedName, includeArtifactContent ? textOf(a) : null));
      }
    }

    return new Result(response, namespaced);
  }

  /**
   * Derives the stable sub-session key for a (parent session, sub-agent) pair: the currently bound
   * parent session id (or a fixed fallback if no {@link AgentSessionContext} is bound) combined
   * with the sub-agent's name. Repeated delegation to the SAME sub-agent within the SAME parent
   * session resumes the same sub-session; a different parent session or a different sub-agent name
   * gets an independent one.
   */
  private static String subSessionKey(String agentName) {
    Session<?> parent = AgentSessionContext.current();
    String parentSessionId = parent != null ? parent.sessionId() : "no-parent-session";
    return parentSessionId + "::" + agentName;
  }

  /**
   * Returns {@code true} if the resolved agent action reports server-managed state. {@link Agent}
   * is the only implementation produced by {@code AgentActions.defineCustomAgent} and exposes
   * {@link Agent#serverManaged()} directly.
   */
  private static boolean isServerManaged(BidiAction<?, ?, ?, ?> agent) {
    return agent instanceof Agent && ((Agent<?>) agent).serverManaged();
  }

  /** Returns a new list containing at most the last {@code n} elements of {@code list}. */
  private static List<Message> trimTrailing(List<Message> list, int n) {
    if (list == null || list.isEmpty() || n <= 0) {
      return new ArrayList<>();
    }
    if (list.size() <= n) {
      return new ArrayList<>(list);
    }
    return new ArrayList<>(list.subList(list.size() - n, list.size()));
  }

  /**
   * Throws a structured exception for {@code INTERRUPTED} and {@code FAILED} sub-agent turns so
   * they propagate through the parent's own generate/tool-calling loop instead of collapsing to
   * plain text.
   *
   * <p><b>INTERRUPTED</b>: throws {@link ToolInterruptException} carrying the sub-agent's
   * interrupted tool name/input (extracted from the final message's tool-request part) as metadata,
   * mirroring the convention used by {@code Tool.run}'s catch/rethrow of the same exception type.
   * This makes the PARENT's generate loop stop with {@code FinishReason.INTERRUPTED} too, exactly
   * as if the parent had called an interrupting tool directly. Scope decision: this fix surfaces
   * the interrupt structurally to the parent; it does not implement an end-to-end nested-resume
   * path (resuming the parent's interrupt does not automatically re-invoke the sub-agent with a
   * resume) — see {@code Agents}/{@code AgentsOptions} javadoc and the fix-middleware report for
   * the full rationale.
   *
   * <p><b>FAILED</b>: throws {@link GenkitException} carrying the sub-agent's actual error message,
   * matching how {@code Tool.run} wraps a handler's checked failure and how {@code Genkit.generate}
   * treats any other tool exception (folded into a {@code ToolResponse} error, not propagated as a
   * structured interrupt).
   */
  private static void throwIfInterruptedOrFailed(String agentName, AgentOutput<?> output) {
    if (output == null) {
      return;
    }
    AgentFinishReason reason = output.getFinishReason();
    if (reason == AgentFinishReason.INTERRUPTED) {
      Map<String, Object> metadata = new HashMap<>();
      Part toolRequestPart = findToolRequestPart(output.getMessage());
      if (toolRequestPart != null && toolRequestPart.getToolRequest() != null) {
        metadata.put("name", toolRequestPart.getToolRequest().getName());
        metadata.put("input", toolRequestPart.getToolRequest().getInput());
      }
      metadata.put("subAgent", agentName);
      String text = output.getMessage() != null ? output.getMessage().getText() : null;
      String message =
          "Sub-agent '"
              + agentName
              + "' was interrupted before completing"
              + (text != null && !text.isEmpty() ? ": " + text : ".");
      throw new ToolInterruptException(message, metadata);
    }
    if (reason == AgentFinishReason.FAILED) {
      String err =
          output.getError() != null ? String.valueOf(output.getError().getMessage()) : null;
      String text = output.getMessage() != null ? output.getMessage().getText() : null;
      String detail = err != null ? err : text;
      throw new GenkitException(
          "Sub-agent '"
              + agentName
              + "' failed"
              + (detail != null && !detail.isEmpty() ? ": " + detail : "."));
    }
  }

  /** Finds the first tool-request part in a message's content, or {@code null} if none. */
  private static Part findToolRequestPart(Message message) {
    if (message == null || message.getContent() == null) {
      return null;
    }
    for (Part p : message.getContent()) {
      if (p.getToolRequest() != null) {
        return p;
      }
    }
    return null;
  }

  /** Builds the textual response for a normal (STOP/etc.) sub-agent turn. */
  private static String extractResponse(AgentOutput<?> output) {
    if (output == null) {
      return "Sub-agent produced no output.";
    }
    String text = output.getMessage() != null ? output.getMessage().getText() : null;
    return text != null ? text : "";
  }

  /** Concatenates the text of all text parts of an artifact. */
  private static String textOf(Artifact artifact) {
    StringBuilder sb = new StringBuilder();
    if (artifact.getParts() != null) {
      for (Part p : artifact.getParts()) {
        if (p.getText() != null) {
          sb.append(p.getText());
        }
      }
    }
    return sb.toString();
  }
}
