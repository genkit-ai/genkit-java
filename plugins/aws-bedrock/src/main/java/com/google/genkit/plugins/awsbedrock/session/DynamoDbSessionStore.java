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

package com.google.genkit.plugins.awsbedrock.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB-backed implementation of {@link SessionStore} and {@link SnapshotSubscriber}.
 *
 * <p>Persists session snapshots with the same sharded checkpoint + diff + pointer layout as the
 * Firestore backend (see {@code FirestoreSessionStore}), sharing the pure logic in {@link
 * SnapshotSharding}.
 *
 * <h3>Storage layout (single table)</h3>
 *
 * <p>All rows live in one table (default {@code genkit-sessions}) with a composite key: partition
 * key {@code pk} = the per-tenant prefix (default {@code "global"}), sort key {@code sk}:
 *
 * <ul>
 *   <li>{@code SNAP#<snapshotId>} — one metadata row per snapshot. {@code kind} is {@code
 *       "checkpoint"} or {@code "diff"}; carries {@code checkpointId}, {@code
 *       checkpointShardCount}, {@code segmentPath}, {@code statePatch} (RFC-6902 as a JSON string
 *       for diffs), {@code error} (JSON string), and a numeric {@code version} for optimistic
 *       concurrency.
 *   <li>{@code SHARD#<checkpointId>#<index>} — a shard of the checkpoint state JSON.
 *   <li>{@code PTR#<sessionId>} — the current leaf pointer for a session.
 * </ul>
 *
 * <h3>Concurrency</h3>
 *
 * <p>DynamoDB has no interactive read-then-write transaction, so the observable {@code
 * saveSnapshot} contract is preserved with idempotent unique-key writes plus a conditional,
 * monotonic pointer advance: shards are written first, then the snapshot row, then the pointer
 * flips — so a reader following the pointer always sees complete data. Updating an existing
 * snapshot id uses a {@code version} conditional put and re-applies the (pure) mutator on conflict.
 *
 * @param <S> the type of custom session state
 */
public final class DynamoDbSessionStore<S> implements SessionStore<S>, SnapshotSubscriber {

  private static final Logger logger = LoggerFactory.getLogger(DynamoDbSessionStore.class);
  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  static final String KIND_CHECKPOINT = "checkpoint";
  static final String KIND_DIFF = "diff";

  private static final int MAX_ATTEMPTS = 5;

  private final DynamoDbClient db;
  private final DynamoDbSessionStoreOptions options;
  private final ScheduledExecutorService scheduler;

  /**
   * Creates a store with default options.
   *
   * @param db the DynamoDB client
   */
  public DynamoDbSessionStore(DynamoDbClient db) {
    this(db, DynamoDbSessionStoreOptions.defaults());
  }

  /**
   * Creates a store.
   *
   * @param db the DynamoDB client
   * @param options the store options
   */
  public DynamoDbSessionStore(DynamoDbClient db, DynamoDbSessionStoreOptions options) {
    if (db == null) {
      throw new IllegalArgumentException("DynamoDbClient must be non-null");
    }
    if (options == null) {
      throw new IllegalArgumentException("options must be non-null");
    }
    this.db = db;
    this.options = options;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "genkit-dynamodb-session-store-poll");
              t.setDaemon(true);
              return t;
            });
    if (options.isCreateTableIfNotExists()) {
      ensureTable();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Key helpers
  // ──────────────────────────────────────────────────────────────────────────

  private String prefix(SessionStoreOptions opts) {
    String p =
        options.getSnapshotPathPrefix().apply(opts != null ? opts : SessionStoreOptions.empty());
    return (p == null || p.isBlank()) ? "global" : p;
  }

  private static String snapSk(String snapshotId) {
    return "SNAP#" + snapshotId;
  }

  private static String shardSk(String checkpointId, int index) {
    return "SHARD#" + checkpointId + "#" + index;
  }

  private static String ptrSk(String sessionId) {
    return "PTR#" + sessionId;
  }

  private static Map<String, AttributeValue> key(String prefix, String sk) {
    Map<String, AttributeValue> k = new HashMap<>();
    k.put("pk", s(prefix));
    k.put("sk", s(sk));
    return k;
  }

  private static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private static AttributeValue n(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Table management
  // ──────────────────────────────────────────────────────────────────────────

  private void ensureTable() {
    String table = options.getTableName();
    try {
      db.describeTable(DescribeTableRequest.builder().tableName(table).build());
    } catch (ResourceNotFoundException e) {
      db.createTable(
          CreateTableRequest.builder()
              .tableName(table)
              .keySchema(
                  KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                  KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
              .attributeDefinitions(
                  AttributeDefinition.builder()
                      .attributeName("pk")
                      .attributeType(ScalarAttributeType.S)
                      .build(),
                  AttributeDefinition.builder()
                      .attributeName("sk")
                      .attributeType(ScalarAttributeType.S)
                      .build())
              .billingMode(BillingMode.PAY_PER_REQUEST)
              .build());
      db.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(table).build());
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotWriter
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Implements the same identity/sessionId/status defaulting contract as the reference stores,
   * then writes the snapshot (checkpoint shards + metadata row) and advances the session pointer.
   */
  @Override
  public String saveSnapshot(
      String snapshotId, SnapshotMutator<S> mutator, SessionStoreOptions storeOpts) {
    String prefix = prefix(storeOpts);
    String table = options.getTableName();

    for (int attempt = 1; ; attempt++) {
      // 1. Read existing snapshot + reconstruct state.
      SessionSnapshot<S> existing = null;
      String existingSessionId = null;
      Long existingVersion = null;
      if (snapshotId != null) {
        SnapshotSharding.validateId(snapshotId);
        Map<String, AttributeValue> item = getItem(key(prefix, snapSk(snapshotId)));
        if (item != null) {
          existing = readSnapshot(prefix, item);
          existingSessionId = existing.getSessionId();
          existingVersion = getLong(item, "version");
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
        Map<String, AttributeValue> parentItem = getItem(key(prefix, snapSk(parentId)));
        if (parentItem != null) {
          parent = loadParentInfo(prefix, parentItem);
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

      // 5. Build the snapshot metadata row (+ shard rows for a checkpoint).
      Map<String, AttributeValue> row = baseRow(prefix, result);
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
          Map<String, AttributeValue> shardRow = new HashMap<>();
          shardRow.put("pk", s(prefix));
          shardRow.put("sk", s(shardSk(checkpointId, i)));
          shardRow.put("checkpointId", s(checkpointId));
          shardRow.put("index", n(i));
          shardRow.put("data", s(shards.get(i)));
          db.putItem(PutItemRequest.builder().tableName(table).item(shardRow).build());
        }
        row.put("kind", s(KIND_CHECKPOINT));
      } else {
        ParentInfo p = parent;
        if (p == null) {
          throw new GenkitException("internal: diff path without parent");
        }
        checkpointId = p.checkpointId;
        checkpointShardCount = p.checkpointShardCount;
        segmentPath = new ArrayList<>(p.segmentPath);
        segmentPath.add(finalId);
        row.put("kind", s(KIND_DIFF));
        row.put("statePatch", s(writeJson(statePatch)));
      }

      row.put("checkpointId", s(checkpointId));
      row.put("checkpointShardCount", n(checkpointShardCount));
      row.put("segmentPath", stringList(segmentPath));
      long newVersion = (existingVersion == null ? 0 : existingVersion) + 1;
      row.put("version", n(newVersion));

      // 6. Write the snapshot row with optimistic concurrency.
      PutItemRequest.Builder put = PutItemRequest.builder().tableName(table).item(row);
      if (existingVersion == null) {
        put.conditionExpression("attribute_not_exists(sk)");
      } else {
        put.conditionExpression("version = :ev")
            .expressionAttributeValues(Map.of(":ev", n(existingVersion)));
      }
      try {
        db.putItem(put.build());
      } catch (ConditionalCheckFailedException e) {
        if (attempt < MAX_ATTEMPTS) {
          continue; // concurrent writer won the race; re-read and retry the pure mutator.
        }
        throw new GenkitException("Failed to save snapshot after " + MAX_ATTEMPTS + " attempts", e);
      }

      // 7. Advance the session pointer (never backward). A lost race here is benign.
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
    String table = options.getTableName();

    Map<String, AttributeValue> values = new HashMap<>();
    values.put(":sid", s(snapshotId));
    values.put(":cp", s(checkpointId));
    values.put(":sc", n(checkpointShardCount));
    values.put(":sp", stringList(segmentPath));

    StringBuilder setExpr =
        new StringBuilder(
            "SET currentSnapshotId = :sid, checkpointId = :cp, checkpointShardCount = :sc,"
                + " segmentPath = :sp");
    if (createdAt != null) {
      setExpr.append(", currentCreatedAt = :cc");
      values.put(":cc", s(createdAt));
    }
    if (updatedAt != null) {
      setExpr.append(", updatedAt = :ua");
      values.put(":ua", s(updatedAt));
    }

    UpdateItemRequest.Builder update =
        UpdateItemRequest.builder()
            .tableName(table)
            .key(key(prefix, ptrSk(sessionId)))
            .updateExpression(setExpr.toString())
            .expressionAttributeValues(values);

    // Only guard against moving backward when we have a comparable createdAt. When createdAt is
    // null we always advance (matching the reference stores, which treat null createdAt as newer).
    if (createdAt != null) {
      update.conditionExpression(
          "attribute_not_exists(currentSnapshotId) OR attribute_not_exists(currentCreatedAt) OR"
              + " currentCreatedAt <= :cc OR currentSnapshotId = :sid");
    }

    try {
      db.updateItem(update.build());
    } catch (ConditionalCheckFailedException e) {
      // Stored pointer is already newer; leave it in place.
      logger.debug("Pointer for session {} not advanced (stored leaf is newer)", sessionId);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotReader
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>By {@code snapshotId}: loads the snapshot row and reconstructs its state from the checkpoint
   * shards + ordered {@code segmentPath} diffs. By {@code sessionId}: reads the pointer, then loads
   * and reconstructs the pointed snapshot.
   */
  @Override
  public SessionSnapshot<S> getSnapshot(GetSnapshotOptions opts) {
    if (opts == null) {
      return null;
    }
    SessionStoreOptions storeOpts = SessionStoreOptions.empty();
    String prefix = prefix(storeOpts);

    if (opts.getSnapshotId() != null) {
      SnapshotSharding.validateId(opts.getSnapshotId());
      Map<String, AttributeValue> item = getItem(key(prefix, snapSk(opts.getSnapshotId())));
      return item == null ? null : readSnapshot(prefix, item);
    }
    if (opts.getSessionId() != null) {
      SnapshotSharding.validateId(opts.getSessionId());
      Map<String, AttributeValue> pointer = getItem(key(prefix, ptrSk(opts.getSessionId())));
      if (pointer == null) {
        return null;
      }
      String leafId = getString(pointer, "currentSnapshotId");
      if (leafId == null) {
        return null;
      }
      Map<String, AttributeValue> item = getItem(key(prefix, snapSk(leafId)));
      return item == null ? null : readSnapshot(prefix, item);
    }
    return null;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotSubscriber (polling)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>DynamoDB has no built-in per-item change notification usable here, so the subscription polls
   * {@link #getSnapshot} on a shared daemon scheduler and fires the callback whenever the
   * serialized snapshot content changes. The callback also fires immediately if the snapshot
   * already exists.
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
  // Row (de)serialization
  // ──────────────────────────────────────────────────────────────────────────

  /** Builds the non-state base row for a snapshot (pk/sk + metadata). */
  private Map<String, AttributeValue> baseRow(String prefix, SessionSnapshot<S> snap) {
    Map<String, AttributeValue> data = new HashMap<>();
    data.put("pk", s(prefix));
    data.put("sk", s(snapSk(snap.getSnapshotId())));
    data.put("snapshotId", s(snap.getSnapshotId()));
    data.put("sessionId", s(snap.getSessionId()));
    if (snap.getParentId() != null) {
      data.put("parentId", s(snap.getParentId()));
    }
    if (snap.getCreatedAt() != null) {
      data.put("createdAt", s(snap.getCreatedAt()));
    }
    if (snap.getUpdatedAt() != null) {
      data.put("updatedAt", s(snap.getUpdatedAt()));
    }
    if (snap.getHeartbeatAt() != null) {
      data.put("heartbeatAt", s(snap.getHeartbeatAt()));
    }
    if (snap.getStatus() != null) {
      data.put("status", s(snap.getStatus().getValue()));
    }
    if (snap.getFinishReason() != null) {
      data.put("finishReason", s(snap.getFinishReason().getValue()));
    }
    if (snap.getError() != null) {
      data.put("error", s(writeJson(snap.getError())));
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
  private ParentInfo loadParentInfo(String prefix, Map<String, AttributeValue> item) {
    ParentInfo info = new ParentInfo();
    info.checkpointId = getString(item, "checkpointId");
    Long shardCount = getLong(item, "checkpointShardCount");
    info.checkpointShardCount = shardCount != null ? shardCount.intValue() : 0;
    info.segmentPath = getStringList(item, "segmentPath");
    info.state =
        reconstructFullState(
            prefix, info.checkpointId, info.checkpointShardCount, info.segmentPath);
    return info;
  }

  /** Reads and fully reconstructs a snapshot from its metadata row. */
  private SessionSnapshot<S> readSnapshot(String prefix, Map<String, AttributeValue> item) {
    String checkpointId = getString(item, "checkpointId");
    Long shardCount = getLong(item, "checkpointShardCount");
    int checkpointShardCount = shardCount != null ? shardCount.intValue() : 0;
    List<String> segmentPath = getStringList(item, "segmentPath");
    JsonNode state = reconstructFullState(prefix, checkpointId, checkpointShardCount, segmentPath);
    return rowToSnapshot(item, state);
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
      Map<String, AttributeValue> shard = getItem(key(prefix, shardSk(checkpointId, i)));
      shardContents.add(shard != null ? getString(shard, "data") : "");
    }
    String checkpointJson = SnapshotSharding.reassembleShards(shardContents);

    List<String> diffs = new ArrayList<>();
    for (String diffId : segmentPath) {
      Map<String, AttributeValue> diffItem = getItem(key(prefix, snapSk(diffId)));
      if (diffItem != null) {
        String patch = getString(diffItem, "statePatch");
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

  /** Builds a {@link SessionSnapshot} from a metadata row and reconstructed state. */
  @SuppressWarnings("unchecked")
  private SessionSnapshot<S> rowToSnapshot(Map<String, AttributeValue> item, JsonNode state) {
    SessionSnapshot.Builder<S> builder = SessionSnapshot.<S>builder();
    builder.snapshotId(getString(item, "snapshotId"));
    builder.sessionId(getString(item, "sessionId"));
    builder.parentId(getString(item, "parentId"));
    builder.createdAt(getString(item, "createdAt"));
    builder.updatedAt(getString(item, "updatedAt"));
    builder.heartbeatAt(getString(item, "heartbeatAt"));
    String status = getString(item, "status");
    if (status != null) {
      builder.status(SnapshotStatus.fromValueOrCompleted(status));
    }
    String finishReason = getString(item, "finishReason");
    if (finishReason != null) {
      builder.finishReason(AgentFinishReason.fromValue(finishReason));
    }
    String errorJson = getString(item, "error");
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
  // Low-level DynamoDB + attribute helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Consistent-read GetItem; returns {@code null} when the item does not exist. */
  private Map<String, AttributeValue> getItem(Map<String, AttributeValue> key) {
    Map<String, AttributeValue> item =
        db.getItem(
                GetItemRequest.builder()
                    .tableName(options.getTableName())
                    .key(key)
                    .consistentRead(true)
                    .build())
            .item();
    return (item == null || item.isEmpty()) ? null : item;
  }

  private static AttributeValue stringList(List<String> values) {
    List<AttributeValue> list = new ArrayList<>();
    for (String v : values) {
      list.add(s(v));
    }
    return AttributeValue.builder().l(list).build();
  }

  private static String getString(Map<String, AttributeValue> item, String name) {
    AttributeValue av = item.get(name);
    return (av == null || av.s() == null) ? null : av.s();
  }

  private static Long getLong(Map<String, AttributeValue> item, String name) {
    AttributeValue av = item.get(name);
    if (av == null || av.n() == null) {
      return null;
    }
    return Long.parseLong(av.n());
  }

  private static List<String> getStringList(Map<String, AttributeValue> item, String name) {
    AttributeValue av = item.get(name);
    List<String> out = new ArrayList<>();
    if (av != null && av.hasL()) {
      for (AttributeValue e : av.l()) {
        if (e.s() != null) {
          out.add(e.s());
        }
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
