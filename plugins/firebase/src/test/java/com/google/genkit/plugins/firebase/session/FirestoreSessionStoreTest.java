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

package com.google.genkit.plugins.firebase.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.agent.SessionStoreOptions;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.core.GenkitException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FirestoreSessionStore}.
 *
 * <ul>
 *   <li><b>Options unit tests</b> — validate {@link FirestoreSessionStoreOptions}. These always
 *       run.
 *   <li><b>Emulator-gated integration tests</b> — gated on {@code FIRESTORE_EMULATOR_HOST}; skipped
 *       (via {@link Assumptions#assumeTrue}) when no emulator is configured.
 * </ul>
 *
 * <p>The pure sharding / checkpoint / reconstruction helpers now live in {@code
 * com.google.genkit.ai.agent.internal.SnapshotSharding} and are unit-tested by {@code
 * SnapshotShardingTest} in the {@code ai} module.
 */
class FirestoreSessionStoreTest {

  // ──────────────────────────────────────────────────────────────────────────
  // Options unit tests (no Firestore)
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void optionsDefaults() {
    FirestoreSessionStoreOptions opts = FirestoreSessionStoreOptions.builder().build();
    assertEquals("genkit-sessions", opts.getCollection());
    assertEquals(25, opts.getCheckpointInterval());
    assertEquals(512 * 1024, opts.getShardSize());
    assertEquals("global", opts.getSnapshotPathPrefix().apply(SessionStoreOptions.empty()));
  }

  @Test
  void optionsCustomBuilder() {
    FirestoreSessionStoreOptions opts =
        FirestoreSessionStoreOptions.builder()
            .collection("my-sessions")
            .checkpointInterval(10)
            .shardSize(1024)
            .snapshotPathPrefix(o -> "tenant-1")
            .build();
    assertEquals("my-sessions", opts.getCollection());
    assertEquals(10, opts.getCheckpointInterval());
    assertEquals(1024, opts.getShardSize());
    assertEquals("tenant-1", opts.getSnapshotPathPrefix().apply(SessionStoreOptions.empty()));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Emulator-gated integration tests
  // ──────────────────────────────────────────────────────────────────────────

  private Firestore firestore;
  private FirestoreSessionStore<Map<String, Object>> store;
  private String collection;

  @BeforeEach
  void setUp() {
    String emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST");
    if (emulatorHost == null || emulatorHost.isEmpty()) {
      return; // emulator-gated tests will be skipped via assumeTrue
    }
    firestore =
        FirestoreOptions.getDefaultInstance().toBuilder()
            .setProjectId("genkit-test")
            .setEmulatorHost(emulatorHost)
            .build()
            .getService();
    // Unique collection per run to isolate documents.
    collection = "genkit-sessions-test-" + UUID.randomUUID().toString().substring(0, 8);
    store =
        new FirestoreSessionStore<>(
            firestore,
            FirestoreSessionStoreOptions.builder()
                .collection(collection)
                .checkpointInterval(3)
                .build());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (firestore != null) {
      firestore.close();
    }
  }

  private static SessionSnapshot<Map<String, Object>> snapshotWithState(
      String sessionId, String parentId, Map<String, Object> custom) {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId(sessionId).custom(custom).build();
    return SessionSnapshot.<Map<String, Object>>builder()
        .sessionId(sessionId)
        .parentId(parentId)
        .status(SnapshotStatus.COMPLETED)
        .state(state)
        .build();
  }

  @Test
  void emulatorSaveGetRoundTrip() {
    Assumptions.assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null);

    String sessionId = "s-" + UUID.randomUUID();
    Map<String, Object> custom = new HashMap<>();
    custom.put("count", 1);

    String id =
        store.saveSnapshot(
            null,
            existing -> snapshotWithState(sessionId, null, custom),
            SessionStoreOptions.empty());
    assertNotNull(id);

    SessionSnapshot<Map<String, Object>> got =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(got);
    assertEquals(sessionId, got.getSessionId());
    assertEquals(SnapshotStatus.COMPLETED, got.getStatus());
    assertNotNull(got.getState());
    assertEquals(1, ((Number) got.getState().getCustom().get("count")).intValue());
  }

  @Test
  void emulatorRejectsEmptySessionId() {
    Assumptions.assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null);
    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () ->
                store.saveSnapshot(
                    null,
                    existing -> snapshotWithState("", null, new HashMap<>()),
                    SessionStoreOptions.empty()));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
  }

  @Test
  void emulatorMutatorNullIsNoOp() {
    Assumptions.assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null);
    String result = store.saveSnapshot(null, existing -> null, SessionStoreOptions.empty());
    assertNull(result);
  }

  @Test
  void emulatorDiffChainReconstructs() {
    Assumptions.assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null);

    String sessionId = "s-" + UUID.randomUUID();

    // Turn 1 (checkpoint - root)
    Map<String, Object> c1 = new HashMap<>();
    c1.put("count", 1);
    String id1 =
        store.saveSnapshot(
            null, e -> snapshotWithState(sessionId, null, c1), SessionStoreOptions.empty());

    // Turn 2 (diff)
    Map<String, Object> c2 = new HashMap<>();
    c2.put("count", 2);
    String id2 =
        store.saveSnapshot(
            null, e -> snapshotWithState(sessionId, id1, c2), SessionStoreOptions.empty());

    // Turn 3 (diff)
    Map<String, Object> c3 = new HashMap<>();
    c3.put("count", 3);
    c3.put("extra", "z");
    String id3 =
        store.saveSnapshot(
            null, e -> snapshotWithState(sessionId, id2, c3), SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> got =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id3).build());
    assertNotNull(got);
    assertEquals(3, ((Number) got.getState().getCustom().get("count")).intValue());
    assertEquals("z", got.getState().getCustom().get("extra"));
  }

  @Test
  void emulatorGetLatestViaPointer() {
    Assumptions.assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null);

    String sessionId = "s-" + UUID.randomUUID();
    Map<String, Object> c1 = new HashMap<>();
    c1.put("count", 1);
    String id1 =
        store.saveSnapshot(
            null, e -> snapshotWithState(sessionId, null, c1), SessionStoreOptions.empty());
    Map<String, Object> c2 = new HashMap<>();
    c2.put("count", 2);
    String id2 =
        store.saveSnapshot(
            null, e -> snapshotWithState(sessionId, id1, c2), SessionStoreOptions.empty());

    SessionSnapshot<Map<String, Object>> latest =
        store.getSnapshot(GetSnapshotOptions.builder().sessionId(sessionId).build());
    assertNotNull(latest);
    assertEquals(id2, latest.getSnapshotId());
    assertEquals(2, ((Number) latest.getState().getCustom().get("count")).intValue());
  }

  @Test
  void emulatorCheckpointEveryInterval() {
    Assumptions.assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null);

    String sessionId = "s-" + UUID.randomUUID();
    String parent = null;
    String lastId = null;
    // checkpointInterval is 3; save 5 turns and confirm reconstruction stays correct.
    for (int i = 1; i <= 5; i++) {
      Map<String, Object> c = new HashMap<>();
      c.put("count", i);
      final String p = parent;
      lastId =
          store.saveSnapshot(
              null, e -> snapshotWithState(sessionId, p, c), SessionStoreOptions.empty());
      parent = lastId;
    }
    SessionSnapshot<Map<String, Object>> got =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(lastId).build());
    assertNotNull(got);
    assertEquals(5, ((Number) got.getState().getCustom().get("count")).intValue());
  }

  @Test
  void emulatorOnSnapshotStateChangeFires() throws Exception {
    Assumptions.assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null);

    String sessionId = "s-" + UUID.randomUUID();
    Map<String, Object> c1 = new HashMap<>();
    c1.put("count", 1);
    String id =
        store.saveSnapshot(
            null, e -> snapshotWithState(sessionId, null, c1), SessionStoreOptions.empty());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<SessionSnapshot<?>> seen = new AtomicReference<>();
    try (AutoCloseable sub =
        store.onSnapshotStateChange(
            id,
            snap -> {
              seen.set(snap);
              latch.countDown();
            },
            SessionStoreOptions.empty())) {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "callback should fire at subscription time");
      assertNotNull(seen.get());
      assertEquals(id, seen.get().getSnapshotId());
    }
  }
}
