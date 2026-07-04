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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.Message;
import com.google.genkit.core.JsonUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Session is the per-invocation in-memory state holder managed by the agent runtime. It stores
 * messages, custom state, and artifacts; provides mutation methods that increment a version counter
 * and fire listener callbacks; and implements {@link ArtifactStore} for middleware/tools that do
 * not need to know the custom state type.
 *
 * <p>Thread-safety: the runtime drives a session from one turn at a time. Mutations are {@code
 * synchronized} to guard against concurrent reads in edge cases, but over-engineering with
 * lock-free structures is intentionally avoided.
 *
 * @param <S> the type of the custom state object
 */
public final class Session<S> implements ArtifactStore {

  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  private final String sessionId;

  /** Internal messages list — never exposed directly. */
  private final List<Message> messages;

  /** Current custom state. */
  private S custom;

  /** Internal artifacts list — never exposed directly. */
  private final List<Artifact> artifacts;

  /** Monotonically increasing mutation counter. */
  private long version;

  /** Callback fired after updateCustom completes. */
  private Runnable onCustomChanged;

  /** Callback fired per artifact added or updated. */
  private Consumer<Artifact> onArtifactChanged;

  /**
   * Constructs a new Session from the given initial state. Deep-copies {@code initialState} so that
   * subsequent external mutations to the original object cannot affect this session. If {@code
   * initialState.sessionId} is null or empty, a new UUID is minted.
   *
   * @param initialState the initial session state (must not be null)
   */
  public Session(SessionState<S> initialState) {
    // Mint or preserve sessionId
    String sid = initialState.getSessionId();
    this.sessionId = (sid != null && !sid.isEmpty()) ? sid : UUID.randomUUID().toString();

    // Deep-copy messages
    List<Message> srcMessages = initialState.getMessages();
    this.messages = srcMessages != null ? deepCopyMessages(srcMessages) : new ArrayList<>();

    // Custom state: a shallow reference is acceptable for simple values; for Map/POJO we rely on
    // the caller providing an immutable or properly-owned object. Deep-copy via ObjectMapper is
    // used to avoid aliasing issues when S is a Map or POJO.
    this.custom = deepCopyCustom(initialState.getCustom());

    // Deep-copy artifacts
    List<Artifact> srcArtifacts = initialState.getArtifacts();
    this.artifacts = srcArtifacts != null ? deepCopyArtifacts(srcArtifacts) : new ArrayList<>();

    this.version = 0L;
  }

  // ---- Identifiers ----

  /**
   * Returns the session ID.
   *
   * @return the session ID (never null)
   */
  public String sessionId() {
    return sessionId;
  }

  // ---- Version ----

  /**
   * Returns the current mutation version counter. Increments on every mutating operation.
   *
   * @return the version
   */
  public synchronized long getVersion() {
    return version;
  }

  // ---- State snapshot ----

  /**
   * Returns a deep copy of the current session state. Callers must not mutate the returned object
   * to avoid leaking changes back into the session.
   *
   * @return a deep copy of the current {@link SessionState}
   */
  public synchronized SessionState<S> getState() {
    SessionState<S> snapshot = new SessionState<>();
    snapshot.setSessionId(sessionId);
    snapshot.setMessages(new ArrayList<>(messages));
    snapshot.setCustom(deepCopyCustom(custom));
    snapshot.setArtifacts(new ArrayList<>(artifacts));
    return snapshot;
  }

  // ---- Messages ----

  /**
   * Returns a copy of the current messages list. Mutating the returned list does not affect the
   * session.
   *
   * @return a copy of the messages (never null)
   */
  public synchronized List<Message> getMessages() {
    return new ArrayList<>(messages);
  }

  /**
   * Appends the given messages to the session.
   *
   * @param msgs messages to add
   */
  public synchronized void addMessages(Message... msgs) {
    messages.addAll(Arrays.asList(msgs));
    version++;
  }

  /**
   * Appends the given messages to the session.
   *
   * @param msgs messages to add (must not be null)
   */
  public synchronized void addMessages(List<Message> msgs) {
    messages.addAll(msgs);
    version++;
  }

  /**
   * Replaces the session's message list with a copy of the given list.
   *
   * @param msgs the new messages (must not be null)
   */
  public synchronized void setMessages(List<Message> msgs) {
    messages.clear();
    messages.addAll(msgs);
    version++;
  }

  /**
   * Applies {@code fn} to the current messages list and stores the result.
   *
   * @param fn the update function
   */
  public synchronized void updateMessages(UnaryOperator<List<Message>> fn) {
    List<Message> updated = fn.apply(new ArrayList<>(messages));
    messages.clear();
    if (updated != null) {
      messages.addAll(updated);
    }
    version++;
  }

  // ---- Custom state ----

  /**
   * Returns a deep copy of the current custom state. Mutating the returned value does not affect
   * the session — prefer {@link #updateCustom} for modifications.
   *
   * @return a deep copy of the current custom state, or null if not set
   */
  public synchronized S getCustom() {
    return deepCopyCustom(custom);
  }

  /**
   * Applies {@code fn} to the current custom state, stores the result, increments the version, and
   * fires the {@code onCustomChanged} listener.
   *
   * @param fn the update function
   */
  public synchronized void updateCustom(UnaryOperator<S> fn) {
    custom = fn.apply(custom);
    version++;
    if (onCustomChanged != null) {
      onCustomChanged.run();
    }
  }

  // ---- Artifacts (ArtifactStore implementation) ----

  /**
   * Returns a copy of the current artifacts list. Mutating the returned list does not affect the
   * session.
   *
   * @return a copy of the artifacts (never null)
   */
  @Override
  public synchronized List<Artifact> getArtifacts() {
    return new ArrayList<>(artifacts);
  }

  /**
   * Adds artifacts with deduplication by name. If an artifact with the same non-null name already
   * exists, it is replaced in place. Artifacts with null names are always appended. Fires the
   * {@code onArtifactChanged} listener for each artifact added or updated.
   *
   * @param arts artifacts to add
   */
  @Override
  public synchronized void addArtifacts(Artifact... arts) {
    addArtifactList(Arrays.asList(arts));
  }

  /**
   * Adds artifacts with deduplication by name (list overload).
   *
   * @param arts artifacts to add (must not be null)
   */
  public synchronized void addArtifacts(List<Artifact> arts) {
    addArtifactList(arts);
  }

  private void addArtifactList(List<Artifact> arts) {
    for (Artifact incoming : arts) {
      String name = incoming.getName();
      if (name != null) {
        // Replace in place if name matches
        boolean replaced = false;
        for (int i = 0; i < artifacts.size(); i++) {
          if (name.equals(artifacts.get(i).getName())) {
            artifacts.set(i, incoming);
            replaced = true;
            break;
          }
        }
        if (!replaced) {
          artifacts.add(incoming);
        }
      } else {
        // Null name: always append
        artifacts.add(incoming);
      }
      version++;
      if (onArtifactChanged != null) {
        onArtifactChanged.accept(incoming);
      }
    }
  }

  /**
   * Applies {@code fn} to the current artifacts list and stores the result.
   *
   * @param fn the update function
   */
  public synchronized void updateArtifacts(UnaryOperator<List<Artifact>> fn) {
    List<Artifact> updated = fn.apply(new ArrayList<>(artifacts));
    artifacts.clear();
    if (updated != null) {
      artifacts.addAll(updated);
    }
    version++;
  }

  // ---- Listener hooks ----

  /**
   * Sets the callback to be invoked after {@link #updateCustom} completes.
   *
   * @param cb the callback (may be null to clear)
   */
  public synchronized void setOnCustomChanged(Runnable cb) {
    this.onCustomChanged = cb;
  }

  /**
   * Sets the callback to be invoked for each artifact added or updated via {@link #addArtifacts}.
   *
   * @param cb the callback (may be null to clear)
   */
  public synchronized void setOnArtifactChanged(Consumer<Artifact> cb) {
    this.onArtifactChanged = cb;
  }

  // ---- Deep-copy helpers ----

  @SuppressWarnings("unchecked")
  private S deepCopyCustom(S value) {
    if (value == null) {
      return null;
    }
    try {
      // Serialize to JSON and back to obtain a disconnected copy.
      String json = MAPPER.writeValueAsString(value);
      return (S) MAPPER.readValue(json, value.getClass());
    } catch (Exception e) {
      // Inner generic type params are erased here; complex parameterized custom state may not
      // be fully deep-copied.
      // Fall back to returning the original reference if serialization is not possible.
      return value;
    }
  }

  private List<Message> deepCopyMessages(List<Message> src) {
    try {
      String json = MAPPER.writeValueAsString(src);
      return MAPPER.readValue(json, new TypeReference<List<Message>>() {});
    } catch (Exception e) {
      return new ArrayList<>(src);
    }
  }

  private List<Artifact> deepCopyArtifacts(List<Artifact> src) {
    try {
      String json = MAPPER.writeValueAsString(src);
      return MAPPER.readValue(json, new TypeReference<List<Artifact>>() {});
    } catch (Exception e) {
      return new ArrayList<>(src);
    }
  }
}
