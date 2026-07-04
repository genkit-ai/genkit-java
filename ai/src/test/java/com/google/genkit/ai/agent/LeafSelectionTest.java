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

import com.google.genkit.ai.agent.internal.LeafSelection;
import com.google.genkit.core.GenkitException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TDD tests for LeafSelection.selectLeaf. */
class LeafSelectionTest {

  // Helpers
  private static SessionSnapshot<Void> snap(String id, String parentId, String createdAt) {
    return SessionSnapshot.<Void>builder()
        .snapshotId(id)
        .parentId(parentId)
        .createdAt(createdAt)
        .build();
  }

  // ---- empty list → null ----

  @Test
  void testEmptyList_returnsNull() {
    assertNull(LeafSelection.selectLeaf(Collections.emptyList(), false));
  }

  @Test
  void testEmptyList_rejectBranching_returnsNull() {
    assertNull(LeafSelection.selectLeaf(Collections.emptyList(), true));
  }

  // ---- single snapshot → itself ----

  @Test
  void testSingleSnapshot_returnsItself() {
    SessionSnapshot<Void> a = snap("A", null, "2024-01-01T00:00:00Z");
    SessionSnapshot<Void> result = LeafSelection.selectLeaf(Collections.singletonList(a), false);
    assertSame(a, result);
  }

  @Test
  void testSingleSnapshot_noCreatedAt_returnsItself() {
    SessionSnapshot<Void> a = snap("A", null, null);
    SessionSnapshot<Void> result = LeafSelection.selectLeaf(Collections.singletonList(a), false);
    assertSame(a, result);
  }

  // ---- linear chain A ← B ← C: selectLeaf → C ----

  @Test
  void testLinearChain_returnsLeaf() {
    SessionSnapshot<Void> a = snap("A", null, "2024-01-01T00:00:00Z");
    SessionSnapshot<Void> b = snap("B", "A", "2024-01-02T00:00:00Z");
    SessionSnapshot<Void> c = snap("C", "B", "2024-01-03T00:00:00Z");

    List<SessionSnapshot<Void>> chain = Arrays.asList(a, b, c);
    SessionSnapshot<Void> result = LeafSelection.selectLeaf(chain, false);
    assertEquals("C", result.getSnapshotId());
  }

  @Test
  void testLinearChain_orderedDifferently_returnsLeaf() {
    // Same chain but snapshots in reverse order in the list
    SessionSnapshot<Void> a = snap("A", null, "2024-01-01T00:00:00Z");
    SessionSnapshot<Void> b = snap("B", "A", "2024-01-02T00:00:00Z");
    SessionSnapshot<Void> c = snap("C", "B", "2024-01-03T00:00:00Z");

    List<SessionSnapshot<Void>> chain = Arrays.asList(c, b, a);
    SessionSnapshot<Void> result = LeafSelection.selectLeaf(chain, false);
    assertEquals("C", result.getSnapshotId());
  }

  // ---- two leaves: C1(parent=B), C2(parent=B), C2.createdAt later ----

  @Test
  void testTwoLeaves_rejectBranchingFalse_returnsMostRecent() {
    SessionSnapshot<Void> a = snap("A", null, "2024-01-01T00:00:00Z");
    SessionSnapshot<Void> b = snap("B", "A", "2024-01-02T00:00:00Z");
    SessionSnapshot<Void> c1 = snap("C1", "B", "2024-01-03T00:00:00Z");
    SessionSnapshot<Void> c2 = snap("C2", "B", "2024-01-04T00:00:00Z");

    List<SessionSnapshot<Void>> snapshots = Arrays.asList(a, b, c1, c2);
    SessionSnapshot<Void> result = LeafSelection.selectLeaf(snapshots, false);
    assertEquals("C2", result.getSnapshotId());
  }

  @Test
  void testTwoLeaves_rejectBranchingTrue_throws() {
    SessionSnapshot<Void> a = snap("A", null, "2024-01-01T00:00:00Z");
    SessionSnapshot<Void> b = snap("B", "A", "2024-01-02T00:00:00Z");
    SessionSnapshot<Void> c1 = snap("C1", "B", "2024-01-03T00:00:00Z");
    SessionSnapshot<Void> c2 = snap("C2", "B", "2024-01-04T00:00:00Z");

    List<SessionSnapshot<Void>> snapshots = Arrays.asList(a, b, c1, c2);
    GenkitException ex =
        assertThrows(GenkitException.class, () -> LeafSelection.selectLeaf(snapshots, true));
    assertEquals("FAILED_PRECONDITION", ex.getErrorCode());
  }

  // ---- tie-break by snapshotId when createdAt equal ----

  @Test
  void testTwoLeaves_sameCreatedAt_tieBreakBySnapshotId() {
    SessionSnapshot<Void> a = snap("A", null, "2024-01-01T00:00:00Z");
    SessionSnapshot<Void> b = snap("B", "A", "2024-01-02T00:00:00Z");
    // Same timestamp; lexicographically "Z" > "C"
    SessionSnapshot<Void> c = snap("C", "B", "2024-01-03T00:00:00Z");
    SessionSnapshot<Void> z = snap("Z", "B", "2024-01-03T00:00:00Z");

    List<SessionSnapshot<Void>> snapshots = Arrays.asList(a, b, c, z);
    SessionSnapshot<Void> result = LeafSelection.selectLeaf(snapshots, false);
    assertEquals("Z", result.getSnapshotId());
  }

  // ---- null createdAt treated as earliest (epoch) ----

  @Test
  void testNullCreatedAt_treatedAsEarliest() {
    SessionSnapshot<Void> a = snap("A", null, null);
    SessionSnapshot<Void> b = snap("B", "A", "2024-01-02T00:00:00Z");
    SessionSnapshot<Void> c1 = snap("C1", "B", null); // null = earliest
    SessionSnapshot<Void> c2 = snap("C2", "B", "2024-01-03T00:00:00Z");

    List<SessionSnapshot<Void>> snapshots = Arrays.asList(a, b, c1, c2);
    SessionSnapshot<Void> result = LeafSelection.selectLeaf(snapshots, false);
    assertEquals("C2", result.getSnapshotId());
  }

  // ---- zero leaves in non-empty list (cycle) → throws FAILED_PRECONDITION ----

  @Test
  void testCycle_noLeaves_throws() {
    // A points to B, B points to A — neither is a leaf
    SessionSnapshot<Void> a = snap("A", "B", "2024-01-01T00:00:00Z");
    SessionSnapshot<Void> b = snap("B", "A", "2024-01-02T00:00:00Z");

    List<SessionSnapshot<Void>> snapshots = Arrays.asList(a, b);
    GenkitException ex =
        assertThrows(GenkitException.class, () -> LeafSelection.selectLeaf(snapshots, false));
    assertEquals("FAILED_PRECONDITION", ex.getErrorCode());
  }
}
