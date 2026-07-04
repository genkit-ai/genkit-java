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

package com.google.genkit.ai.agent.internal;

import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.core.GenkitException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility for selecting the leaf snapshot from a collection of snapshots in a session's
 * parent-chain.
 *
 * <p>A <em>leaf</em> is a snapshot whose {@code snapshotId} is not referenced as a {@code parentId}
 * by any other snapshot in the list.
 */
public final class LeafSelection {

  private LeafSelection() {}

  /**
   * Selects the leaf snapshot from the given list.
   *
   * <p>Selection rules:
   *
   * <ol>
   *   <li><strong>Empty list</strong> — returns {@code null}. An empty input carries no information
   *       about a cycle, so returning {@code null} (no snapshot found) is the correct behaviour.
   *   <li><strong>Exactly one leaf</strong> — returns it directly.
   *   <li><strong>Zero leaves in a non-empty list</strong> — the chain is corrupt (cycle). Throws
   *       {@link GenkitException} with error code {@code FAILED_PRECONDITION}.
   *   <li><strong>More than one leaf</strong> — if {@code rejectBranching} is {@code true}, throws
   *       {@link GenkitException} with error code {@code FAILED_PRECONDITION}. Otherwise the most
   *       recent leaf is returned (by {@code createdAt} parsed as {@link Instant}; {@code null}
   *       {@code createdAt} is treated as the epoch / earliest). Ties are broken by {@code
   *       snapshotId} in natural (lexicographic) ascending order, taking the <em>greater</em> ID.
   * </ol>
   *
   * @param <S> the type of custom session state
   * @param snapshots the full list of snapshots for a session (may be empty)
   * @param rejectBranching if {@code true}, more than one leaf is treated as an error
   * @return the selected leaf snapshot, or {@code null} if the input list is empty
   * @throws GenkitException with {@code FAILED_PRECONDITION} when the chain contains a cycle or
   *     (with {@code rejectBranching=true}) multiple leaves
   */
  public static <S> SessionSnapshot<S> selectLeaf(
      List<SessionSnapshot<S>> snapshots, boolean rejectBranching) {

    if (snapshots == null || snapshots.isEmpty()) {
      return null;
    }

    // Collect all IDs that appear as a parentId — these are non-leaf nodes.
    Set<String> referencedAsParent =
        snapshots.stream()
            .map(SessionSnapshot::getParentId)
            .filter(pid -> pid != null)
            .collect(Collectors.toSet());

    // Leaves are snapshots whose own snapshotId is not in the parent set.
    List<SessionSnapshot<S>> leaves =
        snapshots.stream()
            .filter(s -> !referencedAsParent.contains(s.getSnapshotId()))
            .collect(Collectors.toList());

    if (leaves.isEmpty()) {
      throw GenkitException.builder()
          .message(
              "Session snapshot chain contains a cycle or all snapshots reference a parent:"
                  + " no leaf found.")
          .errorCode("FAILED_PRECONDITION")
          .build();
    }

    if (leaves.size() == 1) {
      return leaves.get(0);
    }

    // Multiple leaves.
    if (rejectBranching) {
      throw GenkitException.builder()
          .message(
              "Session snapshot chain has "
                  + leaves.size()
                  + " leaves (branching detected). Use rejectBranching=false to select the most"
                  + " recent leaf automatically.")
          .errorCode("FAILED_PRECONDITION")
          .build();
    }

    // Pick the most recent leaf; null createdAt treated as epoch (earliest).
    return leaves.stream()
        .max(LeafSelection::compareByCreatedAtThenId)
        .orElseThrow(
            () ->
                GenkitException.builder()
                    .message("Unexpected empty leaves stream.")
                    .errorCode("FAILED_PRECONDITION")
                    .build());
  }

  /**
   * Comparator: earlier createdAt → smaller; null createdAt → epoch (smallest). Tie-break: larger
   * snapshotId wins (natural string order ascending, max).
   */
  private static <S> int compareByCreatedAtThenId(SessionSnapshot<S> a, SessionSnapshot<S> b) {
    Instant ia = parseInstant(a.getCreatedAt());
    Instant ib = parseInstant(b.getCreatedAt());
    int cmp = ia.compareTo(ib);
    if (cmp != 0) {
      return cmp;
    }
    // Tie-break: lexicographically larger snapshotId wins.
    String idA = a.getSnapshotId() != null ? a.getSnapshotId() : "";
    String idB = b.getSnapshotId() != null ? b.getSnapshotId() : "";
    return idA.compareTo(idB);
  }

  /** Parses an RFC-3339 timestamp string; returns {@link Instant#EPOCH} for null or unparseable. */
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
}
