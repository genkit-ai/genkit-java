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

import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for InMemorySessionStore (agent package). */
class InMemorySessionStoreTest {

  private InMemorySessionStore<Map<String, Object>> store;

  @BeforeEach
  void setUp() {
    store = new InMemorySessionStore<>();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static SessionSnapshot<Map<String, Object>> snapWithSession(String sessionId) {
    return SessionSnapshot.<Map<String, Object>>builder().sessionId(sessionId).build();
  }

  private static SessionSnapshot<Map<String, Object>> snapWithSessionAndCustom(
      String sessionId, Map<String, Object> custom) {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId(sessionId).custom(custom).build();
    return SessionSnapshot.<Map<String, Object>>builder().sessionId(sessionId).state(state).build();
  }

  // ── save/get round-trip ───────────────────────────────────────────────────────

  @Test
  void testSaveNewSnapshot_mintsUuid_andReturnsIt() {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-1"), SessionStoreOptions.empty());
    assertNotNull(id, "saveSnapshot must return a non-null id");
    assertFalse(id.isBlank(), "minted id must not be blank");
  }

  @Test
  void testGetSnapshot_bySnapshotId_roundTrip() {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-1"), SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());

    assertNotNull(fetched, "getSnapshot by id must return a snapshot");
    assertEquals(id, fetched.getSnapshotId());
    assertEquals("sess-1", fetched.getSessionId());
  }

  @Test
  void testGetSnapshot_returnsDeepCopy_mutatingReturnedSnapshotDoesNotChangeStore() {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-1"), SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(fetched);

    // Mutate the returned snapshot
    fetched.setSessionId("MUTATED");

    // Re-fetch from store — must still be original
    SessionSnapshot<Map<String, Object>> refetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertEquals(
        "sess-1", refetched.getSessionId(), "store must not reflect mutation of returned snapshot");
  }

  @Test
  void testSaveSnapshot_deepCopyOnStore_mutatingInputDoesNotChangeStore() {
    Map<String, Object> custom = new HashMap<>();
    custom.put("key", "original");

    String id =
        store.saveSnapshot(
            null,
            existing -> snapWithSessionAndCustom("sess-2", custom),
            SessionStoreOptions.empty());

    // Mutate the custom map after save
    custom.put("key", "mutated");

    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(fetched);
    assertNotNull(fetched.getState());
    // The stored value must reflect "original", not "mutated"
    assertEquals(
        "original",
        fetched.getState().getCustom().get("key"),
        "store must not reflect post-save mutation of mutator's returned snapshot");
  }

  // ── mint UUID ────────────────────────────────────────────────────────────────

  @Test
  void testSaveSnapshot_nullId_mintsUniqueUuids() {
    String id1 =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-a"), SessionStoreOptions.empty());
    String id2 =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-b"), SessionStoreOptions.empty());
    assertNotEquals(id1, id2, "each save with null id must produce a unique id");
  }

  // ── getSnapshot by sessionId (latest leaf) ────────────────────────────────────

  @Test
  void testGetSnapshot_bySessionId_returnsLatestLeaf() {
    // Save root snapshot
    String rootId =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-chain"), SessionStoreOptions.empty());

    // Save a second snapshot with parentId pointing to first
    String childId =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-chain");
              snap.setParentId(rootId);
              snap.setCreatedAt("2025-01-02T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

    // getSnapshot by sessionId must return the leaf (child)
    SessionSnapshot<Map<String, Object>> leaf =
        store.getSnapshot(GetSnapshotOptions.builder().sessionId("sess-chain").build());
    assertNotNull(leaf, "getSnapshot by sessionId must return a snapshot");
    assertEquals(childId, leaf.getSnapshotId(), "must return the leaf (child)");
  }

  @Test
  void testGetSnapshot_bySessionId_noMatch_returnsNull() {
    SessionSnapshot<Map<String, Object>> result =
        store.getSnapshot(GetSnapshotOptions.builder().sessionId("nonexistent").build());
    assertNull(result, "getSnapshot by sessionId with no match must return null");
  }

  // ── preserve existing sessionId ───────────────────────────────────────────────

  @Test
  void testSaveSnapshot_preservesExistingSessionId_whenUpdatingById() {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-orig"), SessionStoreOptions.empty());

    // Update with a different sessionId in the mutator — store should preserve original
    store.saveSnapshot(
        id,
        existing -> {
          assertNotNull(existing, "mutator should receive the existing snapshot");
          assertEquals("sess-orig", existing.getSessionId());
          // Return snapshot with a different sessionId — store must ignore this
          return snapWithSession("sess-override");
        },
        SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertEquals(
        "sess-orig", fetched.getSessionId(), "store must preserve existing sessionId on update");
  }

  // ── mutator returning null → no-op ────────────────────────────────────────────

  @Test
  void testSaveSnapshot_mutatorReturnsNull_noOp_returnsNull() {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-noop"), SessionStoreOptions.empty());

    // Attempt update with mutator returning null
    String result = store.saveSnapshot(id, existing -> null, SessionStoreOptions.empty());
    assertNull(result, "saveSnapshot with null mutator result must return null");

    // Original snapshot must still be unchanged
    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(fetched, "snapshot must still be present after no-op");
    assertEquals("sess-noop", fetched.getSessionId());
  }

  // ── empty sessionId → INVALID_ARGUMENT ───────────────────────────────────────

  @Test
  void testSaveSnapshot_emptySessionId_throwsInvalidArgument() {
    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () ->
                store.saveSnapshot(
                    null,
                    existing -> {
                      SessionSnapshot<Map<String, Object>> snap = new SessionSnapshot<>();
                      snap.setSessionId(""); // empty
                      return snap;
                    },
                    SessionStoreOptions.empty()));
    assertEquals(
        "INVALID_ARGUMENT",
        ex.getErrorCode(),
        "empty sessionId must throw GenkitException with INVALID_ARGUMENT");
  }

  @Test
  void testSaveSnapshot_nullSessionId_throwsInvalidArgument() {
    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () ->
                store.saveSnapshot(
                    null,
                    existing -> {
                      // Return snapshot with no sessionId at all
                      return new SessionSnapshot<>();
                    },
                    SessionStoreOptions.empty()));
    assertEquals(
        "INVALID_ARGUMENT",
        ex.getErrorCode(),
        "null sessionId must throw GenkitException with INVALID_ARGUMENT");
  }

  // ── null status → defaults to COMPLETED ──────────────────────────────────────

  @Test
  void testSaveSnapshot_nullStatus_defaultsToCompleted() {
    String id =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-status");
              snap.setStatus(null); // explicitly null
              return snap;
            },
            SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(fetched);
    assertEquals(
        SnapshotStatus.COMPLETED,
        fetched.getStatus(),
        "null status must be defaulted to COMPLETED");
  }

  // ── subscriber fires on status change ─────────────────────────────────────────

  @Test
  void testSubscriber_firesOnStatusChange() throws Exception {
    // Save an initial PENDING snapshot
    String id =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-sub");
              snap.setStatus(SnapshotStatus.PENDING);
              return snap;
            },
            SessionStoreOptions.empty());

    List<SessionSnapshot<?>> received = new ArrayList<>();

    // Subscribe — receives current snapshot immediately since it already exists
    AutoCloseable sub = store.onSnapshotStateChange(id, received::add, SessionStoreOptions.empty());

    // Immediate callback fires with current PENDING snapshot
    assertEquals(
        1,
        received.size(),
        "subscriber must receive current snapshot immediately when existing snapshot is present");
    assertEquals(
        SnapshotStatus.PENDING,
        received.get(0).getStatus(),
        "initial callback must have current PENDING status");

    // Now save again, flipping status to ABORTED
    store.saveSnapshot(
        id,
        existing -> {
          SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-sub");
          snap.setStatus(SnapshotStatus.ABORTED);
          return snap;
        },
        SessionStoreOptions.empty());

    // Subscriber must have been called again with the updated snapshot
    assertEquals(2, received.size(), "subscriber must fire again on subsequent status change");
    assertEquals(
        SnapshotStatus.ABORTED,
        received.get(received.size() - 1).getStatus(),
        "subscriber must receive snapshot with new status ABORTED");

    sub.close();
  }

  @Test
  void testSubscriber_doesNotFireWhenStatusUnchanged() {
    // Save an initial PENDING snapshot
    String id =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-no-fire");
              snap.setStatus(SnapshotStatus.PENDING);
              return snap;
            },
            SessionStoreOptions.empty());

    List<SessionSnapshot<?>> received = new ArrayList<>();
    AutoCloseable sub = store.onSnapshotStateChange(id, received::add, SessionStoreOptions.empty());

    // Immediate callback fires with current PENDING snapshot
    assertEquals(1, received.size(), "immediate callback must fire");

    // Save again with same status — subscriber should NOT fire (no status change)
    store.saveSnapshot(
        id,
        existing -> {
          SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-no-fire");
          snap.setStatus(SnapshotStatus.PENDING);
          return snap;
        },
        SessionStoreOptions.empty());

    // Still only 1 callback (the immediate one); no additional fire on unchanged status
    assertEquals(
        1,
        received.size(),
        "subscriber must not fire when status is unchanged (only immediate callback)");

    try {
      sub.close();
    } catch (Exception e) {
      // ignore
    }
  }

  @Test
  void testSubscriber_unsubscribeStopsNotifications() {
    String id =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-unsub");
              snap.setStatus(SnapshotStatus.PENDING);
              return snap;
            },
            SessionStoreOptions.empty());

    List<SessionSnapshot<?>> received = new ArrayList<>();
    AutoCloseable sub = store.onSnapshotStateChange(id, received::add, SessionStoreOptions.empty());

    // Immediate callback fires with current PENDING snapshot
    assertEquals(1, received.size(), "immediate callback must fire");

    try {
      sub.close();
    } catch (Exception e) {
      fail("close() must not throw");
    }

    // Now save again — subscriber is closed, must not fire (no additional callback)
    store.saveSnapshot(
        id,
        existing -> {
          SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-unsub");
          snap.setStatus(SnapshotStatus.ABORTED);
          return snap;
        },
        SessionStoreOptions.empty());

    // Still only 1 callback (the immediate one before unsubscribe)
    assertEquals(1, received.size(), "closed subscriber must not receive further notifications");
  }

  // ── first save fires subscriber (status: null→COMPLETED counts as creation) ───

  @Test
  void testSubscriber_firesOnFirstSave_whenSubscribedBeforeSave() {
    // Subscribe to a snapshotId that doesn't exist yet
    List<SessionSnapshot<?>> received = new ArrayList<>();
    AtomicReference<AutoCloseable> subRef = new AtomicReference<>();

    // We subscribe before saving — since snapshot doesn't exist yet, no immediate callback
    // After first save (null→COMPLETED transition), subscriber fires
    subRef.set(
        store.onSnapshotStateChange("future-id", received::add, SessionStoreOptions.empty()));

    // Save with explicit id
    store.saveSnapshot(
        "future-id",
        existing -> {
          SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-future");
          snap.setStatus(SnapshotStatus.COMPLETED);
          return snap;
        },
        SessionStoreOptions.empty());

    // existing was null, so "null != COMPLETED" → subscriber fires
    assertFalse(received.isEmpty(), "subscriber must fire on first save (null→COMPLETED)");

    try {
      subRef.get().close();
    } catch (Exception e) {
      // ignore
    }
  }

  // ── getSnapshot with neither snapshotId nor sessionId → null ─────────────────

  @Test
  void testGetSnapshot_neitherIdSet_returnsNull() {
    SessionSnapshot<Map<String, Object>> result =
        store.getSnapshot(GetSnapshotOptions.builder().build());
    assertNull(result, "getSnapshot with no ids must return null");
  }

  // ── rejectBranching constructor ───────────────────────────────────────────────

  @Test
  void testRejectBranchingConstructor_canBeCreated() {
    InMemorySessionStore<Map<String, Object>> strictStore = new InMemorySessionStore<>(true);
    assertNotNull(strictStore);

    // Single snapshot — no branching, should work fine
    String id =
        strictStore.saveSnapshot(
            null, existing -> snapWithSession("sess-strict"), SessionStoreOptions.empty());
    assertNotNull(id);
  }

  // ── subscribe-after-terminal: callback fires immediately with current terminal status ──

  @Test
  void testSubscriber_subscribeAfterTerminal_firesImmediatelyWithCurrentStatus() throws Exception {
    // Save a snapshot with terminal status ABORTED
    String id =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-terminal");
              snap.setStatus(SnapshotStatus.ABORTED);
              return snap;
            },
            SessionStoreOptions.empty());

    // Verify snapshot is stored with ABORTED status
    SessionSnapshot<Map<String, Object>> stored =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(stored);
    assertEquals(SnapshotStatus.ABORTED, stored.getStatus());

    // NOW subscribe (after the terminal save)
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<SessionSnapshot<?>> received = new AtomicReference<>();

    AutoCloseable sub =
        store.onSnapshotStateChange(
            id,
            snap -> {
              received.set(snap);
              latch.countDown();
            },
            SessionStoreOptions.empty());

    // Callback must fire immediately with the current ABORTED snapshot
    boolean completed = latch.await(1, TimeUnit.SECONDS);
    assertTrue(completed, "callback must fire immediately when snapshot already exists");
    assertNotNull(received.get(), "received snapshot must not be null");
    assertEquals(
        SnapshotStatus.ABORTED,
        received.get().getStatus(),
        "immediate callback must have current ABORTED status");

    try {
      sub.close();
    } catch (Exception e) {
      // ignore
    }
  }

  // ── behavior 17: rejectBranching throws when two leaves share a session ──────

  @Test
  void testRejectBranchingThrowsWhenTwoLeaves() {
    InMemorySessionStore<Map<String, Object>> strictStore = new InMemorySessionStore<>(true);

    // Save a root snapshot for the session.
    String rootId =
        strictStore.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-branch");
              snap.setCreatedAt("2025-01-01T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

    // Save two children of the SAME root — both are leaves (neither is referenced as a parentId).
    strictStore.saveSnapshot(
        null,
        existing -> {
          SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-branch");
          snap.setParentId(rootId);
          snap.setCreatedAt("2025-01-02T00:00:00Z");
          return snap;
        },
        SessionStoreOptions.empty());
    strictStore.saveSnapshot(
        null,
        existing -> {
          SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-branch");
          snap.setParentId(rootId);
          snap.setCreatedAt("2025-01-02T00:00:01Z");
          return snap;
        },
        SessionStoreOptions.empty());

    // getSnapshot by sessionId must throw FAILED_PRECONDITION: two leaves detected.
    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () ->
                strictStore.getSnapshot(
                    GetSnapshotOptions.builder().sessionId("sess-branch").build()));
    assertEquals("FAILED_PRECONDITION", ex.getErrorCode());
  }
}
