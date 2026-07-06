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

package com.google.genkit.plugins.postgresql.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.RuntimeError;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.ai.agent.SessionStoreOptions;
import com.google.genkit.ai.agent.SnapshotMutator;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.ai.agent.SnapshotSubscriber;
import com.google.genkit.ai.agent.internal.SnapshotSharding;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.jsonpatch.JsonPatch;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL-backed implementation of {@link SessionStore} and {@link SnapshotSubscriber}.
 *
 * <p>Persists session snapshots with the same sharded checkpoint + diff + pointer layout as the
 * Firestore, DynamoDB, and Cosmos DB backends (see {@code FirestoreSessionStore}), sharing the pure
 * logic in {@link SnapshotSharding}.
 *
 * <h3>Storage layout (single table)</h3>
 *
 * <p>All records live in one table (default {@code genkit_sessions}) keyed by {@code (pk, id)}. The
 * {@code pk} column holds the per-tenant prefix (default {@code "global"}); the {@code id} column
 * discriminates the record kind, each row carrying a JSONB {@code doc} payload and a {@code
 * version} counter:
 *
 * <ul>
 *   <li>{@code SNAP_<snapshotId>} — one metadata record per snapshot. {@code kind} is {@code
 *       "checkpoint"} or {@code "diff"}; carries {@code checkpointId}, {@code
 *       checkpointShardCount}, {@code segmentPath}, {@code statePatch} (RFC-6902 as a JSON string
 *       for diffs) and {@code error} (JSON string).
 *   <li>{@code SHARD_<checkpointId>_<index>} — a shard of the checkpoint state JSON.
 *   <li>{@code PTR_<sessionId>} — the current leaf pointer for a session.
 * </ul>
 *
 * <h3>Concurrency</h3>
 *
 * <p>Shard and snapshot records are idempotent by key (upserted); updating an existing snapshot id
 * uses a {@code version} conditional update, re-applying the (pure) mutator on conflict. The
 * session pointer is advanced monotonically (never backward) under the same {@code version}
 * concurrency. Shards are written before the snapshot record, which is written before the pointer
 * flips, so a reader following the pointer always sees complete data.
 *
 * @param <S> the type of custom session state
 */
public final class PostgresSessionStore<S> implements SessionStore<S>, SnapshotSubscriber {

  private static final Logger logger = LoggerFactory.getLogger(PostgresSessionStore.class);
  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  static final String KIND_CHECKPOINT = "checkpoint";
  static final String KIND_DIFF = "diff";

  private static final int MAX_ATTEMPTS = 5;

  private final DataSource dataSource;
  private final PostgresSessionStoreOptions options;
  private final String table;
  private final ScheduledExecutorService scheduler;

  /**
   * Creates a store with default options.
   *
   * @param dataSource the PostgreSQL data source
   */
  public PostgresSessionStore(DataSource dataSource) {
    this(dataSource, PostgresSessionStoreOptions.defaults());
  }

  /**
   * Creates a store.
   *
   * @param dataSource the PostgreSQL data source
   * @param options the store options
   */
  public PostgresSessionStore(DataSource dataSource, PostgresSessionStoreOptions options) {
    if (dataSource == null) {
      throw new IllegalArgumentException("DataSource must be non-null");
    }
    if (options == null) {
      throw new IllegalArgumentException("options must be non-null");
    }
    this.dataSource = dataSource;
    this.options = options;
    this.table = quoteIdentifier(options.getTableName());
    if (options.isCreateTableIfNotExists()) {
      ensureTable();
    }
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "genkit-postgres-session-store-poll");
              t.setDaemon(true);
              return t;
            });
  }

  private void ensureTable() {
    String sql =
        "CREATE TABLE IF NOT EXISTS "
            + table
            + " ("
            + "pk TEXT NOT NULL,"
            + "id TEXT NOT NULL,"
            + "doc JSONB NOT NULL,"
            + "version BIGINT NOT NULL DEFAULT 1,"
            + "PRIMARY KEY (pk, id))";
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
      logger.info("PostgreSQL session store initialized for table: {}", options.getTableName());
    } catch (SQLException e) {
      throw new GenkitException("Failed to create session table: " + e.getMessage(), e);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Id helpers
  // ──────────────────────────────────────────────────────────────────────────

  private String prefix(SessionStoreOptions opts) {
    String p =
        options.getSnapshotPathPrefix().apply(opts != null ? opts : SessionStoreOptions.empty());
    return (p == null || p.isBlank()) ? "global" : p;
  }

  private static String snapId(String snapshotId) {
    return "SNAP_" + snapshotId;
  }

  private static String shardId(String checkpointId, int index) {
    return "SHARD_" + checkpointId + "_" + index;
  }

  private static String ptrId(String sessionId) {
    return "PTR_" + sessionId;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotWriter
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Implements the same identity/sessionId/status defaulting contract as the reference stores,
   * then writes the snapshot (checkpoint shards + metadata record) and advances the session
   * pointer.
   */
  @Override
  public String saveSnapshot(
      String snapshotId, SnapshotMutator<S> mutator, SessionStoreOptions storeOpts) {
    String prefix = prefix(storeOpts);

    for (int attempt = 1; ; attempt++) {
      // 1. Read existing snapshot (+ version) and reconstruct state.
      SessionSnapshot<S> existing = null;
      String existingSessionId = null;
      Long existingVersion = null;
      if (snapshotId != null) {
        SnapshotSharding.validateId(snapshotId);
        Row row = readRow(prefix, snapId(snapshotId));
        if (row != null) {
          existing = readSnapshot(prefix, row.doc);
          existingSessionId = existing.getSessionId();
          existingVersion = row.version;
        }
      }

      // 2. Apply mutator (pure — safe to re-run on conflict retry).
      SessionSnapshot<S> result = mutator.apply(existing);
      if (result == null) {
        return null;
      }

      // 3. Identity / sessionId / status defaulting (mirror the reference stores).
      String finalId;
      if (snapshotId != null) {
        finalId = snapshotId;
      } else if (result.getSnapshotId() != null && !result.getSnapshotId().isBlank()) {
        finalId = result.getSnapshotId();
      } else {
        finalId = UUID.randomUUID().toString();
      }
      SnapshotSharding.validateId(finalId);
      result.setSnapshotId(finalId);

      if (existingSessionId != null) {
        result.setSessionId(existingSessionId);
      }
      if (result.getSessionId() == null && result.getState() != null) {
        result.setSessionId(result.getState().getSessionId());
      }
      if (result.getSessionId() == null || result.getSessionId().isBlank()) {
        throw GenkitException.builder()
            .message("snapshot requires sessionId")
            .errorCode("INVALID_ARGUMENT")
            .build();
      }
      if (result.getStatus() == null) {
        result.setStatus(SnapshotStatus.COMPLETED);
      }

      // 4. Resolve parent metadata to decide checkpoint vs diff.
      String parentId = result.getParentId();
      JsonNode newState = stateToJson(result.getState());

      ParentInfo parent = null;
      if (parentId != null && !parentId.isBlank()) {
        Row parentRow = readRow(prefix, snapId(parentId));
        if (parentRow != null) {
          parent = loadParentInfo(prefix, parentRow.doc);
        }
      }

      boolean parentExists = parent != null;
      int depthFromCheckpoint = 0;
      JsonNode statePatch = null;
      int diffSizeBytes = 0;
      if (parent != null) {
        depthFromCheckpoint = parent.segmentPath.size() + 1;
        statePatch = JsonPatch.diff(parent.state, newState);
        diffSizeBytes = jsonBytes(statePatch);
      }

      boolean checkpoint =
          SnapshotSharding.shouldCheckpoint(
              parentExists,
              depthFromCheckpoint,
              options.getCheckpointInterval(),
              diffSizeBytes,
              options.getShardSize());

      // 5. Build the snapshot metadata record (+ shard rows for a checkpoint).
      ObjectNode doc = baseDoc(result);
      String checkpointId;
      int checkpointShardCount;
      List<String> segmentPath;

      if (checkpoint) {
        checkpointId = finalId;
        String stateJson = writeJson(newState);
        List<String> shards = SnapshotSharding.shardString(stateJson, options.getShardSize());
        checkpointShardCount = shards.size();
        segmentPath = new ArrayList<>();
        for (int i = 0; i < shards.size(); i++) {
          ObjectNode shardDoc = MAPPER.createObjectNode();
          shardDoc.put("checkpointId", checkpointId);
          shardDoc.put("index", i);
          shardDoc.put("data", shards.get(i));
          upsertRow(prefix, shardId(checkpointId, i), shardDoc);
        }
        doc.put("kind", KIND_CHECKPOINT);
      } else {
        ParentInfo p = parent;
        if (p == null) {
          throw new GenkitException("internal: diff path without parent");
        }
        checkpointId = p.checkpointId;
        checkpointShardCount = p.checkpointShardCount;
        segmentPath = new ArrayList<>(p.segmentPath);
        segmentPath.add(finalId);
        doc.put("kind", KIND_DIFF);
        doc.put("statePatch", writeJson(statePatch));
      }

      doc.put("checkpointId", checkpointId);
      doc.put("checkpointShardCount", checkpointShardCount);
      putStringArray(doc, "segmentPath", segmentPath);

      // 6. Write the snapshot record with optimistic concurrency.
      boolean written = writeConditional(prefix, snapId(finalId), doc, existingVersion);
      if (!written) {
        if (attempt < MAX_ATTEMPTS) {
          continue; // concurrent writer won the race; re-read and retry the pure mutator.
        }
        throw new GenkitException("Failed to save snapshot after " + MAX_ATTEMPTS + " attempts");
      }

      // 7. Advance the session pointer (never backward).
      advancePointer(
          prefix,
          result.getSessionId(),
          finalId,
          result.getCreatedAt(),
          result.getUpdatedAt(),
          checkpointId,
          checkpointShardCount,
          segmentPath);

      return finalId;
    }
  }

  /** Advances the session pointer to the new leaf unless the stored pointer is already newer. */
  private void advancePointer(
      String prefix,
      String sessionId,
      String snapshotId,
      String createdAt,
      String updatedAt,
      String checkpointId,
      int checkpointShardCount,
      List<String> segmentPath) {
    String id = ptrId(sessionId);

    for (int attempt = 1; ; attempt++) {
      Row existing = readRow(prefix, id);
      Long version = existing != null ? existing.version : null;
      if (existing != null) {
        String currentLeaf = getString(existing.doc, "currentSnapshotId");
        String currentCreatedAt = getString(existing.doc, "currentCreatedAt");
        boolean sameLeaf = snapshotId.equals(currentLeaf);
        boolean newer =
            createdAt == null
                || currentCreatedAt == null
                || createdAt.compareTo(currentCreatedAt) >= 0;
        if (!sameLeaf && !newer) {
          return; // stored pointer is already newer; don't move backward.
        }
      }

      ObjectNode ptr = MAPPER.createObjectNode();
      ptr.put("currentSnapshotId", snapshotId);
      ptr.put("checkpointId", checkpointId);
      ptr.put("checkpointShardCount", checkpointShardCount);
      putStringArray(ptr, "segmentPath", segmentPath);
      if (createdAt != null) {
        ptr.put("currentCreatedAt", createdAt);
      }
      if (updatedAt != null) {
        ptr.put("updatedAt", updatedAt);
      }

      if (writeConditional(prefix, id, ptr, version)) {
        return;
      }
      if (attempt >= MAX_ATTEMPTS) {
        logger.debug("Pointer for session {} not advanced (lost concurrency race)", sessionId);
        return;
      }
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotReader
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>By {@code snapshotId}: loads the snapshot record and reconstructs its state from the
   * checkpoint shards + ordered {@code segmentPath} diffs. By {@code sessionId}: reads the pointer,
   * then loads and reconstructs the pointed snapshot.
   */
  @Override
  public SessionSnapshot<S> getSnapshot(GetSnapshotOptions opts) {
    if (opts == null) {
      return null;
    }
    String prefix = prefix(SessionStoreOptions.empty());

    if (opts.getSnapshotId() != null) {
      SnapshotSharding.validateId(opts.getSnapshotId());
      Row row = readRow(prefix, snapId(opts.getSnapshotId()));
      return row == null ? null : readSnapshot(prefix, row.doc);
    }
    if (opts.getSessionId() != null) {
      SnapshotSharding.validateId(opts.getSessionId());
      Row pointer = readRow(prefix, ptrId(opts.getSessionId()));
      if (pointer == null) {
        return null;
      }
      String leafId = getString(pointer.doc, "currentSnapshotId");
      if (leafId == null) {
        return null;
      }
      Row row = readRow(prefix, snapId(leafId));
      return row == null ? null : readSnapshot(prefix, row.doc);
    }
    return null;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotSubscriber (polling)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>The subscription polls {@link #getSnapshot} on a shared daemon scheduler and fires the
   * callback whenever the serialized snapshot content changes. The callback also fires immediately
   * if the snapshot already exists.
   */
  @Override
  public AutoCloseable onSnapshotStateChange(
      String snapshotId, Consumer<SessionSnapshot<?>> cb, SessionStoreOptions storeOpts) {
    SnapshotSharding.validateId(snapshotId);
    GetSnapshotOptions get = GetSnapshotOptions.builder().snapshotId(snapshotId).build();

    final String[] lastContent = {null};
    SessionSnapshot<S> initial = getSnapshot(get);
    if (initial != null) {
      lastContent[0] = serializeQuietly(initial);
      cb.accept(initial);
    }

    ScheduledFuture<?> future =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                SessionSnapshot<S> snap = getSnapshot(get);
                if (snap == null) {
                  return;
                }
                String content = serializeQuietly(snap);
                if (!content.equals(lastContent[0])) {
                  lastContent[0] = content;
                  cb.accept(snap);
                }
              } catch (Exception e) {
                // Swallow poll errors — don't kill the scheduler thread.
              }
            },
            options.getPollIntervalMs(),
            options.getPollIntervalMs(),
            TimeUnit.MILLISECONDS);

    return () -> future.cancel(false);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Document (de)serialization
  // ──────────────────────────────────────────────────────────────────────────

  /** Builds the non-state base document for a snapshot (metadata only). */
  private ObjectNode baseDoc(SessionSnapshot<S> snap) {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("snapshotId", snap.getSnapshotId());
    data.put("sessionId", snap.getSessionId());
    if (snap.getParentId() != null) {
      data.put("parentId", snap.getParentId());
    }
    if (snap.getCreatedAt() != null) {
      data.put("createdAt", snap.getCreatedAt());
    }
    if (snap.getUpdatedAt() != null) {
      data.put("updatedAt", snap.getUpdatedAt());
    }
    if (snap.getHeartbeatAt() != null) {
      data.put("heartbeatAt", snap.getHeartbeatAt());
    }
    if (snap.getStatus() != null) {
      data.put("status", snap.getStatus().getValue());
    }
    if (snap.getFinishReason() != null) {
      data.put("finishReason", snap.getFinishReason().getValue());
    }
    if (snap.getError() != null) {
      data.put("error", writeJson(snap.getError()));
    }
    return data;
  }

  /** Holds the reconstructed parent state and its checkpoint lineage. */
  private static final class ParentInfo {
    JsonNode state;
    String checkpointId;
    int checkpointShardCount;
    List<String> segmentPath;
  }

  /** Loads a parent snapshot's checkpoint lineage and reconstructs its state. */
  private ParentInfo loadParentInfo(String prefix, ObjectNode doc) {
    ParentInfo info = new ParentInfo();
    info.checkpointId = getString(doc, "checkpointId");
    info.checkpointShardCount = getInt(doc, "checkpointShardCount");
    info.segmentPath = getStringList(doc, "segmentPath");
    info.state =
        reconstructFullState(
            prefix, info.checkpointId, info.checkpointShardCount, info.segmentPath);
    return info;
  }

  /** Reads and fully reconstructs a snapshot from its metadata document. */
  private SessionSnapshot<S> readSnapshot(String prefix, ObjectNode doc) {
    String checkpointId = getString(doc, "checkpointId");
    int checkpointShardCount = getInt(doc, "checkpointShardCount");
    List<String> segmentPath = getStringList(doc, "segmentPath");
    JsonNode state = reconstructFullState(prefix, checkpointId, checkpointShardCount, segmentPath);
    return docToSnapshot(doc, state);
  }

  /**
   * Reconstructs full state: loads the checkpoint shards (concatenate, parse) then applies the
   * {@code segmentPath} diffs in order.
   */
  private JsonNode reconstructFullState(
      String prefix, String checkpointId, int checkpointShardCount, List<String> segmentPath) {
    if (checkpointId == null) {
      return NullNode.getInstance();
    }
    List<String> shardContents = new ArrayList<>();
    for (int i = 0; i < checkpointShardCount; i++) {
      Row shard = readRow(prefix, shardId(checkpointId, i));
      shardContents.add(shard != null ? getString(shard.doc, "data") : "");
    }
    String checkpointJson = SnapshotSharding.reassembleShards(shardContents);

    List<String> diffs = new ArrayList<>();
    for (String diffId : segmentPath) {
      Row diffRow = readRow(prefix, snapId(diffId));
      if (diffRow != null) {
        String patch = getString(diffRow.doc, "statePatch");
        if (patch != null) {
          diffs.add(patch);
        }
      }
    }
    try {
      return SnapshotSharding.reconstructState(checkpointJson, diffs);
    } catch (Exception e) {
      throw new GenkitException("Failed to reconstruct session state: " + e.getMessage(), e);
    }
  }

  /** Builds a {@link SessionSnapshot} from a metadata document and reconstructed state. */
  @SuppressWarnings("unchecked")
  private SessionSnapshot<S> docToSnapshot(ObjectNode doc, JsonNode state) {
    SessionSnapshot.Builder<S> builder = SessionSnapshot.<S>builder();
    builder.snapshotId(getString(doc, "snapshotId"));
    builder.sessionId(getString(doc, "sessionId"));
    builder.parentId(getString(doc, "parentId"));
    builder.createdAt(getString(doc, "createdAt"));
    builder.updatedAt(getString(doc, "updatedAt"));
    builder.heartbeatAt(getString(doc, "heartbeatAt"));
    String status = getString(doc, "status");
    if (status != null) {
      builder.status(SnapshotStatus.fromValueOrCompleted(status));
    }
    String finishReason = getString(doc, "finishReason");
    if (finishReason != null) {
      builder.finishReason(AgentFinishReason.fromValue(finishReason));
    }
    String errorJson = getString(doc, "error");
    if (errorJson != null) {
      try {
        builder.error(MAPPER.readValue(errorJson, RuntimeError.class));
      } catch (Exception e) {
        throw new GenkitException("Failed to parse snapshot error: " + e.getMessage(), e);
      }
    }
    if (state != null && !state.isNull()) {
      try {
        builder.state((SessionState<S>) MAPPER.treeToValue(state, SessionState.class));
      } catch (Exception e) {
        throw new GenkitException("Failed to parse session state: " + e.getMessage(), e);
      }
    }
    return builder.build();
  }

  /** Serializes session state to a JSON node (null state → JSON null). */
  private JsonNode stateToJson(SessionState<S> state) {
    if (state == null) {
      return NullNode.getInstance();
    }
    return MAPPER.valueToTree(state);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Low-level JDBC + node helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** A single stored row: its JSON document and optimistic-concurrency version. */
  private static final class Row {
    final ObjectNode doc;
    final long version;

    Row(ObjectNode doc, long version) {
      this.doc = doc;
      this.version = version;
    }
  }

  /** Reads a row by key, or {@code null} when it does not exist. */
  private Row readRow(String prefix, String id) {
    String sql = "SELECT doc, version FROM " + table + " WHERE pk = ? AND id = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, prefix);
      pstmt.setString(2, id);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        String docJson = rs.getString(1);
        long version = rs.getLong(2);
        return new Row((ObjectNode) MAPPER.readTree(docJson), version);
      }
    } catch (Exception e) {
      throw new GenkitException("Failed to read session row " + id + ": " + e.getMessage(), e);
    }
  }

  /** Idempotently upserts a row (used for shard records), bumping the version. */
  private void upsertRow(String prefix, String id, ObjectNode doc) {
    String sql =
        "INSERT INTO "
            + table
            + " (pk, id, doc, version) VALUES (?, ?, ?::jsonb, 1) "
            + "ON CONFLICT (pk, id) DO UPDATE SET doc = EXCLUDED.doc, version = "
            + table
            + ".version + 1";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, prefix);
      pstmt.setString(2, id);
      pstmt.setString(3, writeJson(doc));
      pstmt.executeUpdate();
    } catch (SQLException e) {
      throw new GenkitException("Failed to upsert session row " + id + ": " + e.getMessage(), e);
    }
  }

  /**
   * Writes a row with optimistic concurrency: inserts when {@code expectedVersion} is {@code null},
   * otherwise updates only when the stored version still matches. Returns {@code false} when a
   * concurrent writer won the race (the caller re-reads and retries the pure mutator).
   */
  private boolean writeConditional(String prefix, String id, ObjectNode doc, Long expectedVersion) {
    String docJson = writeJson(doc);
    if (expectedVersion == null) {
      String sql =
          "INSERT INTO "
              + table
              + " (pk, id, doc, version) VALUES (?, ?, ?::jsonb, 1) ON CONFLICT (pk, id) DO NOTHING";
      try (Connection conn = dataSource.getConnection();
          PreparedStatement pstmt = conn.prepareStatement(sql)) {
        pstmt.setString(1, prefix);
        pstmt.setString(2, id);
        pstmt.setString(3, docJson);
        return pstmt.executeUpdate() == 1;
      } catch (SQLException e) {
        throw new GenkitException("Failed to insert session row " + id + ": " + e.getMessage(), e);
      }
    }
    String sql =
        "UPDATE "
            + table
            + " SET doc = ?::jsonb, version = version + 1 WHERE pk = ? AND id = ? AND version = ?";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, docJson);
      pstmt.setString(2, prefix);
      pstmt.setString(3, id);
      pstmt.setLong(4, expectedVersion);
      return pstmt.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new GenkitException("Failed to update session row " + id + ": " + e.getMessage(), e);
    }
  }

  private static void putStringArray(ObjectNode node, String name, List<String> values) {
    ArrayNode arr = node.putArray(name);
    for (String v : values) {
      arr.add(v);
    }
  }

  private static String getString(ObjectNode node, String name) {
    JsonNode v = node.get(name);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  private static int getInt(ObjectNode node, String name) {
    JsonNode v = node.get(name);
    return (v == null || v.isNull()) ? 0 : v.asInt();
  }

  private static List<String> getStringList(ObjectNode node, String name) {
    List<String> out = new ArrayList<>();
    JsonNode v = node.get(name);
    if (v != null && v.isArray()) {
      for (JsonNode e : v) {
        out.add(e.asText());
      }
    }
    return out;
  }

  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new GenkitException("Failed to serialize value: " + e.getMessage(), e);
    }
  }

  private static int jsonBytes(Object value) {
    return writeJson(value).getBytes(StandardCharsets.UTF_8).length;
  }

  private static String serializeQuietly(SessionSnapshot<?> snap) {
    try {
      return MAPPER.writeValueAsString(MAPPER.valueToTree(snap));
    } catch (Exception e) {
      return "";
    }
  }

  /** Quotes a SQL identifier, doubling embedded double quotes. */
  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
