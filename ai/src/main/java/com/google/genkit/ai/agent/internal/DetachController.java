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

import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.RuntimeError;
import com.google.genkit.ai.agent.SessionRunner;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.ai.agent.SessionStoreOptions;
import com.google.genkit.ai.agent.SnapshotMutator;
import com.google.genkit.ai.agent.SnapshotStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a detached agent turn: writes a {@code PENDING} snapshot, suppresses streaming, starts a
 * heartbeat, runs the turn on a background daemon thread, and finalizes the pending snapshot to a
 * terminal status when the turn completes.
 *
 * <p><strong>Pragmatic approach.</strong> The bidi handler returns {@code AgentOutput}
 * synchronously after the first detach input. True multi-turn continuation inside the live bidi
 * stream would require keeping the stream open while running in the background, which is
 * incompatible with the synchronous handler contract. Instead, {@link #detach} reserves the
 * snapshot id, kicks off the single turn on a shared daemon executor (streaming suppressed), and
 * returns immediately. The client polls {@code getSnapshot} until the snapshot reaches a terminal
 * status. This matches the design spec §6.4 and the Go/JS reference behavior for the write side.
 *
 * <p><strong>Abort race.</strong> Both the heartbeat and the finalize step go through {@link
 * AbortAwareMutator}, and the finalize mutator additionally never overwrites an {@code ABORTED}
 * row. If the abort companion flips the pending snapshot to {@code ABORTED} while the background
 * turn is still running, the heartbeat becomes a no-op (status != PENDING) and the finalize
 * declines the write, so the {@code ABORTED} terminal state is preserved.
 *
 * <p><strong>Threading.</strong> All background work uses shared daemon-thread executors so the JVM
 * can exit without blocking and no threads are leaked across invocations.
 */
public final class DetachController {

  private DetachController() {}

  /**
   * Heartbeat interval in milliseconds. The heartbeat refreshes {@code heartbeatAt} on the pending
   * snapshot so a reader can distinguish a live detached run from a stale (expired) one.
   *
   * <p>Package-private and non-final so tests can shrink it for determinism. Production default is
   * 30s, matching the Go/JS reference.
   */
  static volatile long heartbeatIntervalMillis = 30_000L;

  /** Shared single-thread daemon scheduler for all heartbeats across agents. */
  private static final ScheduledExecutorService HEARTBEAT_SCHEDULER =
      Executors.newSingleThreadScheduledExecutor(daemonFactory("genkit-agent-heartbeat"));

  /** Shared daemon executor that runs detached turn bodies. */
  private static final java.util.concurrent.ExecutorService BACKGROUND_EXECUTOR =
      Executors.newCachedThreadPool(daemonFactory("genkit-agent-detach"));

  private static ThreadFactory daemonFactory(String prefix) {
    AtomicLong counter = new AtomicLong();
    return r -> {
      Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  /**
   * Overrides the heartbeat interval (test hook). Returns the previous value so tests can restore
   * it. Public so tests outside this package can make heartbeat timing deterministic; it is not
   * part of the supported API surface.
   *
   * @param millis the new interval in milliseconds (must be positive)
   * @return the previous interval
   */
  public static long setHeartbeatIntervalMillisForTest(long millis) {
    long prev = heartbeatIntervalMillis;
    heartbeatIntervalMillis = millis;
    return prev;
  }

  /**
   * Handles a detached turn for a server-managed agent.
   *
   * <ol>
   *   <li>Reserves a snapshot id and writes a {@code PENDING} snapshot (cumulative state baseline).
   *   <li>Suppresses the stream emitter so the detached run emits no chunks.
   *   <li>Schedules a heartbeat that refreshes {@code heartbeatAt} while the snapshot stays
   *       pending.
   *   <li>Runs {@code turnBody} on a shared daemon thread, then finalizes the snapshot
   *       (abort-aware) to {@code COMPLETED}/{@code FAILED} with the cumulative state and stops the
   *       heartbeat.
   * </ol>
   *
   * <p><strong>Abort signal registration.</strong> While the turn is {@code PENDING}, {@code
   * abortSignal} (the same {@link AtomicBoolean} handed to the turn's {@code AgentFnContext}) is
   * registered in {@link PendingAbortRegistry} under the reserved snapshot id, so an external
   * {@code Agent.abort(snapshotId)} call can flip it while the background turn is still running.
   * The registration is removed in a {@code finally} block when the turn finishes, regardless of
   * outcome.
   *
   * @param <S> the type of custom session state
   * @param runner the session runner (shared with the background thread; {@link
   *     com.google.genkit.ai.agent.Session} is synchronized)
   * @param store the (non-null) server-side store
   * @param opts store options
   * @param emitter the stream emitter to suppress
   * @param abortSignal the abort signal handed to this turn's {@code AgentFnContext}; registered
   *     under the reserved snapshot id for the lifetime of the background run
   * @param turnBody the turn body to execute in the background
   * @return the reserved snapshot id for the pending detached run
   */
  public static <S> String detach(
      SessionRunner<S> runner,
      SessionStore<S> store,
      SessionStoreOptions opts,
      StreamEmitter<S> emitter,
      AtomicBoolean abortSignal,
      DetachedTurn<S> turnBody) {

    final SessionStoreOptions options = opts != null ? opts : SessionStoreOptions.empty();
    final String snapshotId = UUID.randomUUID().toString();
    final String sessionId = runner.sessionId();
    final String parentId = runner.lastSnapshotId();

    // Register the abort signal under the reserved snapshot id BEFORE the id becomes externally
    // visible (the pending snapshot write below), so there is no window where a caller could
    // observe the id via getSnapshot/abort but find no registered signal yet.
    PendingAbortRegistry.register(snapshotId, abortSignal);

    // Step 1: write the PENDING snapshot with the current cumulative state as a baseline.
    final SessionState<S> baseline = runner.getState();
    final String createdAt = Instant.now().toString();
    SnapshotMutator<S> pendingMutator =
        existing ->
            SessionSnapshot.<S>builder()
                .snapshotId(snapshotId)
                .sessionId(sessionId)
                .parentId(parentId)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .heartbeatAt(createdAt)
                .status(SnapshotStatus.PENDING)
                .state(baseline)
                .build();
    store.saveSnapshot(snapshotId, AbortAwareMutator.wrap(pendingMutator), options);

    // Step 2: suppress streaming for the detached run.
    emitter.setSuppressed(true);

    // Step 3: start the heartbeat — a no-op once the snapshot leaves PENDING.
    final ScheduledFuture<?>[] heartbeatHandle = new ScheduledFuture<?>[1];
    heartbeatHandle[0] =
        HEARTBEAT_SCHEDULER.scheduleAtFixedRate(
            () -> beat(store, snapshotId, options),
            heartbeatIntervalMillis,
            heartbeatIntervalMillis,
            TimeUnit.MILLISECONDS);

    // Step 4: run the turn body in the background, then finalize.
    BACKGROUND_EXECUTOR.execute(
        () -> {
          SnapshotStatus terminalStatus = SnapshotStatus.COMPLETED;
          AgentFinishReason finishReason = AgentFinishReason.STOP;
          RuntimeError error = null;
          try {
            finishReason = turnBody.run();
            if (finishReason == null) {
              finishReason = AgentFinishReason.STOP;
            }
          } catch (Throwable t) {
            terminalStatus = SnapshotStatus.FAILED;
            finishReason = AgentFinishReason.FAILED;
            error =
                RuntimeError.builder()
                    .status("INTERNAL")
                    .message(t.getMessage() != null ? t.getMessage() : t.getClass().getName())
                    .build();
          } finally {
            try {
              finalizePending(
                  runner, store, options, snapshotId, terminalStatus, finishReason, error);
            } finally {
              ScheduledFuture<?> handle = heartbeatHandle[0];
              if (handle != null) {
                handle.cancel(false);
              }
              // Always remove the registration once the turn is no longer running, whatever the
              // outcome, so entries never leak and a later abort() call for a reused/unknown id
              // cannot spuriously flip a stale signal.
              PendingAbortRegistry.unregister(snapshotId);
            }
          }
        });

    // Step 5: caller returns AgentOutput{finishReason=DETACHED, snapshotId} immediately.
    return snapshotId;
  }

  /** Refreshes {@code heartbeatAt} iff the snapshot is still pending; otherwise a no-op. */
  private static <S> void beat(SessionStore<S> store, String snapshotId, SessionStoreOptions opts) {
    SnapshotMutator<S> mutator =
        existing -> {
          if (existing == null || existing.getStatus() != SnapshotStatus.PENDING) {
            return null; // decline: nothing to refresh
          }
          existing.setHeartbeatAt(Instant.now().toString());
          return existing;
        };
    try {
      store.saveSnapshot(snapshotId, AbortAwareMutator.wrap(mutator), opts);
    } catch (RuntimeException ignored) {
      // A heartbeat failure must never crash the scheduler thread.
    }
  }

  /**
   * Finalizes the pending snapshot to a terminal status with the cumulative session state. Never
   * overwrites an {@code ABORTED} row, and declines if the snapshot has gone missing.
   */
  private static <S> void finalizePending(
      SessionRunner<S> runner,
      SessionStore<S> store,
      SessionStoreOptions opts,
      String snapshotId,
      SnapshotStatus terminalStatus,
      AgentFinishReason finishReason,
      RuntimeError error) {

    final SessionState<S> finalState = runner.getState();
    final String now = Instant.now().toString();
    SnapshotMutator<S> mutator =
        existing -> {
          if (existing == null) {
            return null; // snapshot gone — decline
          }
          if (existing.getStatus() == SnapshotStatus.ABORTED) {
            return existing; // abort won — preserve it
          }
          existing.setStatus(terminalStatus);
          existing.setFinishReason(finishReason);
          existing.setError(error);
          existing.setState(finalState);
          existing.setUpdatedAt(now);
          existing.setHeartbeatAt(null); // terminal: no more heartbeats
          return existing;
        };
    store.saveSnapshot(snapshotId, AbortAwareMutator.wrap(mutator), opts);
  }

  /**
   * The body of a detached turn. Runs the agent function and applies its result to the session,
   * returning the finish reason. Thrown exceptions cause the pending snapshot to finalize as {@code
   * FAILED}.
   *
   * @param <S> the type of custom session state
   */
  @FunctionalInterface
  public interface DetachedTurn<S> {
    /**
     * Runs the detached turn body.
     *
     * @return the finish reason
     * @throws Exception if the turn fails
     */
    AgentFinishReason run() throws Exception;
  }
}
