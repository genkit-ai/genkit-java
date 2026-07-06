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
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-backed implementation of {@link SessionStore} and {@link SnapshotSubscriber}.
 *
 * <p>Persists session snapshots with the same sharded checkpoint + diff + pointer layout as the
 * Firestore, DynamoDB, Cosmos DB, and PostgreSQL backends (see {@code FirestoreSessionStore}),
 * sharing the pure logic in {@link SnapshotSharding}.
 *
 * <h3>Storage layout (single collection)</h3>
 *
 * <p>All documents live in one collection (default database {@code genkit}, collection {@code
 * genkit_sessions}). Each document's {@code _id} is {@code <prefix>::<recordId>} where {@code
 * prefix} is the per-tenant prefix (default {@code "global"}) and {@code recordId} discriminates
 * the record kind:
 *
 * <ul>
 *   <li>{@code SNAP_<snapshotId>} — one metadata document per snapshot. {@code kind} is {@code
 *       "checkpoint"} or {@code "diff"}; carries {@code checkpointId}, {@code
 *       checkpointShardCount}, {@code segmentPath}, {@code statePatch} (RFC-6902 as a JSON string
 *       for diffs) and {@code error} (JSON string).
 *   <li>{@code SHARD_<checkpointId>_<index>} — a shard of the checkpoint state JSON.
 *   <li>{@code PTR_<sessionId>} — the current leaf pointer for a session.
 * </ul>
 *
 * <h3>Concurrency</h3>
 *
 * <p>Shard and snapshot documents are idempotent by {@code _id} (upserted); updating an existing
 * snapshot id uses a {@code version} conditional replace, re-applying the (pure) mutator on
 * conflict. The session pointer is advanced monotonically (never backward) under the same {@code
 * version} concurrency. Shards are written before the snapshot document, which is written before
 * the pointer flips, so a reader following the pointer always sees complete data.
 *
 * @param <S> the type of custom session state
 */
public final class MongoSessionStore<S> implements SessionStore<S>, SnapshotSubscriber {

  private static final Logger logger = LoggerFactory.getLogger(MongoSessionStore.class);
  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  static final String KIND_CHECKPOINT = "checkpoint";
  static final String KIND_DIFF = "diff";

  private static final int MAX_ATTEMPTS = 5;

  private final MongoCollection<Document> collection;
  private final MongoSessionStoreOptions options;
  private final ScheduledExecutorService scheduler;

  /**
   * Creates a store with default options.
   *
   * @param client the MongoDB client
   */
  public MongoSessionStore(MongoClient client) {
    this(client, MongoSessionStoreOptions.defaults());
  }

  /**
   * Creates a store.
   *
   * @param client the MongoDB client
   * @param options the store options
   */
  public MongoSessionStore(MongoClient client, MongoSessionStoreOptions options) {
    if (client == null) {
      throw new IllegalArgumentException("MongoClient must be non-null");
    }
    if (options == null) {
      throw new IllegalArgumentException("options must be non-null");
    }
    this.options = options;
    this.collection =
        client.getDatabase(options.getDatabaseName()).getCollection(options.getCollectionName());
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "genkit-mongo-session-store-poll");
              t.setDaemon(true);
              return t;
            });
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Id helpers
  // ──────────────────────────────────────────────────────────────────────────

  private String prefix(SessionStoreOptions opts) {
    String p =
        options.getSnapshotPathPrefix().apply(opts != null ? opts : SessionStoreOptions.empty());
    return (p == null || p.isBlank()) ? "global" : p;
  }

  private static String key(String prefix, String recordId) {
    return prefix + "::" + recordId;
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
   * then writes the snapshot (checkpoint shards + metadata document) and advances the session
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

      // 5. Build the snapshot metadata document (+ shard docs for a checkpoint).
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

      // 6. Write the snapshot document with optimistic concurrency.
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
   * <p>By {@code snapshotId}: loads the snapshot document and reconstructs its state from the
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
  // Low-level MongoDB + node helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** A single stored document: its JSON payload and optimistic-concurrency version. */
  private static final class Row {
    final ObjectNode doc;
    final long version;

    Row(ObjectNode doc, long version) {
      this.doc = doc;
      this.version = version;
    }
  }

  /** Reads a document by key, or {@code null} when it does not exist. */
  private Row readRow(String prefix, String id) {
    Document found = collection.find(Filters.eq("_id", key(prefix, id))).first();
    if (found == null) {
      return null;
    }
    long version = found.get("version") instanceof Number n ? n.longValue() : 1L;
    Document payload = new Document(found);
    payload.remove("_id");
    payload.remove("pk");
    payload.remove("version");
    try {
      return new Row((ObjectNode) MAPPER.readTree(payload.toJson()), version);
    } catch (Exception e) {
      throw new GenkitException("Failed to read session document " + id + ": " + e.getMessage(), e);
    }
  }

  /** Builds the persisted document by merging the payload with the id/pk/version envelope. */
  private Document toDocument(String prefix, String id, ObjectNode payload, long version) {
    Document doc = Document.parse(writeJson(payload));
    doc.put("_id", key(prefix, id));
    doc.put("pk", prefix);
    doc.put("version", version);
    return doc;
  }

  /** Idempotently upserts a document (used for shard records). */
  private void upsertRow(String prefix, String id, ObjectNode payload) {
    Document doc = toDocument(prefix, id, payload, 1L);
    collection.replaceOne(
        Filters.eq("_id", key(prefix, id)), doc, new ReplaceOptions().upsert(true));
  }

  /**
   * Writes a document with optimistic concurrency: inserts when {@code expectedVersion} is {@code
   * null}, otherwise replaces only when the stored version still matches. Returns {@code false}
   * when a concurrent writer won the race (the caller re-reads and retries the pure mutator).
   */
  private boolean writeConditional(
      String prefix, String id, ObjectNode payload, Long expectedVersion) {
    if (expectedVersion == null) {
      Document doc = toDocument(prefix, id, payload, 1L);
      try {
        collection.insertOne(doc);
        return true;
      } catch (MongoWriteException e) {
        if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
          return false; // concurrent insert won the race.
        }
        throw new GenkitException(
            "Failed to insert session document " + id + ": " + e.getMessage(), e);
      }
    }
    Document doc = toDocument(prefix, id, payload, expectedVersion + 1);
    UpdateResult result =
        collection.replaceOne(
            Filters.and(Filters.eq("_id", key(prefix, id)), Filters.eq("version", expectedVersion)),
            doc);
    return result.getMatchedCount() == 1;
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
}
