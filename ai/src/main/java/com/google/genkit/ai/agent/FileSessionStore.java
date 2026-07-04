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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.agent.internal.LeafSelection;
import com.google.genkit.ai.agent.internal.PointerDoc;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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
import java.util.stream.Stream;

/**
 * Disk-backed implementation of {@link SessionStore} and {@link SnapshotSubscriber}.
 *
 * <h3>On-disk layout</h3>
 *
 * <pre>
 * &lt;dir&gt;/&lt;prefix&gt;/&lt;snapshotId&gt;.json              one snapshot per file
 * &lt;dir&gt;/&lt;prefix&gt;/.pointers/&lt;sessionId&gt;.json     pointer: { currentSnapshotId, currentCreatedAt, updatedAt }
 * </pre>
 *
 * <p>The default prefix is {@code "global"}.
 *
 * <h3>Atomic writes</h3>
 *
 * <p>Each write goes to a {@code .tmp} file first, then is renamed to the target path using {@link
 * StandardCopyOption#ATOMIC_MOVE} (falling back to {@link StandardCopyOption#REPLACE_EXISTING} if
 * the filesystem does not support atomic moves). This ensures no partial JSON files are visible.
 *
 * <h3>Subscriber / polling</h3>
 *
 * <p>Subscriptions use polling (a shared daemon {@link ScheduledExecutorService}) rather than
 * {@code WatchService}. This is simpler and more reliable across platforms (particularly macOS
 * where {@code WatchService} uses polling internally anyway). The poll interval defaults to 2000 ms
 * and can be overridden via the builder for fast-iteration tests.
 *
 * <p>De-duplication is done by comparing the serialised content of the snapshot file on each poll
 * tick; the callback fires only when the content has changed.
 *
 * @param <S> the type of custom session state
 */
public final class FileSessionStore<S> implements SessionStore<S>, SnapshotSubscriber {

  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  /** Default prefix sub-directory used when none is configured. */
  static final String DEFAULT_PREFIX = "global";

  /** Default polling interval in milliseconds. */
  static final long DEFAULT_POLL_INTERVAL_MS = 2000L;

  private final Path baseDir;
  private final String prefix;
  private final int maxPersistedChainLength; // 0 = unlimited
  private final boolean rejectBranchingSessions;
  private final long snapshotWatchPollIntervalMs;

  /**
   * Shared daemon scheduler used for all poll-based subscriptions. One instance per store; shut
   * down when the store itself is closed (or left to die as a daemon thread on JVM exit).
   */
  private final ScheduledExecutorService scheduler;

  /** Guard for saveSnapshot / getSnapshot operations. */
  private final Object lock = new Object();

  // ── constructors / builder ────────────────────────────────────────────────

  /**
   * Creates a new {@code FileSessionStore} with default options (prefix = {@code "global"}, no
   * chain pruning, polling every 2000 ms).
   *
   * @param dir the root directory for persisted snapshots; created if absent
   */
  public FileSessionStore(String dir) {
    this(dir, DEFAULT_PREFIX, 0, false, DEFAULT_POLL_INTERVAL_MS);
  }

  private FileSessionStore(
      String dir,
      String prefix,
      int maxPersistedChainLength,
      boolean rejectBranchingSessions,
      long snapshotWatchPollIntervalMs) {
    this.baseDir = Paths.get(dir);
    this.prefix = prefix != null ? prefix : DEFAULT_PREFIX;
    this.maxPersistedChainLength = maxPersistedChainLength;
    this.rejectBranchingSessions = rejectBranchingSessions;
    this.snapshotWatchPollIntervalMs = snapshotWatchPollIntervalMs;
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "genkit-file-session-store-poll");
              t.setDaemon(true);
              return t;
            });

    // Ensure base directory exists
    try {
      Files.createDirectories(this.baseDir);
    } catch (IOException e) {
      throw new GenkitException("Failed to create session store directory: " + dir, e);
    }
  }

  /**
   * Creates a builder for {@code FileSessionStore}.
   *
   * @param <S> the type of custom session state
   * @param dir the root directory for persisted snapshots
   * @return a new builder
   */
  public static <S> Builder<S> builder(String dir) {
    return new Builder<>(dir);
  }

  // ── SnapshotReader ────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>When {@link GetSnapshotOptions#getSnapshotId()} is set, reads {@code <prefix>/<id>.json} and
   * deserialises it (or returns {@code null} if absent). When {@link
   * GetSnapshotOptions#getSessionId()} is set, tries the pointer first; falls back to scanning all
   * {@code *.json} files in the prefix dir (skipping {@code .pointers/}), runs {@link
   * LeafSelection}, rewrites the pointer, and returns the leaf.
   */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public SessionSnapshot<S> getSnapshot(GetSnapshotOptions opts) {
    if (opts == null) {
      return null;
    }

    if (opts.getSnapshotId() != null) {
      checkPathSafety(opts.getSnapshotId());
    }

    synchronized (lock) {
      Path prefixDir = prefixDir();

      if (opts.getSnapshotId() != null) {
        return readSnapshotFile(prefixDir.resolve(opts.getSnapshotId() + ".json"));
      }

      if (opts.getSessionId() != null) {
        String sessionId = opts.getSessionId();

        // 1. Try pointer
        Path pointerFile = pointerFile(prefixDir, sessionId);
        if (Files.exists(pointerFile)) {
          PointerDoc pointer = readPointerFile(pointerFile);
          if (pointer != null && pointer.getCurrentSnapshotId() != null) {
            Path snapshotFile = prefixDir.resolve(pointer.getCurrentSnapshotId() + ".json");
            if (Files.exists(snapshotFile)) {
              SessionSnapshot<S> snap = readSnapshotFile(snapshotFile);
              if (snap != null && sessionId.equals(effectiveSessionId(snap))) {
                return snap;
              }
            }
          }
        }

        // 2. Scan fallback
        return scanAndSelectLeaf(prefixDir, sessionId, pointerFile);
      }

      return null;
    }
  }

  // ── SnapshotWriter ────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Implements the full saveSnapshot contract (mirrors {@code InMemorySessionStore}), but
   * persists to disk:
   *
   * <ol>
   *   <li>Validate path safety for {@code snapshotId} (if non-null).
   *   <li>Under lock: read existing snapshot file.
   *   <li>Apply mutator; if result is {@code null}, return {@code null} (no write).
   *   <li>Determine final id; apply sessionId / status defaulting.
   *   <li>Atomic write to {@code <prefix>/<finalId>.json}.
   *   <li>Advance pointer if this is a new snapshot row.
   *   <li>Prune chain if {@code maxPersistedChainLength > 0}.
   *   <li>Notify subscribers if status changed.
   * </ol>
   */
  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public String saveSnapshot(
      String snapshotId, SnapshotMutator<S> mutator, SessionStoreOptions options) {

    if (snapshotId != null) {
      checkPathSafety(snapshotId);
    }

    String finalId;
    SessionSnapshot<S> result;
    boolean isNewRow;
    SnapshotStatus existingStatus;

    synchronized (lock) {
      Path prefixDir = prefixDir();
      ensureDirectoryExists(prefixDir);

      // Step 1: read existing
      Path existingFile = snapshotId != null ? prefixDir.resolve(snapshotId + ".json") : null;
      SessionSnapshot<S> existing =
          (existingFile != null && Files.exists(existingFile))
              ? (SessionSnapshot<S>) readSnapshotFile(existingFile)
              : null;

      // Step 2: apply mutator
      result = mutator.apply(existing);
      if (result == null) {
        return null;
      }

      // Step 3: determine final id
      if (snapshotId != null) {
        finalId = snapshotId;
      } else if (result.getSnapshotId() != null && !result.getSnapshotId().isBlank()) {
        finalId = result.getSnapshotId();
      } else {
        finalId = UUID.randomUUID().toString();
      }
      result.setSnapshotId(finalId);

      // Step 4: preserve sessionId from existing row
      if (existing != null && existing.getSessionId() != null) {
        result.setSessionId(existing.getSessionId());
      }

      // Step 4b: fall back to state.sessionId if top-level is null
      if (result.getSessionId() == null && result.getState() != null) {
        result.setSessionId(result.getState().getSessionId());
      }

      // Step 4c: validate sessionId
      if (result.getSessionId() == null || result.getSessionId().isBlank()) {
        throw GenkitException.builder()
            .message("snapshot requires sessionId")
            .errorCode("INVALID_ARGUMENT")
            .build();
      }

      // Step 5: default null status to COMPLETED
      if (result.getStatus() == null) {
        result.setStatus(SnapshotStatus.COMPLETED);
      }

      existingStatus = existing != null ? existing.getStatus() : null;
      isNewRow = (existing == null);

      // Step 6: atomic write
      Path targetFile = prefixDir.resolve(finalId + ".json");
      atomicWrite(targetFile, result);

      // Step 7: advance pointer (only for new rows with a sessionId)
      if (isNewRow && result.getSessionId() != null && !result.getSessionId().isBlank()) {
        advancePointer(prefixDir, result);
      }

      // Step 8: chain pruning
      if (maxPersistedChainLength > 0 && isNewRow) {
        pruneChain(prefixDir, result);
      }
    } // lock released

    // Step 9: notify subscribers outside the lock
    boolean statusChanged = existingStatus != result.getStatus();
    if (statusChanged) {
      notifySubscribers(finalId, result);
    }

    return finalId;
  }

  // ── SnapshotSubscriber ────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Registers a polling subscription for the snapshot identified by {@code snapshotId}. If the
   * snapshot file already exists, the callback is fired immediately with the current content. A
   * shared daemon {@link ScheduledExecutorService} polls the file every {@code
   * snapshotWatchPollIntervalMs} milliseconds; the callback fires when the serialised content
   * changes. Calling {@link AutoCloseable#close()} on the returned handle cancels the poll.
   */
  @Override
  public AutoCloseable onSnapshotStateChange(
      String snapshotId, Consumer<SessionSnapshot<?>> cb, SessionStoreOptions options) {

    checkPathSafety(snapshotId);

    Path prefixDir = prefixDir();
    Path snapshotFile = prefixDir.resolve(snapshotId + ".json");

    // State for de-duplication: last known serialised content (null = not yet seen)
    final String[] lastContent = {null};

    // Fire immediately if file already exists
    SessionSnapshot<?> initial = readSnapshotFileUnsafe(snapshotFile);
    if (initial != null) {
      lastContent[0] = serializeQuietly(initial);
      cb.accept(initial);
    }

    // Schedule polling
    ScheduledFuture<?> future =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                SessionSnapshot<?> snap = readSnapshotFileUnsafe(snapshotFile);
                if (snap == null) {
                  return;
                }
                String content = serializeQuietly(snap);
                if (!content.equals(lastContent[0])) {
                  lastContent[0] = content;
                  cb.accept(snap);
                }
              } catch (Exception e) {
                // Swallow poll errors — don't kill the scheduler thread
              }
            },
            snapshotWatchPollIntervalMs,
            snapshotWatchPollIntervalMs,
            TimeUnit.MILLISECONDS);

    return () -> future.cancel(false);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Returns the prefix directory ({@code <baseDir>/<prefix>}). */
  private Path prefixDir() {
    return baseDir.resolve(prefix);
  }

  /** Returns the pointer file path for the given session ID. */
  private Path pointerFile(Path prefixDir, String sessionId) {
    return prefixDir.resolve(".pointers").resolve(sessionId + ".json");
  }

  /** Ensures the directory (and its parents) exist. */
  private static void ensureDirectoryExists(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new GenkitException("Failed to create directory: " + dir, e);
    }
  }

  /**
   * Validates that the given name component does not allow path traversal.
   *
   * <p>Rejects names that contain {@code /}, {@code \}, or NUL characters; equal to {@code .} or
   * {@code ..}; or start with {@code .}.
   *
   * @throws IllegalArgumentException if the name is unsafe
   */
  private static void checkPathSafety(String name) {
    if (name == null) {
      return;
    }
    if (name.contains("/")
        || name.contains("\\")
        || name.contains("\0")
        || name.equals(".")
        || name.equals("..")
        || name.startsWith(".")) {
      throw new IllegalArgumentException(
          "Unsafe snapshotId/sessionId rejected (path traversal risk): " + name);
    }
  }

  /** Reads and deserialises a snapshot JSON file; returns {@code null} if the file is absent. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private SessionSnapshot<S> readSnapshotFile(Path file) {
    if (!Files.exists(file)) {
      return null;
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      return (SessionSnapshot<S>) MAPPER.readValue(bytes, SessionSnapshot.class);
    } catch (IOException e) {
      // Corrupt file — treat as absent
      return null;
    }
  }

  /** Like {@link #readSnapshotFile(Path)} but without the type param (for subscriber use). */
  @SuppressWarnings("rawtypes")
  private SessionSnapshot<?> readSnapshotFileUnsafe(Path file) {
    if (!Files.exists(file)) {
      return null;
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      return MAPPER.readValue(bytes, SessionSnapshot.class);
    } catch (IOException e) {
      return null;
    }
  }

  /** Reads and deserialises a pointer JSON file; returns {@code null} if absent or corrupt. */
  private PointerDoc readPointerFile(Path file) {
    if (!Files.exists(file)) {
      return null;
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      return MAPPER.readValue(bytes, PointerDoc.class);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Atomically writes {@code value} as JSON to {@code target}.
   *
   * <p>Writes to a temp file ({@code <target>.<uuid>.tmp}) first, then renames to {@code target}.
   */
  private static <T> void atomicWrite(Path target, T value) {
    ensureDirectoryExists(target.getParent());
    Path tmp = target.getParent().resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    try {
      byte[] bytes = MAPPER.writeValueAsBytes(value);
      Files.write(tmp, bytes);
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicEx) {
        // Fallback: REPLACE_EXISTING (non-atomic but safe enough)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      // Clean up the temp file if we fail
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // best effort
      }
      throw new GenkitException("Failed to write snapshot file: " + target, e);
    }
  }

  /**
   * Advances the pointer for the session if this snapshot sorts ahead of the current pointer.
   *
   * <p>The pointer is only advanced when {@code snap} is a new row (existing == null). Comparison
   * is by ({@code createdAt}, {@code snapshotId}).
   */
  private void advancePointer(Path prefixDir, SessionSnapshot<S> snap) {
    if (snap.getSessionId() == null || snap.getSessionId().isBlank()) {
      return;
    }
    Path pointersDir = prefixDir.resolve(".pointers");
    ensureDirectoryExists(pointersDir);

    Path pointerFile = pointersDir.resolve(snap.getSessionId() + ".json");
    PointerDoc existing = readPointerFile(pointerFile);

    boolean shouldAdvance = false;
    if (existing == null || existing.getCurrentSnapshotId() == null) {
      shouldAdvance = true;
    } else {
      // Compare by (createdAt, snapshotId)
      int cmp =
          compareCreatedAtThenId(
              snap.getCreatedAt(),
              snap.getSnapshotId(),
              existing.getCurrentCreatedAt(),
              existing.getCurrentSnapshotId());
      shouldAdvance = cmp > 0;
    }

    if (shouldAdvance) {
      String now = Instant.now().toString();
      PointerDoc pointer = new PointerDoc(snap.getSnapshotId(), snap.getCreatedAt(), now);
      atomicWrite(pointerFile, pointer);
    }
  }

  /**
   * Compares two (createdAt, snapshotId) pairs. Returns positive if (aCreatedAt, aId) sorts after
   * (bCreatedAt, bId).
   */
  private static int compareCreatedAtThenId(
      String aCreatedAt, String aId, String bCreatedAt, String bId) {
    Instant ia = parseInstant(aCreatedAt);
    Instant ib = parseInstant(bCreatedAt);
    int cmp = ia.compareTo(ib);
    if (cmp != 0) {
      return cmp;
    }
    String sa = aId != null ? aId : "";
    String sb = bId != null ? bId : "";
    return sa.compareTo(sb);
  }

  /** Parses an RFC-3339 timestamp; returns {@link Instant#EPOCH} for null/unparseable. */
  private static Instant parseInstant(String rfc3339) {
    if (rfc3339 == null || rfc3339.isEmpty()) {
      return Instant.EPOCH;
    }
    try {
      return Instant.parse(rfc3339);
    } catch (Exception e) {
      return Instant.EPOCH;
    }
  }

  /**
   * Scans the prefix directory for all snapshot files belonging to {@code sessionId}, selects the
   * leaf via {@link LeafSelection}, rewrites the pointer, and returns the leaf.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private SessionSnapshot<S> scanAndSelectLeaf(Path prefixDir, String sessionId, Path pointerFile) {
    if (!Files.isDirectory(prefixDir)) {
      return null;
    }

    List<SessionSnapshot<S>> matching = new ArrayList<>();
    try (Stream<Path> files = Files.list(prefixDir)) {
      files
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !Files.isDirectory(p))
          .forEach(
              p -> {
                SessionSnapshot<S> snap = readSnapshotFile(p);
                if (snap != null && sessionId.equals(effectiveSessionId(snap))) {
                  matching.add(snap);
                }
              });
    } catch (IOException e) {
      return null;
    }

    if (matching.isEmpty()) {
      return null;
    }

    SessionSnapshot<S> leaf = LeafSelection.selectLeaf(matching, rejectBranchingSessions);
    if (leaf == null) {
      return null;
    }

    // Rewrite pointer
    Path pointersDir = prefixDir.resolve(".pointers");
    ensureDirectoryExists(pointersDir);
    String now = Instant.now().toString();
    PointerDoc pointer = new PointerDoc(leaf.getSnapshotId(), leaf.getCreatedAt(), now);
    atomicWrite(pointerFile, pointer);

    return leaf;
  }

  /**
   * Prunes the parent chain rooted at {@code snap}, deleting snapshot files beyond the newest
   * {@code maxPersistedChainLength}.
   *
   * <p>Walks the parent chain backwards from {@code snap}; collects all ancestors in order (newest
   * first); deletes those beyond position {@code maxPersistedChainLength - 1}.
   */
  private void pruneChain(Path prefixDir, SessionSnapshot<S> snap) {
    // Build the chain newest-first
    List<String> chain = new ArrayList<>();
    String current = snap.getSnapshotId();
    // Collect the chain by following parentId links (read from files)
    // We need a map of snapshotId -> parentId to walk the chain.
    // Build by collecting all snapshots for this session.
    Map<String, String> parentOf = new HashMap<>(); // snapshotId -> parentId
    if (Files.isDirectory(prefixDir)) {
      try (Stream<Path> files = Files.list(prefixDir)) {
        files
            .filter(p -> p.getFileName().toString().endsWith(".json"))
            .filter(p -> !Files.isDirectory(p))
            .filter(p -> !p.getParent().getFileName().toString().equals(".pointers"))
            .forEach(
                p -> {
                  SessionSnapshot<?> s = readSnapshotFileUnsafe(p);
                  if (s != null && s.getSnapshotId() != null) {
                    parentOf.put(s.getSnapshotId(), s.getParentId()); // parentId may be null
                  }
                });
      } catch (IOException e) {
        return; // best effort
      }
    }

    // Walk from snap backwards through parentId links
    String node = snap.getSnapshotId();
    int maxWalk = parentOf.size() + 1; // guard against cycles
    int walked = 0;
    while (node != null && walked++ < maxWalk) {
      chain.add(node);
      node = parentOf.getOrDefault(node, null);
    }

    // chain[0] is the newest; delete anything beyond position maxPersistedChainLength-1
    for (int i = maxPersistedChainLength; i < chain.size(); i++) {
      Path toDelete = prefixDir.resolve(chain.get(i) + ".json");
      try {
        Files.deleteIfExists(toDelete);
      } catch (IOException e) {
        // best effort
      }
    }
  }

  /** Returns the effective sessionId for a snapshot (top-level, then state.sessionId). */
  private static String effectiveSessionId(SessionSnapshot<?> snap) {
    if (snap == null) {
      return null;
    }
    if (snap.getSessionId() != null) {
      return snap.getSessionId();
    }
    if (snap.getState() != null) {
      return snap.getState().getSessionId();
    }
    return null;
  }

  /**
   * Serialises a snapshot to a JSON string for change-detection de-duplication; returns "" on
   * error.
   */
  private static String serializeQuietly(SessionSnapshot<?> snap) {
    try {
      JsonNode node = MAPPER.valueToTree(snap);
      return MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      return "";
    }
  }

  /** Notifies in-process subscribers (used when saveSnapshot changes status). */
  private void notifySubscribers(String finalId, SessionSnapshot<S> result) {
    // For disk-based store the polling mechanism handles notifications.
    // This method is a no-op: polling detects the file change independently.
    // (In-process callers that registered via onSnapshotStateChange will be notified by the poll.)
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  /**
   * Builder for {@link FileSessionStore}.
   *
   * @param <S> the type of custom session state
   */
  public static final class Builder<S> {
    private final String dir;
    private String prefix = DEFAULT_PREFIX;
    private int maxPersistedChainLength = 0;
    private boolean rejectBranchingSessions = false;
    private long snapshotWatchPollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    private Builder(String dir) {
      this.dir = dir;
    }

    /**
     * Sets the prefix sub-directory (default {@code "global"}).
     *
     * @param prefix the prefix
     * @return this builder
     */
    public Builder<S> prefix(String prefix) {
      this.prefix = prefix;
      return this;
    }

    /**
     * Sets the maximum number of snapshot files to retain in the parent chain. When set to a
     * positive value, older ancestors are deleted after each new save. Zero (default) means
     * unlimited.
     *
     * @param maxPersistedChainLength the maximum chain length, or 0 for unlimited
     * @return this builder
     */
    public Builder<S> maxPersistedChainLength(int maxPersistedChainLength) {
      this.maxPersistedChainLength = maxPersistedChainLength;
      return this;
    }

    /**
     * If {@code true}, {@link LeafSelection#selectLeaf} throws when more than one leaf exists for a
     * session.
     *
     * @param rejectBranchingSessions whether to reject branching
     * @return this builder
     */
    public Builder<S> rejectBranchingSessions(boolean rejectBranchingSessions) {
      this.rejectBranchingSessions = rejectBranchingSessions;
      return this;
    }

    /**
     * Sets the polling interval for snapshot-change subscriptions (default 2000 ms). Use a small
     * value (e.g. 100 ms) in tests for faster callback detection.
     *
     * @param ms the poll interval in milliseconds
     * @return this builder
     */
    public Builder<S> snapshotWatchPollIntervalMs(long ms) {
      this.snapshotWatchPollIntervalMs = ms;
      return this;
    }

    /**
     * Builds a new {@link FileSessionStore}.
     *
     * @return a new store
     */
    public FileSessionStore<S> build() {
      return new FileSessionStore<>(
          dir,
          prefix,
          maxPersistedChainLength,
          rejectBranchingSessions,
          snapshotWatchPollIntervalMs);
    }
  }
}
