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

package com.google.genkit.plugins.mongodb.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genkit.ai.Message;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.agent.SessionStoreOptions;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.core.GenkitException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MongoSessionStore}.
 *
 * <p>Integration tests are gated on the {@code MONGO_URI} environment variable (e.g. a local
 * MongoDB started with Docker). When it is unset the tests are skipped via {@link
 * org.junit.jupiter.api.Assumptions}.
 */
class MongoSessionStoreTest {

  private static final String URI = System.getenv("MONGO_URI");

  private MongoClient client;
  private String database;
  private String collection;
  private MongoSessionStore<Map<String, Object>> store;

  private static boolean configured() {
    return URI != null && !URI.isEmpty();
  }

  @BeforeEach
  void setUp() {
    if (!configured()) {
      return; // integration tests skip via assumeTrue
    }
    client = MongoClients.create(URI);
    database = "genkit_test";
    collection = "genkit_sessions_test_" + UUID.randomUUID().toString().replace("-", "");
    store =
        new MongoSessionStore<>(
            client,
            MongoSessionStoreOptions.builder()
                .databaseName(database)
                .collectionName(collection)
                .checkpointInterval(3)
                .build());
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      if (database != null && collection != null) {
        client.getDatabase(database).getCollection(collection).drop();
      }
      client.close();
    }
  }

  private static SessionSnapshot<Map<String, Object>> snapshotWithState(
      String sessionId, String parentId, Map<String, Object> custom) {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder()
            .sessionId(sessionId)
            .messages(List.of(Message.user("hello")))
            .custom(custom)
            .build();
    return SessionSnapshot.<Map<String, Object>>builder()
        .sessionId(sessionId)
        .parentId(parentId)
        .status(SnapshotStatus.COMPLETED)
        .state(state)
        .build();
  }

  @Test
  void saveThenGetBySnapshotIdRoundTrips() {
    assumeTrue(configured());
    String sessionId = "s-" + UUID.randomUUID();
    Map<String, Object> custom = new HashMap<>();
    custom.put("count", 1);

    String id =
        store.saveSnapshot(
            null, e -> snapshotWithState(sessionId, null, custom), SessionStoreOptions.empty());
    assertNotNull(id);

    SessionSnapshot<Map<String, Object>> got =
        store.getSnapshot(GetSnapshotOptions.builder().snapshotId(id).build());
    assertNotNull(got);
    assertEquals(sessionId, got.getSessionId());
    assertEquals(SnapshotStatus.COMPLETED, got.getStatus());
    assertEquals(1, got.getState().getMessages().size());
    assertEquals(1, ((Number) got.getState().getCustom().get("count")).intValue());
  }

  @Test
  void getBySessionIdReturnsLeaf() {
    assumeTrue(configured());
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
  void diffThenCheckpointReconstructs() {
    assumeTrue(configured());
    // checkpointInterval is 3; save 5 turns across checkpoint boundaries and confirm the leaf
    // reconstructs correctly (checkpoint shards + segment-path diffs).
    String sessionId = "s-" + UUID.randomUUID();
    String parent = null;
    String lastId = null;
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
  void rejectsEmptySessionId() {
    assumeTrue(configured());
    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () ->
                store.saveSnapshot(
                    null,
                    e -> snapshotWithState("", null, new HashMap<>()),
                    SessionStoreOptions.empty()));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
  }

  @Test
  void mutatorNullIsNoOp() {
    assumeTrue(configured());
    assertNull(store.saveSnapshot(null, e -> null, SessionStoreOptions.empty()));
  }

  @Test
  void getUnknownSessionReturnsNull() {
    assumeTrue(configured());
    assertNull(
        store.getSnapshot(
            GetSnapshotOptions.builder().sessionId("s-" + UUID.randomUUID()).build()));
  }
}
