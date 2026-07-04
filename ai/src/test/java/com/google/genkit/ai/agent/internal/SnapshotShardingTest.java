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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.jsonpatch.JsonPatch;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SnapshotSharding} — the pure sharding / checkpoint-vs-diff / state
 * reconstruction helpers shared by the Firestore, DynamoDB, and Cosmos session stores. These run
 * with no backend client.
 */
class SnapshotShardingTest {

  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  @Test
  void shardingRoundTripSmallString() {
    String original = "hello world, this is a small JSON-ish payload {\"a\":1}";
    byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
    List<String> shards = SnapshotSharding.shardString(original, 8);
    String reassembled = SnapshotSharding.reassembleShards(shards);
    assertEquals(original, reassembled);
    int expectedCount = (bytes.length + 8 - 1) / 8;
    assertEquals(expectedCount, shards.size());
  }

  @Test
  void shardingRoundTripExactMultiple() {
    String original = "abcdefghij"; // 10 bytes
    List<String> shards = SnapshotSharding.shardString(original, 5);
    assertEquals(2, shards.size());
    assertEquals(original, SnapshotSharding.reassembleShards(shards));
  }

  @Test
  void shardRoundTripAcrossMultibyteBoundary() {
    // Split a string with multi-byte UTF-8 chars ("é" = 2 bytes) on a byte boundary that lands
    // in the middle of a code point; byte-exact reassembly must recover the original.
    String s = "aébcdéf";
    List<String> shards = SnapshotSharding.shardString(s, 3);
    assertEquals(s, SnapshotSharding.reassembleShards(shards));
  }

  @Test
  void shardingRoundTripLargerThanSize() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      sb.append("x");
    }
    String original = sb.toString();
    int shardSize = 512;
    List<String> shards = SnapshotSharding.shardString(original, shardSize);
    int expected = (5000 + shardSize - 1) / shardSize;
    assertEquals(expected, shards.size());
    assertEquals(original, SnapshotSharding.reassembleShards(shards));
  }

  @Test
  void shardingSingleShardWhenSmallerThanSize() {
    String original = "tiny";
    List<String> shards = SnapshotSharding.shardString(original, 512 * 1024);
    assertEquals(1, shards.size());
    assertEquals(original, SnapshotSharding.reassembleShards(shards));
  }

  @Test
  void shardingEmptyStringProducesSingleEmptyShard() {
    List<String> shards = SnapshotSharding.shardString("", 8);
    assertEquals(1, shards.size());
    assertEquals("", SnapshotSharding.reassembleShards(shards));
  }

  @Test
  void decisionCheckpointWhenNoParent() {
    assertTrue(SnapshotSharding.shouldCheckpoint(false, 0, 25, 10, 512 * 1024));
  }

  @Test
  void decisionCheckpointWhenParentMissing() {
    assertTrue(SnapshotSharding.shouldCheckpoint(false, 3, 25, 10, 512 * 1024));
  }

  @Test
  void decisionDiffForNormalTurn() {
    assertFalse(SnapshotSharding.shouldCheckpoint(true, 3, 25, 10, 512 * 1024));
  }

  @Test
  void decisionCheckpointEveryInterval() {
    assertTrue(SnapshotSharding.shouldCheckpoint(true, 25, 25, 10, 512 * 1024));
  }

  @Test
  void decisionCheckpointWhenDiffExceedsShardSize() {
    assertTrue(SnapshotSharding.shouldCheckpoint(true, 3, 25, 600, 512));
  }

  @Test
  void reconstructStateFromCheckpointWithNoDiffs() throws Exception {
    Map<String, Object> base = new HashMap<>();
    base.put("count", 1);
    base.put("name", "alice");
    String checkpointJson = MAPPER.writeValueAsString(base);

    JsonNode result = SnapshotSharding.reconstructState(checkpointJson, new ArrayList<>());
    assertEquals(1, result.get("count").asInt());
    assertEquals("alice", result.get("name").asText());
  }

  @Test
  void reconstructStateAppliesDiffsInOrder() throws Exception {
    Map<String, Object> base = new HashMap<>();
    base.put("count", 1);
    String checkpointJson = MAPPER.writeValueAsString(base);

    JsonNode s1 = MAPPER.valueToTree(Map.of("count", 1));
    JsonNode s2 = MAPPER.valueToTree(Map.of("count", 2));
    JsonNode s3 = MAPPER.valueToTree(Map.of("count", 3, "extra", "y"));

    String patch1 = MAPPER.writeValueAsString(JsonPatch.diff(s1, s2));
    String patch2 = MAPPER.writeValueAsString(JsonPatch.diff(s2, s3));

    List<String> diffs = List.of(patch1, patch2);
    JsonNode result = SnapshotSharding.reconstructState(checkpointJson, diffs);
    assertEquals(3, result.get("count").asInt());
    assertEquals("y", result.get("extra").asText());
  }

  @Test
  void diffThenReconstructFullRoundTrip() throws Exception {
    JsonNode v0 = MAPPER.valueToTree(Map.of("a", 1, "list", List.of(1, 2, 3)));
    JsonNode v1 = MAPPER.valueToTree(Map.of("a", 2, "list", List.of(1, 2, 3)));
    JsonNode v2 = MAPPER.valueToTree(Map.of("a", 2, "list", List.of(1, 2, 3, 4), "b", "hi"));
    JsonNode v3 = MAPPER.valueToTree(Map.of("a", 2, "list", List.of(9), "b", "hi"));

    String checkpoint = MAPPER.writeValueAsString(v0);
    List<String> diffs = new ArrayList<>();
    diffs.add(MAPPER.writeValueAsString(JsonPatch.diff(v0, v1)));
    diffs.add(MAPPER.writeValueAsString(JsonPatch.diff(v1, v2)));
    diffs.add(MAPPER.writeValueAsString(JsonPatch.diff(v2, v3)));

    JsonNode result = SnapshotSharding.reconstructState(checkpoint, diffs);
    assertEquals(v3, result);
  }

  @Test
  void validateIdRejectsSlash() {
    assertThrows(GenkitException.class, () -> SnapshotSharding.validateId("foo/bar"));
  }

  @Test
  void validateIdRejectsNullOrEmpty() {
    assertThrows(GenkitException.class, () -> SnapshotSharding.validateId(null));
    assertThrows(GenkitException.class, () -> SnapshotSharding.validateId(""));
  }

  @Test
  void validateIdAcceptsNormalId() {
    SnapshotSharding.validateId("abc-123_DEF");
  }
}
