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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TDD tests for FileSessionStore (disk-backed session store). */
class FileSessionStoreTest {

  @TempDir Path tempDir;

  private FileSessionStore<Map<String, Object>> store;

  @BeforeEach
  void setUp() {
    store = new FileSessionStore<>(tempDir.toString());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static SessionSnapshot<Map<String, Object>> snapWithSession(String sessionId) {
    return SessionSnapshot.<Map<String, Object>>builder().sessionId(sessionId).build();
  }

  private static SessionSnapshot<Map<String, Object>> snapWithSessionAndStatus(
      String sessionId, SnapshotStatus status) {
    SessionSnapshot<Map<String, Object>> snap =
        SessionSnapshot.<Map<String, Object>>builder().sessionId(sessionId).build();
    snap.setStatus(status);
    return snap;
  }

  // ── file layout under global/ ─────────────────────────────────────────────

  @Test
  void testSave_fileExistsUnderGlobalDir() throws IOException {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-file"), SessionStoreOptions.empty());

    assertNotNull(id, "saveSnapshot must return a non-null id");
    Path snapshotFile = tempDir.resolve("global").resolve(id + ".json");
    assertTrue(Files.exists(snapshotFile), "snapshot file must exist under <dir>/global/<id>.json");
  }

  @Test
  void testGetSnapshot_bySnapshotId_roundTrip() {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-round-trip"), SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());

    assertNotNull(fetched, "getSnapshot by id must return a snapshot");
    assertEquals(id, fetched.getSnapshotId());
    assertEquals("sess-round-trip", fetched.getSessionId());
  }

  // ── getSnapshot by sessionId: latest leaf via pointer + scan fallback ─────

  @Test
  void testGetSnapshot_bySessionId_returnsLatestLeaf() {
    String rootId =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-chain");
              snap.setCreatedAt("2025-01-01T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

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

    SessionSnapshot<Map<String, Object>> leaf =
        store.getSnapshot(GetSnapshotOptions.builder().sessionId("sess-chain").build());
    assertNotNull(leaf, "getSnapshot by sessionId must return a snapshot");
    assertEquals(childId, leaf.getSnapshotId(), "must return the leaf (child)");
  }

  @Test
  void testGetSnapshot_bySessionId_scanFallback_afterPointerDeleted() throws IOException {
    String rootId =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-scan");
              snap.setCreatedAt("2025-01-01T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

    String childId =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-scan");
              snap.setParentId(rootId);
              snap.setCreatedAt("2025-01-02T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

    // Delete the pointer file to force scan fallback
    Path pointerFile = tempDir.resolve("global").resolve(".pointers").resolve("sess-scan.json");
    assertTrue(Files.exists(pointerFile), "pointer file must exist before deletion");
    Files.delete(pointerFile);

    // getSnapshot by sessionId should still work (scan fallback) and rewrite the pointer
    SessionSnapshot<Map<String, Object>> leaf =
        store.getSnapshot(GetSnapshotOptions.builder().sessionId("sess-scan").build());
    assertNotNull(leaf, "scan fallback must find the snapshot");
    assertEquals(childId, leaf.getSnapshotId(), "scan fallback must return the leaf (child)");

    // Pointer must be rewritten after scan
    assertTrue(Files.exists(pointerFile), "pointer file must be rewritten after scan fallback");
  }

  // ── atomic write: no leftover .tmp files ─────────────────────────────────

  @Test
  void testAtomicWrite_noTmpFilesLeft() throws IOException {
    store.saveSnapshot(
        null, existing -> snapWithSession("sess-atomic"), SessionStoreOptions.empty());

    Path globalDir = tempDir.resolve("global");
    try (Stream<Path> files = Files.list(globalDir)) {
      long tmpCount = files.filter(p -> p.toString().endsWith(".tmp")).count();
      assertEquals(0, tmpCount, "no .tmp files must remain after successful save");
    }
  }

  // ── chain pruning keeps newest N ─────────────────────────────────────────

  @Test
  void testChainPruning_keepsNewestN() throws IOException {
    FileSessionStore<Map<String, Object>> pruningStore =
        FileSessionStore.<Map<String, Object>>builder(tempDir.resolve("prunetest").toString())
            .maxPersistedChainLength(2)
            .build();

    // Save root
    String id1 =
        pruningStore.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-prune");
              snap.setCreatedAt("2025-01-01T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

    // Save child1 of root
    String id2 =
        pruningStore.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-prune");
              snap.setParentId(id1);
              snap.setCreatedAt("2025-01-02T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

    // Save child2 of child1 — this should trigger pruning, dropping id1
    String id3 =
        pruningStore.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-prune");
              snap.setParentId(id2);
              snap.setCreatedAt("2025-01-03T00:00:00Z");
              return snap;
            },
            SessionStoreOptions.empty());

    Path pruneDir = tempDir.resolve("prunetest").resolve("global");
    // Count only .json files that are not in the .pointers sub-directory
    try (Stream<Path> files = Files.list(pruneDir)) {
      long snapshotCount =
          files
              .filter(p -> p.getFileName().toString().endsWith(".json"))
              .filter(p -> !p.getParent().getFileName().toString().equals(".pointers"))
              .count();
      assertEquals(2, snapshotCount, "only 2 snapshot files must remain after pruning (newest 2)");
    }

    // The oldest (id1) must be deleted
    Path oldFile = pruneDir.resolve(id1 + ".json");
    assertFalse(Files.exists(oldFile), "oldest snapshot file must be pruned");

    // The two newest must still exist
    assertTrue(Files.exists(pruneDir.resolve(id2 + ".json")), "id2 must still exist");
    assertTrue(Files.exists(pruneDir.resolve(id3 + ".json")), "id3 must still exist");
  }

  // ── path safety: reject ../evil ──────────────────────────────────────────

  @Test
  void testPathSafety_rejectsDotDotSnapshotId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.saveSnapshot(
                "../evil", existing -> snapWithSession("sess-evil"), SessionStoreOptions.empty()),
        "saveSnapshot with '../evil' snapshotId must throw IllegalArgumentException");
  }

  @Test
  void testPathSafety_rejectsSlashInSnapshotId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.saveSnapshot(
                "a/b", existing -> snapWithSession("sess-evil"), SessionStoreOptions.empty()),
        "saveSnapshot with '/' in snapshotId must throw IllegalArgumentException");
  }

  @Test
  void testPathSafety_rejectsDotDotGetSnapshot() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.getSnapshot(GetSnapshotOptions.builder().snapshotId("../evil").build()),
        "getSnapshot with '../evil' snapshotId must throw IllegalArgumentException");
  }

  @Test
  void testPathSafety_rejectsDotDotInOnSnapshotStateChange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.onSnapshotStateChange("../evil", snap -> {}, SessionStoreOptions.empty()),
        "onSnapshotStateChange with '../evil' snapshotId must throw IllegalArgumentException");
  }

  // ── subscriber fires on PENDING→ABORTED status change ─────────────────────

  @Test
  void testSubscriber_firesOnStatusChange_pollingBased() throws Exception {
    // Use a fast poll interval for testing
    FileSessionStore<Map<String, Object>> fastStore =
        FileSessionStore.<Map<String, Object>>builder(tempDir.resolve("subtest").toString())
            .snapshotWatchPollIntervalMs(100)
            .build();

    // Save a PENDING snapshot first
    String id =
        fastStore.saveSnapshot(
            null,
            existing -> snapWithSessionAndStatus("sess-sub", SnapshotStatus.PENDING),
            SessionStoreOptions.empty());

    List<SessionSnapshot<?>> received = new CopyOnWriteArrayList<>();
    CountDownLatch changeLatch = new CountDownLatch(2); // initial + change

    AutoCloseable sub =
        fastStore.onSnapshotStateChange(
            id,
            snap -> {
              received.add(snap);
              changeLatch.countDown();
            },
            SessionStoreOptions.empty());

    // Save again as ABORTED (same store, should trigger polling callback)
    fastStore.saveSnapshot(
        id,
        existing -> snapWithSessionAndStatus("sess-sub", SnapshotStatus.ABORTED),
        SessionStoreOptions.empty());

    // Wait up to 3 seconds for both callbacks (immediate + change)
    boolean completed = changeLatch.await(3, TimeUnit.SECONDS);

    sub.close();

    assertTrue(completed, "subscriber must fire on PENDING→ABORTED status change within 3 seconds");
    assertFalse(received.isEmpty(), "received list must not be empty");
    // The last received snapshot must be ABORTED
    SessionSnapshot<?> last = received.get(received.size() - 1);
    assertEquals(
        SnapshotStatus.ABORTED, last.getStatus(), "subscriber must receive ABORTED status");
  }

  // ── status defaulting: null status → COMPLETED ───────────────────────────

  @Test
  void testSaveSnapshot_nullStatus_defaultsToCompleted() {
    String id =
        store.saveSnapshot(
            null,
            existing -> {
              SessionSnapshot<Map<String, Object>> snap = snapWithSession("sess-status");
              snap.setStatus(null);
              return snap;
            },
            SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(fetched);
    assertEquals(
        SnapshotStatus.COMPLETED, fetched.getStatus(), "null status must default to COMPLETED");
  }

  // ── empty sessionId → INVALID_ARGUMENT ───────────────────────────────────

  @Test
  void testSaveSnapshot_emptySessionId_throwsInvalidArgument() {
    com.google.genkit.core.GenkitException ex =
        assertThrows(
            com.google.genkit.core.GenkitException.class,
            () ->
                store.saveSnapshot(
                    null,
                    existing -> {
                      SessionSnapshot<Map<String, Object>> snap = new SessionSnapshot<>();
                      snap.setSessionId("");
                      return snap;
                    },
                    SessionStoreOptions.empty()));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
  }

  // ── mutator returning null → no-op ───────────────────────────────────────

  @Test
  void testSaveSnapshot_mutatorReturnsNull_noOp() {
    String id =
        store.saveSnapshot(
            null, existing -> snapWithSession("sess-noop"), SessionStoreOptions.empty());

    String result = store.saveSnapshot(id, existing -> null, SessionStoreOptions.empty());
    assertNull(result, "saveSnapshot with null mutator result must return null");

    // Original snapshot must still be present
    SessionSnapshot<Map<String, Object>> fetched =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(fetched, "snapshot must still be present after no-op");
    assertEquals("sess-noop", fetched.getSessionId());
  }

  // ── behavior 19: custom prefix creates a subdirectory named after it ────

  @Test
  void testCustomPrefixCreatesSubdirectory() throws IOException {
    FileSessionStore<Map<String, Object>> prefixedStore =
        FileSessionStore.<Map<String, Object>>builder(tempDir.toString())
            .prefix("tenant-1")
            .build();

    String id =
        prefixedStore.saveSnapshot(
            null, existing -> snapWithSession("sess-tenant"), SessionStoreOptions.empty());

    Path snapshotFile = tempDir.resolve("tenant-1").resolve(id + ".json");
    assertTrue(
        Files.exists(snapshotFile), "snapshot file must be created under <dir>/tenant-1/<id>.json");
    // Must NOT be written under the default "global" prefix.
    Path defaultLocation = tempDir.resolve("global").resolve(id + ".json");
    assertFalse(
        Files.exists(defaultLocation), "snapshot must not also exist under the default prefix");
  }
}
