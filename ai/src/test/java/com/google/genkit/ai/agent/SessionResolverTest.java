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

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.ai.agent.internal.SessionResolver;
import com.google.genkit.core.GenkitException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for SessionResolver. */
class SessionResolverTest {

  private InMemorySessionStore<Map<String, Object>> store;
  private SessionStoreOptions opts;

  @BeforeEach
  void setUp() {
    store = new InMemorySessionStore<>();
    opts = SessionStoreOptions.empty();
  }

  // ── Helper ───────────────────────────────────────────────────────────────────

  /** Saves a snapshot with the given sessionId and status into the store. Returns snapshotId. */
  private String seedSnapshot(String sessionId, SnapshotStatus status) {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId(sessionId).build();
    SessionSnapshot<Map<String, Object>> snap =
        SessionSnapshot.<Map<String, Object>>builder()
            .sessionId(sessionId)
            .status(status)
            .state(state)
            .build();
    return store.saveSnapshot(null, existing -> snap, opts);
  }

  // ── Client-managed ────────────────────────────────────────────────────────────

  @Test
  void clientManaged_withState_hydratesSession() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("client-session-1").build();
    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().state(state).build();

    SessionResolver.Resolution<Map<String, Object>> result =
        SessionResolver.resolve(null, false, init, opts);

    assertTrue(result.isOk());
    assertNotNull(result.session());
    assertEquals("client-session-1", result.session().sessionId());
  }

  @Test
  void clientManaged_withSessionId_throwsFailedPrecondition() {
    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().sessionId("some-id").build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> SessionResolver.resolve(null, false, init, opts));
    assertEquals("FAILED_PRECONDITION", ex.getErrorCode());
    assertTrue(ex.getMessage().contains("sessionId"));
  }

  @Test
  void clientManaged_withSnapshotId_throwsFailedPrecondition() {
    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().snapshotId("snap-1").build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> SessionResolver.resolve(null, false, init, opts));
    assertEquals("FAILED_PRECONDITION", ex.getErrorCode());
    assertTrue(ex.getMessage().contains("snapshotId"));
  }

  @Test
  void clientManaged_noInit_freshSession() {
    SessionResolver.Resolution<Map<String, Object>> result =
        SessionResolver.resolve(null, false, null, opts);

    assertTrue(result.isOk());
    assertNotNull(result.session());
    assertNotNull(result.session().sessionId());
  }

  // ── Server-managed: state → THROW ────────────────────────────────────────────

  @Test
  void serverManaged_withState_throwsFailedPrecondition() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("s1").build();
    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().state(state).build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> SessionResolver.resolve(store, true, init, opts));
    assertEquals("FAILED_PRECONDITION", ex.getErrorCode());
    assertTrue(ex.getMessage().contains("state"));
  }

  // ── Server-managed: snapshotId branch ────────────────────────────────────────

  @Test
  void serverManaged_snapshotId_completed_hydratesSession() {
    String sid = "sess-snap-1";
    String snapId = seedSnapshot(sid, SnapshotStatus.COMPLETED);

    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().snapshotId(snapId).build();

    SessionResolver.Resolution<Map<String, Object>> result =
        SessionResolver.resolve(store, true, init, opts);

    assertTrue(result.isOk());
    assertEquals(sid, result.session().sessionId());
  }

  @Test
  void serverManaged_unknownSnapshotId_throwsInvalidArgument() {
    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().snapshotId("nonexistent-snap").build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> SessionResolver.resolve(store, true, init, opts));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
    assertTrue(ex.getMessage().toLowerCase().contains("snapshot"));
  }

  @Test
  void serverManaged_nonCompletedSnapshot_throwsInvalidArgument() {
    String snapId = seedSnapshot("sess-pending", SnapshotStatus.PENDING);

    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().snapshotId(snapId).build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> SessionResolver.resolve(store, true, init, opts));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
    assertTrue(ex.getMessage().toLowerCase().contains("resumable"));
  }

  @Test
  void serverManaged_snapshotId_withMismatchedSessionId_throwsInvalidArgument() {
    String snapId = seedSnapshot("sess-real", SnapshotStatus.COMPLETED);

    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder()
            .snapshotId(snapId)
            .sessionId("wrong-session-id")
            .build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> SessionResolver.resolve(store, true, init, opts));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
    assertTrue(ex.getMessage().toLowerCase().contains("session"));
  }

  // ── Server-managed: sessionId branch ─────────────────────────────────────────

  @Test
  void serverManaged_sessionId_existingCompletedLeaf_hydratesSession() {
    String sid = "sess-existing";
    seedSnapshot(sid, SnapshotStatus.COMPLETED);

    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().sessionId(sid).build();

    SessionResolver.Resolution<Map<String, Object>> result =
        SessionResolver.resolve(store, true, init, opts);

    assertTrue(result.isOk());
    assertEquals(sid, result.session().sessionId());
  }

  @Test
  void serverManaged_sessionId_unknownSession_freshSessionBoundToId() {
    String sid = "new-unknown-session";
    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().sessionId(sid).build();

    SessionResolver.Resolution<Map<String, Object>> result =
        SessionResolver.resolve(store, true, init, opts);

    assertTrue(result.isOk());
    assertEquals(sid, result.session().sessionId());
  }

  @Test
  void serverManaged_sessionId_pendingLeaf_throwsFailedPrecondition() {
    String sid = "sess-with-pending";
    seedSnapshot(sid, SnapshotStatus.PENDING);

    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().sessionId(sid).build();

    GenkitException ex =
        assertThrows(GenkitException.class, () -> SessionResolver.resolve(store, true, init, opts));
    assertEquals("FAILED_PRECONDITION", ex.getErrorCode());
    assertTrue(ex.getMessage().toLowerCase().contains("resume"));
  }

  // ── Server-managed: no init → fresh session ───────────────────────────────────

  @Test
  void serverManaged_noInit_freshSession() {
    SessionResolver.Resolution<Map<String, Object>> result =
        SessionResolver.resolve(store, true, null, opts);

    assertTrue(result.isOk());
    assertNotNull(result.session());
    assertNotNull(result.session().sessionId());
  }

  // ── Resolution type ───────────────────────────────────────────────────────────

  @Test
  void resolution_failure_returnsFailureResolution() {
    RuntimeError err = RuntimeError.builder().status("FAILED").message("something wrong").build();
    SessionResolver.Resolution<Map<String, Object>> failure =
        SessionResolver.Resolution.failure(err);

    assertFalse(failure.isOk());
    assertNull(failure.session());
    assertNotNull(failure.error());
    assertEquals("something wrong", failure.error().getMessage());
  }
}
