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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.jsonpatch.JsonPatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * AgentChat is the ergonomic programmatic client for driving an agent across multiple turns while
 * carrying session state automatically.
 *
 * <p>Each {@link #send} is exactly one invocation of the agent's bidi action (one-turn-per-send).
 * The chat tracks the evolving {@code snapshotId} / {@code sessionId} / custom state / messages /
 * artifacts so the next send resumes correctly. Resume is carried in the per-turn {@link
 * AgentInit}:
 *
 * <ul>
 *   <li><b>Server-managed</b> agents persist state server-side; the init carries the latest {@code
 *       snapshotId} (and {@code sessionId} once known) so the server resumes from there.
 *   <li><b>Client-managed</b> agents do not persist; the init carries the full {@link SessionState}
 *       (sessionId, messages, custom, artifacts) so the agent rehydrates from it each turn.
 * </ul>
 *
 * <p>Instances are created via {@link Agent#chat(com.google.genkit.core.ActionContext)} / {@link
 * Agent#chat(com.google.genkit.core.ActionContext, AgentInit)} / {@link
 * Agent#loadChat(com.google.genkit.core.ActionContext, GetSnapshotRequest)}.
 *
 * <p>Not thread-safe: a chat is a single conversation and is expected to be driven from one thread.
 *
 * @param <S> the type of custom session state
 */
public final class AgentChat<S> {

  private final AgentTransport<S> transport;
  private final boolean serverManaged;

  private String snapshotId;
  private String sessionId;
  private S state;
  private final List<Message> messages = new ArrayList<>();
  private final List<Artifact> artifacts = new ArrayList<>();

  /**
   * Creates a chat over any transport. External code (e.g. {@code RemoteAgent}) uses this factory
   * so they do not need to be in the same package.
   *
   * @param <S> the type of custom session state
   * @param transport the transport that runs each turn (must not be null)
   * @param init optional seed init; may be {@code null} for a fresh chat
   * @return a new {@link AgentChat} backed by {@code transport}
   */
  public static <S> AgentChat<S> over(AgentTransport<S> transport, AgentInit<S> init) {
    return new AgentChat<>(transport, init);
  }

  /**
   * Constructs a chat. Called by {@link Agent} and {@link #over}.
   *
   * @param transport the transport that runs each turn (must not be null)
   * @param init optional seed init: snapshotId / sessionId / inline state to start from; may be
   *     {@code null} for a fresh chat
   */
  AgentChat(AgentTransport<S> transport, AgentInit<S> init) {
    this.transport = transport;
    this.serverManaged = transport.serverManaged();
    if (init != null) {
      this.snapshotId = init.getSnapshotId();
      this.sessionId = init.getSessionId();
      SessionState<S> seed = init.getState();
      if (seed != null) {
        hydrateFromState(seed);
      }
    }
  }

  /** Hydrates this chat's tracked state from a {@link SessionState}. */
  private void hydrateFromState(SessionState<S> seed) {
    if (seed.getSessionId() != null) {
      this.sessionId = seed.getSessionId();
    }
    this.state = seed.getCustom();
    this.messages.clear();
    if (seed.getMessages() != null) {
      this.messages.addAll(seed.getMessages());
    }
    this.artifacts.clear();
    if (seed.getArtifacts() != null) {
      this.artifacts.addAll(seed.getArtifacts());
    }
  }

  /**
   * Hydrates this chat from a snapshot (used by {@code loadChat}).
   *
   * @param snap the snapshot to load (may be {@code null})
   */
  void loadSnapshot(SessionSnapshot<S> snap) {
    if (snap == null) {
      return;
    }
    this.snapshotId = snap.getSnapshotId();
    if (snap.getSessionId() != null) {
      this.sessionId = snap.getSessionId();
    }
    if (snap.getState() != null) {
      hydrateFromState(snap.getState());
    }
  }

  // ── send ───────────────────────────────────────────────────────────────────────

  /**
   * Sends a user text message and returns the turn's response.
   *
   * @param text the user message text
   * @return the response
   */
  public AgentResponse<S> send(String text) {
    return send(AgentInput.builder().message(Message.user(text)).build());
  }

  /**
   * Sends a fully-formed input and returns the turn's response.
   *
   * @param input the turn input
   * @return the response
   */
  public AgentResponse<S> send(AgentInput input) {
    return sendStream(input, c -> {});
  }

  /**
   * Sends a user text message, streaming chunks to {@code onChunk}, and returns the final response.
   *
   * @param text the user message text
   * @param onChunk per-chunk callback (may be a no-op)
   * @return the response
   */
  public AgentResponse<S> sendStream(String text, Consumer<AgentChunk<S>> onChunk) {
    return sendStream(AgentInput.builder().message(Message.user(text)).build(), onChunk);
  }

  /**
   * Sends a fully-formed input, streaming chunks to {@code onChunk}, and returns the final
   * response.
   *
   * @param input the turn input
   * @param onChunk per-chunk callback (may be a no-op)
   * @return the response
   */
  public AgentResponse<S> sendStream(AgentInput input, Consumer<AgentChunk<S>> onChunk) {
    // Optimistically track the user message we send (server-managed agents do not echo full state).
    Message userMessage = input != null ? input.getMessage() : null;

    // Running custom-state document for chunk.custom(): seeded from the chat's current custom
    // state. The first customPatch of a turn is a whole-document replace, so this tracks the
    // authoritative custom state for server-managed agents (which do not echo full state inline).
    final JsonNode[] runningCustom = {JsonUtils.toJsonNode(state)};
    final boolean[] sawCustomPatch = {false};

    AgentInit<S> init = buildInit();
    AgentOutput<S> output =
        transport.runTurn(
            input,
            init,
            raw -> {
              S chunkCustom = null;
              if (raw != null && raw.getCustomPatch() != null) {
                JsonNode patched = JsonPatch.apply(runningCustom[0], raw.getCustomPatch());
                runningCustom[0] = patched;
                sawCustomPatch[0] = true;
                chunkCustom = deserializeCustom(patched);
              }
              if (onChunk != null) {
                onChunk.accept(new AgentChunk<>(raw, chunkCustom));
              }
            });

    S streamedCustom = sawCustomPatch[0] ? deserializeCustom(runningCustom[0]) : null;
    applyOutput(output, userMessage, streamedCustom, sawCustomPatch[0]);
    return new AgentResponse<>(output, state);
  }

  /**
   * Resumes a paused turn by responding to its interrupts.
   *
   * @param respond the response parts
   * @return the response
   */
  public AgentResponse<S> resume(List<Part> respond) {
    ToolResume tr = ToolResume.builder().respond(respond).build();
    return send(AgentInput.builder().resume(tr).build());
  }

  /**
   * Resumes a paused turn by restarting its interrupted tool requests.
   *
   * <p>Each restart part is a tool-request part (typically produced by {@code Tool.restart(...)})
   * carrying the resumed metadata; the tool is re-executed with that metadata so a restart-aware
   * handler can observe its resumed status via {@code ActionContext.isResumed()}/{@code
   * getResumed()}. Sibling to {@link #resume(List)}.
   *
   * @param restart the restart tool-request parts
   * @return the response
   */
  public AgentResponse<S> restart(List<Part> restart) {
    ToolResume tr = ToolResume.builder().restart(restart).build();
    return send(AgentInput.builder().resume(tr).build());
  }

  // ── lifecycle ────────────────────────────────────────────────────────────────

  /**
   * Aborts the latest pending snapshot for this chat.
   *
   * @return the resulting status, or {@code null} if there is no snapshot / abort is unsupported
   */
  public SnapshotStatus abort() {
    if (snapshotId == null) {
      return null;
    }
    return transport.abort(snapshotId);
  }

  // ── accessors ────────────────────────────────────────────────────────────────

  /**
   * Returns the latest snapshot ID (server-managed agents).
   *
   * @return the snapshot ID, or {@code null} before the first turn / for client-managed agents
   */
  public String snapshotId() {
    return snapshotId;
  }

  /**
   * Returns the session ID.
   *
   * @return the session ID, or {@code null} before it is known
   */
  public String sessionId() {
    return sessionId;
  }

  /**
   * Returns the current custom session state.
   *
   * @return the custom state, or {@code null}
   */
  public S state() {
    return state;
  }

  /**
   * Returns the accumulated conversation messages.
   *
   * @return an unmodifiable view of the messages
   */
  public List<Message> messages() {
    return Collections.unmodifiableList(messages);
  }

  /**
   * Returns the accumulated artifacts.
   *
   * @return an unmodifiable view of the artifacts
   */
  public List<Artifact> artifacts() {
    return Collections.unmodifiableList(artifacts);
  }

  // ── internals ────────────────────────────────────────────────────────────────

  /**
   * Builds the {@link AgentInit} for the next turn from the chat's tracked state.
   *
   * <p>Server-managed: carry {@code snapshotId} (and {@code sessionId} when known) so the server
   * resumes; first turn → empty init. Client-managed: carry the full current {@link SessionState}.
   */
  private AgentInit<S> buildInit() {
    if (serverManaged) {
      if (snapshotId == null && sessionId == null) {
        return null; // fresh session
      }
      AgentInit.Builder<S> b = AgentInit.builder();
      if (snapshotId != null) {
        b.snapshotId(snapshotId);
      }
      if (sessionId != null) {
        b.sessionId(sessionId);
      }
      return b.build();
    }
    // client-managed: round-trip the full state
    if (sessionId == null && messages.isEmpty() && state == null && artifacts.isEmpty()) {
      return null; // fresh session
    }
    SessionState<S> current =
        SessionState.<S>builder()
            .sessionId(sessionId)
            .messages(new ArrayList<>(messages))
            .custom(state)
            .artifacts(new ArrayList<>(artifacts))
            .build();
    return AgentInit.<S>builder().state(current).build();
  }

  /**
   * Folds a turn's output back into the chat's tracked state so the next send resumes correctly.
   *
   * @param output the turn output
   * @param userMessage the user message that was sent this turn (for server-managed history
   *     tracking); may be {@code null}
   * @param streamedCustom custom state reconstructed from this turn's customPatch stream; used for
   *     server-managed agents which do not echo full state inline; may be {@code null}
   * @param sawCustomPatch whether any customPatch was observed during the turn
   */
  private void applyOutput(
      AgentOutput<S> output, Message userMessage, S streamedCustom, boolean sawCustomPatch) {
    if (output == null) {
      return;
    }
    if (output.getSnapshotId() != null) {
      this.snapshotId = output.getSnapshotId();
    }
    if (output.getSessionId() != null) {
      this.sessionId = output.getSessionId();
    }

    SessionState<S> outState = output.getState();
    if (outState != null) {
      // Client-managed: the agent echoes the authoritative full state.
      hydrateFromState(outState);
    } else {
      // Server-managed: no inline state. Track history locally by appending the user message we
      // sent and the assistant message the agent returned, and merge any artifacts.
      if (userMessage != null) {
        this.messages.add(userMessage);
      }
      if (output.getMessage() != null) {
        this.messages.add(output.getMessage());
      }
      if (output.getArtifacts() != null) {
        mergeArtifacts(output.getArtifacts());
      }
      // The customPatch stream is authoritative for custom state in server-managed mode.
      if (sawCustomPatch) {
        this.state = streamedCustom;
      }
    }
  }

  /** Merges turn artifacts into the tracked list, replacing same-named entries. */
  private void mergeArtifacts(List<Artifact> incoming) {
    for (Artifact a : incoming) {
      if (a == null) {
        continue;
      }
      int idx = -1;
      for (int i = 0; i < artifacts.size(); i++) {
        if (a.getName() != null && a.getName().equals(artifacts.get(i).getName())) {
          idx = i;
          break;
        }
      }
      if (idx >= 0) {
        artifacts.set(idx, a);
      } else {
        artifacts.add(a);
      }
    }
  }

  /** Deserializes a custom-state JsonNode into {@code S} (raw-type round-trip). */
  @SuppressWarnings("unchecked")
  private S deserializeCustom(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    try {
      return (S) JsonUtils.getObjectMapper().treeToValue(node, Object.class);
    } catch (Exception e) {
      return null;
    }
  }
}
