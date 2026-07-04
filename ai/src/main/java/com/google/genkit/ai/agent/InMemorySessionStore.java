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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.agent.internal.LeafSelection;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Reference in-memory implementation of {@link SessionStore} and {@link SnapshotSubscriber}.
 *
 * <p>Used by conformance tests and as the simplest full implementation of the {@code saveSnapshot}
 * read-modify-write contract.
 *
 * <h3>Deep-copy strategy</h3>
 *
 * <p>Snapshots are deep-copied on both write (store) and read (return) using a JSON round-trip via
 * the shared {@link ObjectMapper} ({@code valueToTree} → {@code treeToValue(node,
 * SessionSnapshot.class)}). Because of Java's type erasure, the round-trip uses the raw type {@code
 * SessionSnapshot} (without the {@code <S>} parameter), so the {@code custom} field of {@link
 * SessionState} will be deserialized as a {@link java.util.LinkedHashMap} rather than as {@code S}.
 * This is acceptable for the in-memory store used dynamically (conformance tests use {@code
 * Map<String,Object>} state).
 *
 * <h3>Subscriber notification</h3>
 *
 * <p>Callbacks are collected under the lock and invoked <em>after</em> releasing the lock to
 * prevent deadlock if a callback re-enters the store (e.g. to read the new snapshot).
 *
 * <h3>Subscriber semantics (Go-compatible)</h3>
 *
 * <p>If the snapshot is already present when {@link #onSnapshotStateChange} is called, the callback
 * is <em>not</em> invoked immediately. The callback fires only when a subsequent {@link
 * #saveSnapshot} causes a status change (including the first save when existing is {@code null}).
 *
 * @param <S> the type of custom session state
 */
public final class InMemorySessionStore<S> implements SessionStore<S>, SnapshotSubscriber {

  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  /**
   * Backing store: snapshotId → raw (erased) SessionSnapshot. We use the raw type internally
   * because the JSON round-trip erases {@code <S>}, and the store must hold snapshots of a single
   * type anyway.
   */
  @SuppressWarnings("rawtypes")
  private final Map<String, SessionSnapshot> snapshots = new HashMap<>();

  /** Subscriber registry: snapshotId → list of callbacks. */
  private final Map<String, List<Consumer<SessionSnapshot<?>>>> subscribers = new HashMap<>();

  private final boolean rejectBranchingSessions;

  /**
   * Creates a new {@code InMemorySessionStore} with {@code rejectBranching} defaulting to false.
   */
  public InMemorySessionStore() {
    this(false);
  }

  /**
   * Creates a new {@code InMemorySessionStore}.
   *
   * @param rejectBranchingSessions if {@code true}, {@link LeafSelection#selectLeaf} will throw
   *     when more than one leaf is detected for a session
   */
  public InMemorySessionStore(boolean rejectBranchingSessions) {
    this.rejectBranchingSessions = rejectBranchingSessions;
  }

  // ── SnapshotReader ────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>When {@link GetSnapshotOptions#getSnapshotId()} is set, returns a deep copy of the stored
   * snapshot (or {@code null} if absent). When {@link GetSnapshotOptions#getSessionId()} is set,
   * gathers all snapshots whose {@code sessionId} equals it, applies {@link LeafSelection}, and
   * returns a deep copy of the result. If neither id is set, returns {@code null}.
   */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public SessionSnapshot<S> getSnapshot(GetSnapshotOptions opts) {
    if (opts == null) {
      return null;
    }

    synchronized (this) {
      if (opts.getSnapshotId() != null) {
        SessionSnapshot stored = snapshots.get(opts.getSnapshotId());
        return stored == null ? null : deepCopy(stored);
      }

      if (opts.getSessionId() != null) {
        String targetSessionId = opts.getSessionId();
        List<SessionSnapshot<S>> matching =
            snapshots.values().stream()
                .filter(
                    s -> {
                      // sessionId can be on the snapshot itself or on its state
                      String sid = s.getSessionId();
                      if (sid == null && s.getState() != null) {
                        sid = s.getState().getSessionId();
                      }
                      return targetSessionId.equals(sid);
                    })
                .map(s -> (SessionSnapshot<S>) s)
                .collect(Collectors.toList());

        if (matching.isEmpty()) {
          return null;
        }
        SessionSnapshot<S> leaf = LeafSelection.selectLeaf(matching, rejectBranchingSessions);
        return leaf == null ? null : deepCopy(leaf);
      }

      return null;
    }
  }

  // ── SnapshotWriter ────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Implements the full saveSnapshot contract:
   *
   * <ol>
   *   <li>Under lock: read existing snapshot (deep copy) identified by {@code snapshotId}.
   *   <li>Invoke {@code mutator.apply(existing)}; if result is {@code null}, return {@code null}
   *       (no write).
   *   <li>Determine final id: {@code snapshotId != null ? snapshotId : (result.snapshotId != null ?
   *       result.snapshotId : UUID.randomUUID())}. Force {@code result.snapshotId = finalId}.
   *   <li>Preserve sessionId from existing snapshot when updating.
   *   <li>Validate sessionId is non-null and non-empty; throw {@link GenkitException} with {@code
   *       INVALID_ARGUMENT} otherwise.
   *   <li>Default {@code null} status to {@link SnapshotStatus#COMPLETED}.
   *   <li>Store deep copy. Collect subscriber callbacks if status changed (existing was null or
   *       status differs).
   *   <li>Invoke collected callbacks after releasing the lock.
   * </ol>
   */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public String saveSnapshot(
      String snapshotId, SnapshotMutator<S> mutator, SessionStoreOptions options) {
    // Declared outside synchronized block so callbacks can be invoked after lock release.
    List<Consumer<SessionSnapshot<?>>> toNotify;
    SessionSnapshot<?> notifyPayload = null;
    String finalId;

    synchronized (this) {
      // Step 1: read existing
      SessionSnapshot<S> existing =
          snapshotId != null && snapshots.containsKey(snapshotId)
              ? deepCopy((SessionSnapshot) snapshots.get(snapshotId))
              : null;

      // Step 2: apply mutator
      SessionSnapshot<S> result = mutator.apply(existing);
      if (result == null) {
        return null;
      }

      // Step 3: determine final id
      if (snapshotId != null) {
        finalId = snapshotId;
      } else if (result.getSnapshotId() != null && !result.getSnapshotId().isBlank()) {
        finalId = result.getSnapshotId();
      } else {
        finalId = UUID.randomUUID().toString();
      }
      result.setSnapshotId(finalId);

      // Step 4: preserve sessionId from existing row
      if (existing != null && existing.getSessionId() != null) {
        result.setSessionId(existing.getSessionId());
      }

      // Step 4b: fall back to state.sessionId if top-level sessionId is null
      if (result.getSessionId() == null && result.getState() != null) {
        result.setSessionId(result.getState().getSessionId());
      }

      // Step 4c: validate sessionId
      if (result.getSessionId() == null || result.getSessionId().isBlank()) {
        throw GenkitException.builder()
            .message("snapshot requires sessionId")
            .errorCode("INVALID_ARGUMENT")
            .build();
      }

      // Step 5: default null status to COMPLETED
      if (result.getStatus() == null) {
        result.setStatus(SnapshotStatus.COMPLETED);
      }

      // Step 6: store deep copy
      SessionSnapshot<S> toStore = deepCopy(result);
      snapshots.put(finalId, toStore);

      // Step 7: collect subscriber callbacks if status changed (invoke after releasing lock)
      SnapshotStatus existingStatus = existing != null ? existing.getStatus() : null;
      boolean statusChanged = existingStatus != result.getStatus();
      if (statusChanged) {
        List<Consumer<SessionSnapshot<?>>> cbs = subscribers.get(finalId);
        if (cbs != null && !cbs.isEmpty()) {
          toNotify = new ArrayList<>(cbs);
          notifyPayload = deepCopy(toStore);
        } else {
          toNotify = new ArrayList<>();
          notifyPayload = null;
        }
      } else {
        toNotify = new ArrayList<>();
        notifyPayload = null;
      }
    } // lock released here

    // Step 8: invoke callbacks outside the lock to avoid deadlock on re-entry
    if (notifyPayload != null) {
      for (Consumer<SessionSnapshot<?>> cb : toNotify) {
        cb.accept(notifyPayload);
      }
    }
    return finalId;
  }

  // ── SnapshotSubscriber ────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Registers {@code cb} to be invoked on subsequent status changes for {@code snapshotId}. If
   * the snapshot already exists, the callback is invoked immediately with a deep copy of the
   * current snapshot (before returning). The callback will also fire on any subsequent status
   * changes. If the snapshot does not currently exist, the callback is registered and will fire on
   * the first save (status change from null). Returns an {@link AutoCloseable} that unregisters the
   * callback.
   */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public AutoCloseable onSnapshotStateChange(
      String snapshotId, Consumer<SessionSnapshot<?>> cb, SessionStoreOptions options) {
    SessionSnapshot<?> immediate = null;
    synchronized (this) {
      subscribers.computeIfAbsent(snapshotId, k -> new ArrayList<>()).add(cb);
      SessionSnapshot current = snapshots.get(snapshotId);
      if (current != null) {
        immediate = deepCopy(current);
      }
    }
    if (immediate != null) {
      cb.accept(immediate);
    }
    return () -> {
      synchronized (this) {
        List<Consumer<SessionSnapshot<?>>> cbs = subscribers.get(snapshotId);
        if (cbs != null) {
          cbs.remove(cb);
          if (cbs.isEmpty()) {
            subscribers.remove(snapshotId);
          }
        }
      }
    };
  }

  // ── deep copy ────────────────────────────────────────────────────────────────

  /**
   * Deep-copies a snapshot via JSON round-trip (valueToTree → treeToValue with raw type).
   *
   * <p>Note: due to type erasure, the {@code custom} field of {@link SessionState} is deserialized
   * as {@link java.util.LinkedHashMap} rather than {@code S}. This is documented and acceptable for
   * the in-memory reference store.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T> SessionSnapshot<T> deepCopy(SessionSnapshot<T> snapshot) {
    try {
      JsonNode node = MAPPER.valueToTree(snapshot);
      return (SessionSnapshot<T>) MAPPER.treeToValue(node, SessionSnapshot.class);
    } catch (Exception e) {
      throw new GenkitException("Failed to deep-copy SessionSnapshot: " + e.getMessage(), e);
    }
  }
}
