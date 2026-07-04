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
import com.google.genkit.ai.Role;
import com.google.genkit.ai.agent.internal.AbortAwareMutator;
import com.google.genkit.core.GenkitException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Drives ONE turn of an agent session: validates the input, appends the user message, runs the turn
 * body, and persists a snapshot on success ({@link SnapshotStatus#COMPLETED}) or on failure ({@link
 * SnapshotStatus#FAILED}). Failure snapshots are swallowed — {@link #runTurn} never rethrows
 * exceptions from the turn body.
 *
 * <p>Client-managed mode ({@code store == null}): no persistence; the runner tracks the last
 * snapshot in-memory only and {@link #lastSnapshotId()} always returns {@code ""}.
 *
 * @param <S> the type of the custom session state
 */
public final class SessionRunner<S> {

  private final Session<S> session;

  /** Nullable: null means client-managed (no server-side persistence). */
  private final SessionStore<S> store;

  private final SessionStoreOptions opts;

  private int turnIndex;
  private SessionSnapshot<S> lastSnapshot;
  private String lastSnapshotId;
  private SessionState<S> lastGoodState;
  private AgentFinishReason lastTurnFinishReason;
  private RuntimeError lastTurnError;

  /**
   * Constructs a new SessionRunner.
   *
   * @param session the in-memory session to drive
   * @param store the session store, or {@code null} for client-managed mode (no server persistence)
   * @param opts options forwarded to store operations
   */
  public SessionRunner(Session<S> session, SessionStore<S> store, SessionStoreOptions opts) {
    this(session, store, opts, null);
  }

  /**
   * Constructs a new SessionRunner seeded with the id of the snapshot the session was resumed from.
   *
   * <p>Seeding {@link #lastSnapshotId} makes the first post-resume turn chain its {@code parentId}
   * to the resumed snapshot (the {@code parentId = lastSnapshotId} logic in {@link #maybeSnapshot}
   * then threads correctly across invocations). Pass {@code null} for a fresh session.
   *
   * @param session the in-memory session to drive
   * @param store the session store, or {@code null} for client-managed mode (no server persistence)
   * @param opts options forwarded to store operations
   * @param seedSnapshotId the id of the snapshot this session was resumed from, or {@code null}
   */
  public SessionRunner(
      Session<S> session, SessionStore<S> store, SessionStoreOptions opts, String seedSnapshotId) {
    this.session = session;
    this.store = store;
    this.opts = opts != null ? opts : SessionStoreOptions.empty();
    if (seedSnapshotId != null && !seedSnapshotId.isEmpty()) {
      this.lastSnapshotId = seedSnapshotId;
    }
  }

  // ── Session delegates ────────────────────────────────────────────────────────

  /**
   * Returns the session ID.
   *
   * @return the session ID (never null)
   */
  public String sessionId() {
    return session.sessionId();
  }

  /**
   * Returns a deep copy of the current session state.
   *
   * @return the current session state
   */
  public SessionState<S> getState() {
    return session.getState();
  }

  /**
   * Returns a copy of the current messages list.
   *
   * @return the current messages
   */
  public List<Message> getMessages() {
    return session.getMessages();
  }

  /**
   * Appends messages to the session.
   *
   * @param m messages to add
   */
  public void addMessages(Message... m) {
    session.addMessages(m);
  }

  /**
   * Returns a deep copy of the current custom state.
   *
   * @return the custom state
   */
  public S getCustom() {
    return session.getCustom();
  }

  /**
   * Applies {@code fn} to the current custom state and stores the result.
   *
   * @param fn the update function
   */
  public void updateCustom(UnaryOperator<S> fn) {
    session.updateCustom(fn);
  }

  /**
   * Returns a copy of the current artifacts list.
   *
   * @return the artifacts
   */
  public List<Artifact> getArtifacts() {
    return session.getArtifacts();
  }

  /**
   * Adds artifacts to the session.
   *
   * @param a artifacts to add
   */
  public void addArtifacts(Artifact... a) {
    session.addArtifacts(a);
  }

  /**
   * Returns the underlying session.
   *
   * @return the session
   */
  public Session<S> session() {
    return session;
  }

  // ── Turn-state accessors ─────────────────────────────────────────────────────

  /**
   * Returns the number of turns that have completed (including failed turns).
   *
   * @return the turn index (0 before any turn has run)
   */
  public int turnIndex() {
    return turnIndex;
  }

  /**
   * Returns the last persisted (or in-memory) snapshot, or {@code null} if no turn has completed.
   *
   * @return the last snapshot
   */
  public SessionSnapshot<S> lastSnapshot() {
    return lastSnapshot;
  }

  /**
   * Returns the ID of the last snapshot, or {@code null} if no turn has completed. Returns {@code
   * ""} (empty string) in client-managed mode.
   *
   * @return the last snapshot ID
   */
  public String lastSnapshotId() {
    return lastSnapshotId;
  }

  /**
   * Returns the session state as of the last successful turn, or {@code null} if no successful turn
   * has completed.
   *
   * @return the last good state
   */
  public SessionState<S> lastGoodState() {
    return lastGoodState;
  }

  /**
   * Returns the finish reason of the last completed turn.
   *
   * @return the finish reason, or {@code null} if no turn has run
   */
  public AgentFinishReason lastTurnFinishReason() {
    return lastTurnFinishReason;
  }

  /**
   * Returns the error from the last failed turn, or {@code null} if the last turn succeeded.
   *
   * @return the last turn error
   */
  public RuntimeError lastTurnError() {
    return lastTurnError;
  }

  // ── Core turn lifecycle ──────────────────────────────────────────────────────

  /**
   * Runs ONE turn:
   *
   * <ol>
   *   <li>Validates {@code input.message}: role must be null/USER; rejects tool-request and
   *       tool-response parts → throws {@link GenkitException} with {@code INVALID_ARGUMENT} (API
   *       misuse; not graceful).
   *   <li>Reserves a turn snapshot ID (UUID if store != null, else "").
   *   <li>Appends {@code input.message} to the session if non-null.
   *   <li>Runs {@code turnBody.run(input, turnCtx)}.
   *   <li>On success: persists a {@code COMPLETED} snapshot; records {@link #lastGoodState};
   *       increments turn index.
   *   <li>On exception: records {@link #lastTurnError}; persists a {@code FAILED} snapshot (or
   *       {@code ABORTED} for {@link InterruptedException}); increments turn index; does NOT
   *       rethrow.
   * </ol>
   *
   * @param input the agent input for this turn
   * @param turnBody the turn body to execute
   * @throws GenkitException (INVALID_ARGUMENT) if the input message is invalid — this IS propagated
   *     (it is API misuse, not a graceful turn failure)
   */
  public void runTurn(AgentInput input, TurnBody<S> turnBody) {
    // Step 1: validate input
    validateInput(input);

    // Step 2: reserve snapshot ID
    String turnSnapshotId = (store != null) ? UUID.randomUUID().toString() : "";
    String parentId = lastSnapshotId; // may be null on first turn
    TurnContext turnCtx = new TurnContext(turnSnapshotId, parentId, turnIndex);

    // Step 3: append user message (deep-copied to prevent external mutation of history)
    if (input != null && input.getMessage() != null) {
      session.addMessages(deepCopyMessage(input.getMessage()));
    }

    // Steps 4-6: run turnBody
    try {
      AgentFinishReason finishReason = turnBody.run(input, turnCtx);
      if (finishReason == null) {
        finishReason = AgentFinishReason.STOP;
      }
      // Step 5: success path
      lastTurnError = null;
      lastTurnFinishReason = finishReason;
      maybeSnapshot(SnapshotStatus.COMPLETED, finishReason, null, turnSnapshotId, parentId);
      lastGoodState = session.getState();
    } catch (InterruptedException ie) {
      // ABORTED — record but do NOT write a failed snapshot for aborted turns
      Thread.currentThread().interrupt(); // restore interrupted status
      lastTurnError =
          RuntimeError.builder()
              .status("ABORTED")
              .message(ie.getMessage() != null ? ie.getMessage() : "interrupted")
              .build();
      lastTurnFinishReason = AgentFinishReason.ABORTED;
      maybeSnapshot(
          SnapshotStatus.ABORTED,
          AgentFinishReason.ABORTED,
          lastTurnError,
          turnSnapshotId,
          parentId);
    } catch (Exception e) {
      // Step 6: failure path — record error, persist FAILED snapshot, swallow. Preserve a
      // GenkitException's error code (e.g. INVALID_ARGUMENT from resume-directive validation) so
      // the
      // graceful FAILED output carries the correct status; default to INTERNAL otherwise.
      String status = "INTERNAL";
      Throwable probe = e;
      while (probe != null) {
        if (probe instanceof GenkitException ge && ge.getErrorCode() != null) {
          status = ge.getErrorCode();
          break;
        }
        probe = probe.getCause();
      }
      lastTurnError =
          RuntimeError.builder()
              .status(status)
              .message(e.getMessage() != null ? e.getMessage() : e.getClass().getName())
              .build();
      lastTurnFinishReason = AgentFinishReason.FAILED;
      maybeSnapshot(
          SnapshotStatus.FAILED, AgentFinishReason.FAILED, lastTurnError, turnSnapshotId, parentId);
    } finally {
      turnIndex++;
    }
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  /**
   * Deep-copies a message using the shared ObjectMapper. Returns null if input is null.
   *
   * @param m the message to copy
   * @return a deep copy of the message
   * @throws GenkitException if copying fails
   */
  private Message deepCopyMessage(Message m) {
    if (m == null) return null;
    var mapper = com.google.genkit.core.JsonUtils.getObjectMapper();
    try {
      return mapper.treeToValue(mapper.valueToTree(m), Message.class);
    } catch (Exception e) {
      throw GenkitException.builder()
          .message("failed to copy message: " + e.getMessage())
          .errorCode("INTERNAL")
          .build();
    }
  }

  /**
   * Validates the input message. Throws {@link GenkitException} with {@code INVALID_ARGUMENT} if:
   *
   * <ul>
   *   <li>The message role is not null, empty, or USER.
   *   <li>The message contains tool-request or tool-response parts (those go via resume).
   * </ul>
   */
  private void validateInput(AgentInput input) {
    if (input == null || input.getMessage() == null) {
      return;
    }
    Message msg = input.getMessage();

    // Validate role
    Role role = msg.getRole();
    if (role != null && role != Role.USER) {
      throw GenkitException.builder()
          .message("input message role must be 'user' (or null); got: " + role)
          .errorCode("INVALID_ARGUMENT")
          .build();
    }

    // Reject tool-request and tool-response parts on the user message
    List<Part> parts = msg.getContent();
    if (parts != null) {
      for (Part part : parts) {
        if (part.getToolRequest() != null) {
          throw GenkitException.builder()
              .message(
                  "user message must not contain toolRequest parts; use resume for tool responses")
              .errorCode("INVALID_ARGUMENT")
              .build();
        }
        if (part.getToolResponse() != null) {
          throw GenkitException.builder()
              .message(
                  "user message must not contain toolResponse parts; use resume for tool responses")
              .errorCode("INVALID_ARGUMENT")
              .build();
        }
      }
    }
  }

  /**
   * Persists (or tracks in-memory for client-managed mode) a snapshot for this turn.
   *
   * <p>If {@code store == null} (client-managed): no persistence; builds a synthetic snapshot and
   * stores it in {@link #lastSnapshot}. {@link #lastSnapshotId} stays {@code ""}.
   *
   * <p>If {@code store != null}: builds a {@link SessionSnapshot} and calls {@link
   * SessionStore#saveSnapshot} with an {@link AbortAwareMutator}-wrapped mutator. Updates {@link
   * #lastSnapshot} and {@link #lastSnapshotId} to the persisted values.
   */
  private void maybeSnapshot(
      SnapshotStatus status,
      AgentFinishReason finishReason,
      RuntimeError error,
      String snapshotId,
      String parentId) {

    String now = Instant.now().toString();
    SessionState<S> currentState = session.getState();

    if (store == null) {
      // Client-managed: track in memory only, snapshotId stays ""
      SessionSnapshot<S> snap =
          SessionSnapshot.<S>builder()
              .snapshotId("")
              .sessionId(session.sessionId())
              .parentId(parentId)
              .createdAt(now)
              .updatedAt(now)
              .state(currentState)
              .status(status)
              .finishReason(finishReason)
              .error(error)
              .build();
      lastSnapshot = snap;
      lastSnapshotId = "";
    } else {
      // Persist via store
      final SessionSnapshot<S> snap =
          SessionSnapshot.<S>builder()
              .snapshotId(snapshotId)
              .sessionId(session.sessionId())
              .parentId(parentId)
              .createdAt(now)
              .updatedAt(now)
              .state(currentState)
              .status(status)
              .finishReason(finishReason)
              .error(error)
              .build();

      SnapshotMutator<S> inner = existing -> snap;
      String savedId = store.saveSnapshot(snapshotId, AbortAwareMutator.wrap(inner), opts);

      lastSnapshot = snap;
      lastSnapshotId = savedId != null ? savedId : snapshotId;
    }
  }
}
