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

import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AgentResponse is the ergonomic wrapper {@link AgentChat#send} returns for one completed turn.
 *
 * <p>It surfaces the assistant {@link Message} and convenience views derived from it (text,
 * tool-request parts, interrupts) alongside the turn's identifiers, custom state, artifacts, finish
 * reason, and the raw {@link AgentOutput}.
 *
 * @param <S> the type of custom session state
 */
public final class AgentResponse<S> {

  private final AgentOutput<S> raw;
  private final S state;

  /**
   * Constructs an AgentResponse.
   *
   * @param raw the underlying agent output for the turn
   * @param state the custom state after the turn (server-managed agents do not echo full state, so
   *     the chat supplies its tracked copy here); may be {@code null}
   */
  AgentResponse(AgentOutput<S> raw, S state) {
    this.raw = raw;
    this.state = state;
  }

  /**
   * Returns the assistant message produced by the turn.
   *
   * @return the message, or {@code null} if the turn produced none
   */
  public Message message() {
    return raw != null ? raw.getMessage() : null;
  }

  /**
   * Returns the concatenated text of the assistant message's text parts.
   *
   * @return the text (empty string if there is no message / no text parts)
   */
  public String text() {
    Message m = message();
    return m != null ? m.getText() : "";
  }

  /**
   * Returns the tool-request parts of the assistant message.
   *
   * @return an unmodifiable list of tool-request parts (possibly empty)
   */
  public List<Part> toolRequests() {
    Message m = message();
    if (m == null || m.getContent() == null) {
      return Collections.emptyList();
    }
    List<Part> out = new ArrayList<>();
    for (Part p : m.getContent()) {
      if (p != null && p.isToolRequest()) {
        out.add(p);
      }
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * Returns the interrupts for this turn.
   *
   * <p>Best-effort: when the turn finished with {@link AgentFinishReason#INTERRUPTED}, every
   * tool-request part of the assistant message is reported as an interrupt. The wire {@link Part}
   * does not currently carry an explicit interrupt flag, so all pending tool requests are treated
   * as interrupts. When the turn did not finish interrupted, this returns an empty list.
   *
   * @return an unmodifiable list of interrupts (possibly empty)
   */
  public List<AgentInterrupt> interrupts() {
    if (finishReason() != AgentFinishReason.INTERRUPTED) {
      return Collections.emptyList();
    }
    List<AgentInterrupt> out = new ArrayList<>();
    for (Part p : toolRequests()) {
      out.add(AgentInterrupt.fromPart(p));
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * Returns the turn's finish reason.
   *
   * @return the finish reason, or {@code null}
   */
  public AgentFinishReason finishReason() {
    return raw != null ? raw.getFinishReason() : null;
  }

  /**
   * Returns the snapshot ID produced by the turn (server-managed agents).
   *
   * @return the snapshot ID, or {@code null}
   */
  public String snapshotId() {
    return raw != null ? raw.getSnapshotId() : null;
  }

  /**
   * Returns the session ID.
   *
   * @return the session ID, or {@code null}
   */
  public String sessionId() {
    return raw != null ? raw.getSessionId() : null;
  }

  /**
   * Returns the custom session state after the turn.
   *
   * @return the custom state, or {@code null}
   */
  public S custom() {
    return state;
  }

  /**
   * Returns the full session state at the end of the turn.
   *
   * <p>For client-managed agents this is the inline state echoed by the agent. For server-managed
   * agents the agent does not echo full state inline, so this returns {@code null}; use {@link
   * #snapshotId()} with {@link AgentChat} / {@code getSnapshotData} to read server-side state.
   *
   * @return the session state, or {@code null}
   */
  public SessionState<S> state() {
    return raw != null ? raw.getState() : null;
  }

  /**
   * Returns the turn's artifacts.
   *
   * @return an unmodifiable list of artifacts (possibly empty)
   */
  public List<Artifact> artifacts() {
    List<Artifact> a = raw != null ? raw.getArtifacts() : null;
    return a != null ? Collections.unmodifiableList(a) : Collections.emptyList();
  }

  /**
   * Returns the underlying raw agent output.
   *
   * @return the raw output
   */
  public AgentOutput<S> raw() {
    return raw;
  }
}
