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

package com.google.genkit.ai.agent.internal;

import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.RuntimeError;
import com.google.genkit.ai.agent.Session;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.ai.agent.SessionStoreOptions;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.core.GenkitException;

/**
 * Resolves a {@link Session} from an {@link AgentInit} at the start of an agent invocation.
 *
 * <p>Enforces the server-vs-client state-management rules:
 *
 * <ul>
 *   <li>API misuse (wrong init for the state-management mode, ownership mismatch) throws {@link
 *       GenkitException} with {@code FAILED_PRECONDITION} or {@code INVALID_ARGUMENT}.
 *   <li>Other pre-turn failures are returned as a {@link Resolution#failure} so the agent can emit
 *       an {@code AgentOutput} with {@code finishReason=FAILED} rather than surfacing as an
 *       unhandled exception.
 * </ul>
 *
 * <h3>Pending-leaf decision</h3>
 *
 * <p>When resolving by sessionId and the latest leaf snapshot has a non-COMPLETED status (e.g.
 * PENDING, FAILED, ABORTED), this implementation throws {@link GenkitException} with {@code
 * FAILED_PRECONDITION}. This matches Go's {@code resumeSessionFrom} behavior and is simpler than
 * the JS approach of walking back to the last completed snapshot. The error is conformance-tested;
 * callers should clear or complete any in-progress snapshot before starting a new turn.
 *
 * <h3>Unknown-snapshot decision</h3>
 *
 * <p>When a specific {@code snapshotId} is provided but not found in the store, this implementation
 * throws {@link GenkitException} with {@code INVALID_ARGUMENT}. An unknown snapshotId is treated as
 * API misuse (the client provided a bad reference), not as a recoverable pre-turn error. This
 * matches the design spec §6.4 note: "unknown snapshot → throw/404".
 */
public final class SessionResolver {

  private SessionResolver() {}

  // ── Resolution ────────────────────────────────────────────────────────────────

  /**
   * Result of session resolution: either a ready {@link Session}, or a graceful pre-turn failure.
   *
   * <p>A failure resolution lets the agent generator (Task 4.5) emit an {@code AgentOutput} with
   * {@code finishReason=FAILED} instead of throwing.
   *
   * @param <S> the type of custom session state
   */
  public static final class Resolution<S> {

    private final Session<S> session;
    private final RuntimeError error;
    private final String sourceSnapshotId;

    private Resolution(Session<S> session, RuntimeError error, String sourceSnapshotId) {
      this.session = session;
      this.error = error;
      this.sourceSnapshotId = sourceSnapshotId;
    }

    /**
     * Creates a successful resolution wrapping the given session.
     *
     * @param <S> the type of custom session state
     * @param session the resolved session (must not be null)
     * @return a successful Resolution
     */
    public static <S> Resolution<S> ok(Session<S> session) {
      return new Resolution<>(session, null, null);
    }

    /**
     * Creates a successful resolution wrapping the given session and the id of the snapshot it was
     * resolved (resumed) from. The source snapshot id seeds the runner's {@code lastSnapshotId} so
     * the first post-resume turn chains its {@code parentId} to it (see {@code SessionRunner}).
     *
     * @param <S> the type of custom session state
     * @param session the resolved session (must not be null)
     * @param sourceSnapshotId the id of the snapshot this session was resumed from; may be {@code
     *     null} for a fresh session
     * @return a successful Resolution carrying the source snapshot id
     */
    public static <S> Resolution<S> ok(Session<S> session, String sourceSnapshotId) {
      return new Resolution<>(session, null, sourceSnapshotId);
    }

    /**
     * Creates a failure resolution carrying a {@link RuntimeError}.
     *
     * <p>The agent generator should translate this into an {@code AgentOutput} with {@code
     * finishReason=FAILED} rather than throwing.
     *
     * @param <S> the type of custom session state
     * @param error the runtime error describing the failure
     * @return a failure Resolution
     */
    public static <S> Resolution<S> failure(RuntimeError error) {
      return new Resolution<>(null, error, null);
    }

    /**
     * Returns {@code true} if this is a successful resolution with a ready session.
     *
     * @return true if ok
     */
    public boolean isOk() {
      return session != null;
    }

    /**
     * Returns the resolved session, or {@code null} if this is a failure resolution.
     *
     * @return the session, or null
     */
    public Session<S> session() {
      return session;
    }

    /**
     * Returns the runtime error, or {@code null} if this is a successful resolution.
     *
     * @return the error, or null
     */
    public RuntimeError error() {
      return error;
    }

    /**
     * Returns the id of the snapshot this session was resolved (resumed) from, or {@code null} for
     * a fresh session. Used to seed the runner's {@code lastSnapshotId} so the first post-resume
     * turn chains its {@code parentId} to the resumed snapshot.
     *
     * @return the source snapshot id, or {@code null}
     */
    public String sourceSnapshotId() {
      return sourceSnapshotId;
    }
  }

  // ── resolve ───────────────────────────────────────────────────────────────────

  /**
   * Resolves a {@link Session} from the given {@link AgentInit}.
   *
   * <p>Rules (see class-level Javadoc for details):
   *
   * <ol>
   *   <li>State-management mismatch → throws {@link GenkitException}.
   *   <li>Client-managed: hydrate from {@code init.state} or mint a fresh session.
   *   <li>Server-managed: resolve via snapshotId, sessionId, or mint fresh.
   * </ol>
   *
   * @param <S> the type of custom session state
   * @param store the agent's session store, or {@code null} for client-managed agents
   * @param serverManaged {@code true} iff the agent has a store (server-managed)
   * @param init the agent init (may be {@code null} — treated as all fields null)
   * @param opts options forwarded to store operations (may be {@code null})
   * @return a {@link Resolution} wrapping either the resolved session or a graceful failure
   * @throws GenkitException with {@code FAILED_PRECONDITION} or {@code INVALID_ARGUMENT} for API
   *     misuse (wrong init for the state-management mode, or ownership mismatch)
   */
  public static <S> Resolution<S> resolve(
      SessionStore<S> store, boolean serverManaged, AgentInit<S> init, SessionStoreOptions opts) {

    // Null-safe field extraction — treat null init as all-fields-null.
    String snapshotId = init != null ? init.getSnapshotId() : null;
    String sessionId = init != null ? init.getSessionId() : null;
    SessionState<S> state = init != null ? init.getState() : null;

    // ── Rule 1: state-management mismatch → THROW ────────────────────────────

    if (serverManaged && state != null) {
      throw GenkitException.builder()
          .message("Cannot send 'state' to a server-managed agent")
          .errorCode("FAILED_PRECONDITION")
          .build();
    }

    if (!serverManaged && (sessionId != null || snapshotId != null)) {
      String which = sessionId != null ? "sessionId" : "snapshotId";
      throw GenkitException.builder()
          .message("Cannot use '" + which + "'/'snapshotId' with a client-managed agent")
          .errorCode("FAILED_PRECONDITION")
          .build();
    }

    // ── Rule 2: client-managed ────────────────────────────────────────────────

    if (!serverManaged) {
      if (state != null) {
        return Resolution.ok(new Session<>(state));
      }
      return Resolution.ok(new Session<>(new SessionState<>()));
    }

    // ── Rule 3: server-managed ────────────────────────────────────────────────

    if (snapshotId != null) {
      return resolveBySnapshotId(store, snapshotId, sessionId, opts);
    }

    if (sessionId != null) {
      return resolveBySessionId(store, sessionId, opts);
    }

    // No snapshotId, no sessionId → fresh server session (Session mints a new UUID).
    return Resolution.ok(new Session<>(new SessionState<>()));
  }

  // ── private helpers ───────────────────────────────────────────────────────────

  /**
   * Resolves a session from a specific snapshotId. Throws for unknown, non-COMPLETED, or
   * ownership-mismatched snapshots.
   */
  private static <S> Resolution<S> resolveBySnapshotId(
      SessionStore<S> store, String snapshotId, String callerSessionId, SessionStoreOptions opts) {

    SessionSnapshot<S> snap =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(snapshotId).build());

    if (snap == null) {
      throw GenkitException.builder()
          .message("snapshot not found: " + snapshotId)
          .errorCode("INVALID_ARGUMENT")
          .build();
    }

    if (snap.getStatus() != SnapshotStatus.COMPLETED) {
      throw GenkitException.builder()
          .message("snapshot is not resumable: " + snap.getStatus())
          .errorCode("INVALID_ARGUMENT")
          .build();
    }

    // Ownership check: if the caller also provided a sessionId it must match.
    if (callerSessionId != null) {
      String snapSessionId = snap.getSessionId();
      if (snapSessionId == null && snap.getState() != null) {
        snapSessionId = snap.getState().getSessionId();
      }
      if (!callerSessionId.equals(snapSessionId)) {
        throw GenkitException.builder()
            .message("snapshot does not belong to session: " + callerSessionId)
            .errorCode("INVALID_ARGUMENT")
            .build();
      }
    }

    return Resolution.ok(new Session<>(hydratedState(snap)), snap.getSnapshotId());
  }

  /**
   * Resolves a session from a sessionId. Fresh if unknown; hydrated if COMPLETED leaf; throws for
   * non-COMPLETED leaf.
   */
  private static <S> Resolution<S> resolveBySessionId(
      SessionStore<S> store, String sessionId, SessionStoreOptions opts) {

    SessionSnapshot<S> snap =
        store.getSnapshot(GetSnapshotOptions.builder().sessionId(sessionId).build());

    if (snap == null) {
      // Unknown sessionId → fresh session bound to that id.
      SessionState<S> fresh = new SessionState<>();
      fresh.setSessionId(sessionId);
      return Resolution.ok(new Session<>(fresh));
    }

    if (snap.getStatus() == SnapshotStatus.COMPLETED) {
      return Resolution.ok(new Session<>(hydratedState(snap)), snap.getSnapshotId());
    }

    // Latest leaf is pending/failed/aborted/expired → reject resumption.
    // Decision: throw FAILED_PRECONDITION (matches Go's resumeSessionFrom).
    // The JS runtime walks back to the last completed leaf, but throwing is simpler and
    // conformance-correct. The caller must clear or complete in-progress snapshots first.
    throw GenkitException.builder()
        .message("cannot resume: latest snapshot status=" + snap.getStatus())
        .errorCode("FAILED_PRECONDITION")
        .build();
  }

  /**
   * Extracts the state from a snapshot, ensuring the returned state carries the snapshot's
   * sessionId. Falls back to a fresh {@link SessionState} with the snapshot's sessionId if the
   * snapshot's state is null.
   */
  private static <S> SessionState<S> hydratedState(SessionSnapshot<S> snap) {
    SessionState<S> state = snap.getState();
    if (state != null) {
      // Ensure sessionId is set on the state so subsequent snapshots chain correctly.
      if (state.getSessionId() == null || state.getSessionId().isEmpty()) {
        String sid = snap.getSessionId();
        if (sid != null && !sid.isEmpty()) {
          state.setSessionId(sid);
        }
      }
      return state;
    }
    // Snapshot has no state — create a fresh state carrying the snapshot's sessionId.
    SessionState<S> fresh = new SessionState<>();
    fresh.setSessionId(snap.getSessionId());
    return fresh;
  }
}
