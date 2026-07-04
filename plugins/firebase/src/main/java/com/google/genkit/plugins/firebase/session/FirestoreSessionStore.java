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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.ListenerRegistration;
import com.google.cloud.firestore.Transaction;
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
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Firestore-backed implementation of {@link SessionStore} and {@link SnapshotSubscriber}.
 *
 * <p>Persists session snapshots with a sharded checkpoint + diff + pointer layout, mirroring the
 * upstream Go ({@code go/plugins/firebase/exp/firestore_session_store.go}) and JS ({@code
 * js/plugins/google-cloud/src/session-store/firestore.ts}) implementations.
 *
 * <h3>Storage layout</h3>
 *
 * <p>For a configured {@code collection} and per-tenant {@code prefix} (default {@code "global"}):
 *
 * <ul>
 *   <li>{@code <collection>/<prefix>/snapshots/<snapshotId>} — one metadata document per snapshot.
 *       {@code kind} is {@code "checkpoint"} (full state stored out-of-band in shards) or {@code
 *       "diff"} (RFC-6902 {@code statePatch} from its parent). Each document carries {@code
 *       checkpointId} (nearest checkpoint ancestor), {@code checkpointShardCount}, {@code
 *       segmentPath} (ordered diff ids from the checkpoint exclusive → this doc inclusive) and the
 *       snapshot metadata fields. {@code statePatch} and {@code error} are stored as opaque JSON
 *       strings because Firestore disallows nested arrays.
 *   <li>{@code <collection>-shards/<prefix>/shards/<checkpointId>_<index>} — checkpoint state JSON
 *       (UTF-8) split into {@code shardSize}-byte chunks.
 *   <li>{@code <collection>-pointers/<prefix>/pointers/<sessionId>} — the current leaf pointer for
 *       a session ({@code currentSnapshotId}, {@code checkpointId}, {@code checkpointShardCount},
 *       {@code segmentPath}, {@code currentCreatedAt}, {@code updatedAt}). Carries no state.
 * </ul>
 *
 * <h3>Concurrency</h3>
 *
 * <p>{@link #saveSnapshot} runs inside a Firestore transaction (read existing → apply mutator →
 * write). The mutator is treated as pure and may be re-invoked on transaction retry.
 *
 * @param <S> the type of custom session state
 */
public final class FirestoreSessionStore<S> implements SessionStore<S>, SnapshotSubscriber {

  private static final Logger logger = LoggerFactory.getLogger(FirestoreSessionStore.class);
  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  static final String KIND_CHECKPOINT = "checkpoint";
  static final String KIND_DIFF = "diff";

  private final Firestore db;
  private final FirestoreSessionStoreOptions options;

  /**
   * Creates a store with default options.
   *
   * @param db the Firestore client
   */
  public FirestoreSessionStore(Firestore db) {
    this(db, FirestoreSessionStoreOptions.defaults());
  }

  /**
   * Creates a store.
   *
   * @param db the Firestore client
   * @param options the store options
   */
  public FirestoreSessionStore(Firestore db, FirestoreSessionStoreOptions options) {
    if (db == null) {
      throw new IllegalArgumentException("Firestore client must be non-null");
    }
    if (options == null) {
      throw new IllegalArgumentException("options must be non-null");
    }
    this.db = db;
    this.options = options;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Firestore path helpers
  // ──────────────────────────────────────────────────────────────────────────

  private String prefix(SessionStoreOptions opts) {
    String p =
        options.getSnapshotPathPrefix().apply(opts != null ? opts : SessionStoreOptions.empty());
    return (p == null || p.isBlank()) ? "global" : p;
  }

  private DocumentReference snapshotDoc(String prefix, String snapshotId) {
    return db.collection(options.getCollection())
        .document(prefix)
        .collection("snapshots")
        .document(snapshotId);
  }

  private DocumentReference shardDoc(String prefix, String checkpointId, int index) {
    return db.collection(options.getCollection() + "-shards")
        .document(prefix)
        .collection("shards")
        .document(checkpointId + "_" + index);
  }

  private DocumentReference pointerDoc(String prefix, String sessionId) {
    return db.collection(options.getCollection() + "-pointers")
        .document(prefix)
        .collection("pointers")
        .document(sessionId);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotWriter
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Runs a Firestore transaction implementing the same identity/sessionId/status defaulting
   * contract as the in-memory reference store:
   *
   * <ol>
   *   <li>Read the existing snapshot doc; reconstruct its full state when present.
   *   <li>Apply the (pure) mutator. If it returns {@code null}, no write occurs and {@code null} is
   *       returned.
   *   <li>Mint a UUID id when none supplied; preserve {@code sessionId} from the existing row;
   *       reject empty {@code sessionId} with {@code INVALID_ARGUMENT}; default {@code null} status
   *       to {@code COMPLETED}.
   *   <li>Decide checkpoint vs diff and write the snapshot doc (+ shards for a checkpoint).
   *   <li>Advance the session pointer to the new leaf (never backward).
   * </ol>
   */
  @Override
  public String saveSnapshot(
      String snapshotId, SnapshotMutator<S> mutator, SessionStoreOptions options) {
    String prefix = prefix(options);
    try {
      return db.runTransaction(
              (Transaction tx) -> {
                // 1. Read existing snapshot + reconstruct state.
                SessionSnapshot<S> existing = null;
                String existingSessionId = null;
                if (snapshotId != null) {
                  SnapshotSharding.validateId(snapshotId);
                  DocumentSnapshot doc = tx.get(snapshotDoc(prefix, snapshotId)).get();
                  if (doc.exists()) {
                    existing = readSnapshot(tx, prefix, doc);
                    existingSessionId = existing.getSessionId();
                  }
                }

                // 2. Apply mutator (pure).
                SessionSnapshot<S> result = mutator.apply(existing);
                if (result == null) {
                  return null;
                }

                // 3. Identity / sessionId / status defaulting (mirror InMemorySessionStore).
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
                  DocumentSnapshot parentDoc = tx.get(snapshotDoc(prefix, parentId)).get();
                  if (parentDoc.exists()) {
                    parent = loadParentInfo(tx, prefix, parentDoc);
                  }
                }

                boolean parentExists = parent != null;
                int depthFromCheckpoint = 0;
                JsonNode statePatch = null;
                int diffSizeBytes = 0;
                if (parent != null) {
                  depthFromCheckpoint = parent.segmentPath.size() + 1;
                  statePatch = JsonPatch.diff(parent.state, newState);
                  diffSizeBytes =
                      MAPPER.writeValueAsString(statePatch).getBytes(StandardCharsets.UTF_8).length;
                }

                boolean checkpoint =
                    SnapshotSharding.shouldCheckpoint(
                        parentExists,
                        depthFromCheckpoint,
                        FirestoreSessionStore.this.options.getCheckpointInterval(),
                        diffSizeBytes,
                        FirestoreSessionStore.this.options.getShardSize());

                // Read the session pointer now — Firestore transactions require all reads to
                // precede all writes, so this must happen before the tx.set calls below.
                DocumentSnapshot currentPointer =
                    tx.get(pointerDoc(prefix, result.getSessionId())).get();

                // 5. Write snapshot doc (+shards if checkpoint).
                Map<String, Object> docData = baseDocData(result);
                String checkpointId;
                int checkpointShardCount;
                List<String> segmentPath;

                if (checkpoint) {
                  checkpointId = finalId;
                  String stateJson = MAPPER.writeValueAsString(newState);
                  List<String> shards =
                      SnapshotSharding.shardString(
                          stateJson, FirestoreSessionStore.this.options.getShardSize());
                  checkpointShardCount = shards.size();
                  segmentPath = new ArrayList<>();
                  for (int i = 0; i < shards.size(); i++) {
                    Map<String, Object> shardData = new HashMap<>();
                    shardData.put("checkpointId", checkpointId);
                    shardData.put("index", i);
                    shardData.put("data", shards.get(i));
                    tx.set(shardDoc(prefix, checkpointId, i), shardData);
                  }
                  docData.put("kind", KIND_CHECKPOINT);
                } else {
                  // shouldCheckpoint() guarantees a non-null parent here (it returns true whenever
                  // there is no usable parent), but assert defensively for the analyzer.
                  ParentInfo p = parent;
                  if (p == null) {
                    throw new GenkitException("internal: diff path without parent");
                  }
                  checkpointId = p.checkpointId;
                  checkpointShardCount = p.checkpointShardCount;
                  segmentPath = new ArrayList<>(p.segmentPath);
                  segmentPath.add(finalId);
                  docData.put("kind", KIND_DIFF);
                  docData.put("statePatch", MAPPER.writeValueAsString(statePatch));
                }

                docData.put("checkpointId", checkpointId);
                docData.put("checkpointShardCount", checkpointShardCount);
                docData.put("segmentPath", segmentPath);
                tx.set(snapshotDoc(prefix, finalId), docData);

                // 6. Advance pointer (never backward).
                advancePointer(
                    tx,
                    currentPointer,
                    prefix,
                    result.getSessionId(),
                    finalId,
                    result.getCreatedAt(),
                    result.getUpdatedAt(),
                    checkpointId,
                    checkpointShardCount,
                    segmentPath);

                return finalId;
              })
          .get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof GenkitException) {
        throw (GenkitException) cause;
      }
      throw new GenkitException("Failed to save snapshot: " + cause.getMessage(), cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GenkitException("Interrupted while saving snapshot", e);
    }
  }

  /**
   * Advances the session pointer to the new leaf unless the stored pointer is already newer. The
   * pointer document must have been read earlier in the transaction (Firestore requires all reads
   * before all writes) and is passed in as {@code current}.
   */
  private void advancePointer(
      Transaction tx,
      DocumentSnapshot current,
      String prefix,
      String sessionId,
      String snapshotId,
      String createdAt,
      String updatedAt,
      String checkpointId,
      int checkpointShardCount,
      List<String> segmentPath) {
    DocumentReference ref = pointerDoc(prefix, sessionId);
    if (current.exists()) {
      String currentLeaf = current.getString("currentSnapshotId");
      String currentCreatedAt = current.getString("currentCreatedAt");
      // Don't move backward: only refresh when rewriting the same leaf, or when the new snapshot is
      // at least as new as the stored leaf (by createdAt string, RFC-3339 lexically sortable).
      boolean sameLeaf = snapshotId.equals(currentLeaf);
      boolean newer =
          createdAt == null
              || currentCreatedAt == null
              || createdAt.compareTo(currentCreatedAt) >= 0;
      if (!sameLeaf && !newer) {
        return;
      }
    }
    Map<String, Object> pointerData = new HashMap<>();
    pointerData.put("currentSnapshotId", snapshotId);
    pointerData.put("checkpointId", checkpointId);
    pointerData.put("checkpointShardCount", checkpointShardCount);
    pointerData.put("segmentPath", segmentPath);
    if (createdAt != null) {
      pointerData.put("currentCreatedAt", createdAt);
    }
    if (updatedAt != null) {
      pointerData.put("updatedAt", updatedAt);
    }
    tx.set(ref, pointerData);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotReader
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>By {@code snapshotId}: loads the snapshot doc and reconstructs its full state from the
   * nearest checkpoint's shards plus the ordered {@code segmentPath} diffs. By {@code sessionId}:
   * reads the session pointer, then loads and reconstructs the pointed snapshot.
   */
  @Override
  public SessionSnapshot<S> getSnapshot(GetSnapshotOptions opts) {
    if (opts == null) {
      return null;
    }
    SessionStoreOptions storeOpts = SessionStoreOptions.empty();
    String prefix = prefix(storeOpts);
    try {
      if (opts.getSnapshotId() != null) {
        SnapshotSharding.validateId(opts.getSnapshotId());
        return db.runTransaction(
                (Transaction tx) -> {
                  DocumentSnapshot doc = tx.get(snapshotDoc(prefix, opts.getSnapshotId())).get();
                  if (!doc.exists()) {
                    return null;
                  }
                  return readSnapshot(tx, prefix, doc);
                })
            .get();
      }
      if (opts.getSessionId() != null) {
        SnapshotSharding.validateId(opts.getSessionId());
        return db.runTransaction(
                (Transaction tx) -> {
                  DocumentSnapshot pointer = tx.get(pointerDoc(prefix, opts.getSessionId())).get();
                  if (!pointer.exists()) {
                    return null;
                  }
                  String leafId = pointer.getString("currentSnapshotId");
                  if (leafId == null) {
                    return null;
                  }
                  DocumentSnapshot doc = tx.get(snapshotDoc(prefix, leafId)).get();
                  if (!doc.exists()) {
                    return null;
                  }
                  return readSnapshot(tx, prefix, doc);
                })
            .get();
      }
      return null;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof GenkitException) {
        throw (GenkitException) cause;
      }
      throw new GenkitException("Failed to get snapshot: " + cause.getMessage(), cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GenkitException("Interrupted while getting snapshot", e);
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // SnapshotSubscriber
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Registers a Firestore realtime listener on the snapshot document. On every event (including
   * the initial snapshot when the document exists) the snapshot is reconstructed off-band (via a
   * read transaction) and passed to {@code cb}. The returned {@link AutoCloseable} removes the
   * listener.
   */
  @Override
  public AutoCloseable onSnapshotStateChange(
      String snapshotId, Consumer<SessionSnapshot<?>> cb, SessionStoreOptions options) {
    SnapshotSharding.validateId(snapshotId);
    String prefix = prefix(options);
    ListenerRegistration registration =
        snapshotDoc(prefix, snapshotId)
            .addSnapshotListener(
                (doc, error) -> {
                  if (error != null) {
                    logger.warn(
                        "Snapshot listener error for {}: {}", snapshotId, error.getMessage());
                    return;
                  }
                  if (doc == null || !doc.exists()) {
                    return;
                  }
                  try {
                    SessionSnapshot<S> snap =
                        getSnapshot(GetSnapshotOptions.builder().snapshotId(snapshotId).build());
                    if (snap != null) {
                      cb.accept(snap);
                    }
                  } catch (Exception e) {
                    logger.warn(
                        "Failed to reconstruct snapshot {} in listener: {}",
                        snapshotId,
                        e.getMessage());
                  }
                });
    return registration::remove;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Snapshot (de)serialization to/from Firestore documents
  // ──────────────────────────────────────────────────────────────────────────

  /** Builds the non-state base document data for a snapshot. */
  private Map<String, Object> baseDocData(SessionSnapshot<S> snap) throws Exception {
    Map<String, Object> data = new HashMap<>();
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
      // Stored as opaque JSON string (Firestore nested-array restriction safety).
      data.put("error", MAPPER.writeValueAsString(snap.getError()));
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
  @SuppressWarnings("unchecked")
  private ParentInfo loadParentInfo(Transaction tx, String prefix, DocumentSnapshot doc)
      throws Exception {
    ParentInfo info = new ParentInfo();
    info.checkpointId = doc.getString("checkpointId");
    Long shardCount = doc.getLong("checkpointShardCount");
    info.checkpointShardCount = shardCount != null ? shardCount.intValue() : 0;
    Object segObj = doc.get("segmentPath");
    info.segmentPath =
        segObj instanceof List ? new ArrayList<>((List<String>) segObj) : new ArrayList<>();
    info.state =
        reconstructFullState(
            tx, prefix, info.checkpointId, info.checkpointShardCount, info.segmentPath);
    return info;
  }

  /** Reads and fully reconstructs a snapshot from a Firestore document. */
  @SuppressWarnings("unchecked")
  private SessionSnapshot<S> readSnapshot(Transaction tx, String prefix, DocumentSnapshot doc)
      throws Exception {
    String checkpointId = doc.getString("checkpointId");
    Long shardCount = doc.getLong("checkpointShardCount");
    int checkpointShardCount = shardCount != null ? shardCount.intValue() : 0;
    Object segObj = doc.get("segmentPath");
    List<String> segmentPath =
        segObj instanceof List ? new ArrayList<>((List<String>) segObj) : new ArrayList<>();

    JsonNode state =
        reconstructFullState(tx, prefix, checkpointId, checkpointShardCount, segmentPath);
    return docToSnapshot(doc, state);
  }

  /**
   * Reconstructs full state: loads the checkpoint shards (concatenate, parse) then applies the
   * {@code segmentPath} diffs in order.
   */
  private JsonNode reconstructFullState(
      Transaction tx,
      String prefix,
      String checkpointId,
      int checkpointShardCount,
      List<String> segmentPath)
      throws Exception {
    if (checkpointId == null) {
      return NullNode.getInstance();
    }
    // Load shards.
    List<String> shardContents = new ArrayList<>();
    for (int i = 0; i < checkpointShardCount; i++) {
      DocumentSnapshot shard = tx.get(shardDoc(prefix, checkpointId, i)).get();
      shardContents.add(shard.exists() ? (String) shard.get("data") : "");
    }
    String checkpointJson = SnapshotSharding.reassembleShards(shardContents);

    // Load diffs along the segment path (each is an opaque JSON-string patch).
    List<String> diffs = new ArrayList<>();
    for (String diffId : segmentPath) {
      DocumentSnapshot diffDoc = tx.get(snapshotDoc(prefix, diffId)).get();
      if (diffDoc.exists()) {
        String patch = diffDoc.getString("statePatch");
        if (patch != null) {
          diffs.add(patch);
        }
      }
    }
    return SnapshotSharding.reconstructState(checkpointJson, diffs);
  }

  /** Builds a {@link SessionSnapshot} from the metadata document and reconstructed state. */
  @SuppressWarnings("unchecked")
  private SessionSnapshot<S> docToSnapshot(DocumentSnapshot doc, JsonNode state) throws Exception {
    SessionSnapshot.Builder<S> builder = SessionSnapshot.<S>builder();
    builder.snapshotId(doc.getString("snapshotId"));
    builder.sessionId(doc.getString("sessionId"));
    builder.parentId(doc.getString("parentId"));
    builder.createdAt(doc.getString("createdAt"));
    builder.updatedAt(doc.getString("updatedAt"));
    builder.heartbeatAt(doc.getString("heartbeatAt"));
    String status = doc.getString("status");
    if (status != null) {
      builder.status(SnapshotStatus.fromValueOrCompleted(status));
    }
    String finishReason = doc.getString("finishReason");
    if (finishReason != null) {
      builder.finishReason(com.google.genkit.ai.agent.AgentFinishReason.fromValue(finishReason));
    }
    String errorJson = doc.getString("error");
    if (errorJson != null) {
      builder.error(MAPPER.readValue(errorJson, RuntimeError.class));
    }
    if (state != null && !state.isNull()) {
      builder.state((SessionState<S>) MAPPER.treeToValue(state, SessionState.class));
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
}
