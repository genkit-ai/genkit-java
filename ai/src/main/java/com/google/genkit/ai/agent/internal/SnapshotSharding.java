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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.jsonpatch.JsonPatch;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, backend-agnostic sharding / checkpoint / RFC-6902 reconstruction helpers shared by the
 * Firestore, DynamoDB, and Cosmos DB session stores.
 *
 * <p>Session stores that use the sharded checkpoint + diff + pointer layout persist a session's
 * state as a periodic full "checkpoint" (its JSON split into byte-sized shards) plus a chain of
 * RFC-6902 "diff" patches from the nearest checkpoint. These helpers implement the pure logic of
 * that scheme so every backend behaves identically:
 *
 * <ul>
 *   <li>{@link #shouldCheckpoint} — decides checkpoint vs diff for a new snapshot.
 *   <li>{@link #shardString} / {@link #reassembleShards} — byte-exact splitting/rejoining of the
 *       checkpoint JSON.
 *   <li>{@link #reconstructState} — replays the checkpoint + ordered diffs back into a state node.
 *   <li>{@link #validateId} — validates snapshot/session identifiers.
 * </ul>
 */
public final class SnapshotSharding {

  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  private SnapshotSharding() {}

  /**
   * Decides whether to write a full checkpoint instead of a diff.
   *
   * <p>A checkpoint is written when:
   *
   * <ul>
   *   <li>there is no usable parent (session root, or parent orphaned/missing); or
   *   <li>the depth from the nearest checkpoint reaches {@code checkpointInterval}; or
   *   <li>the diff against the parent would exceed {@code shardSize} bytes.
   * </ul>
   *
   * @param parentExists whether a usable parent snapshot exists
   * @param depthFromCheckpoint the number of diffs (inclusive) that would separate this snapshot
   *     from its nearest checkpoint
   * @param checkpointInterval the configured checkpoint interval
   * @param diffSizeBytes the serialized size of the candidate diff in bytes
   * @param shardSize the configured shard size in bytes
   * @return {@code true} if a full checkpoint should be written
   */
  public static boolean shouldCheckpoint(
      boolean parentExists,
      int depthFromCheckpoint,
      int checkpointInterval,
      int diffSizeBytes,
      int shardSize) {
    if (!parentExists) {
      return true;
    }
    if (depthFromCheckpoint >= checkpointInterval) {
      return true;
    }
    return diffSizeBytes > shardSize;
  }

  /**
   * Splits a UTF-8 string into shards of at most {@code shardSize} bytes each.
   *
   * <p>Sharding is byte-exact: shards are concatenated by raw bytes (not chars) so multi-byte UTF-8
   * sequences split across a boundary reassemble correctly.
   *
   * @param value the string to shard
   * @param shardSize the maximum shard size in bytes (must be {@code >= 1})
   * @return the ordered shards; each shard holds at most {@code shardSize} raw UTF-8 bytes encoded
   *     as an ISO-8859-1 string (1 char per byte) for byte-exact reassembly by {@link
   *     #reassembleShards}
   */
  public static List<String> shardString(String value, int shardSize) {
    if (shardSize < 1) {
      throw new IllegalArgumentException("shardSize must be >= 1");
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    List<String> shards = new ArrayList<>();
    if (bytes.length == 0) {
      shards.add("");
      return shards;
    }
    for (int offset = 0; offset < bytes.length; offset += shardSize) {
      int len = Math.min(shardSize, bytes.length - offset);
      // Encode raw bytes as ISO-8859-1 so each byte maps 1:1 to a char, allowing byte-exact
      // reassembly even when a multi-byte UTF-8 sequence is split across a shard boundary.
      shards.add(new String(bytes, offset, len, StandardCharsets.ISO_8859_1));
    }
    return shards;
  }

  /**
   * Reassembles shards produced by {@link #shardString} back into the original string.
   *
   * @param shards the ordered shard list
   * @return the reassembled original string
   */
  public static String reassembleShards(List<String> shards) {
    int total = 0;
    byte[][] parts = new byte[shards.size()][];
    for (int i = 0; i < shards.size(); i++) {
      parts[i] = shards.get(i).getBytes(StandardCharsets.ISO_8859_1);
      total += parts[i].length;
    }
    byte[] all = new byte[total];
    int pos = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, all, pos, part.length);
      pos += part.length;
    }
    return new String(all, StandardCharsets.UTF_8);
  }

  /**
   * Reconstructs a state node from a checkpoint JSON string and a list of RFC-6902 patch JSON
   * strings, applied in order via {@link JsonPatch#apply}.
   *
   * @param checkpointJson the full checkpoint state JSON
   * @param diffPatchesJson the ordered list of opaque JSON-string patches (each an RFC-6902 op
   *     array)
   * @return the reconstructed state node
   * @throws Exception if any JSON cannot be parsed
   */
  public static JsonNode reconstructState(String checkpointJson, List<String> diffPatchesJson)
      throws Exception {
    JsonNode state =
        (checkpointJson == null || checkpointJson.isEmpty())
            ? NullNode.getInstance()
            : MAPPER.readTree(checkpointJson);
    for (String patchJson : diffPatchesJson) {
      if (patchJson == null || patchJson.isEmpty()) {
        continue;
      }
      JsonNode patch = MAPPER.readTree(patchJson);
      state = JsonPatch.apply(state, patch);
    }
    return state;
  }

  /**
   * Validates a snapshot/session document id.
   *
   * @param id the id to validate
   * @throws GenkitException with {@code INVALID_ARGUMENT} if the id is null, empty, or contains a
   *     forward slash
   */
  public static void validateId(String id) {
    if (id == null || id.isEmpty()) {
      throw GenkitException.builder()
          .message("id must be non-empty")
          .errorCode("INVALID_ARGUMENT")
          .build();
    }
    if (id.contains("/")) {
      throw GenkitException.builder()
          .message("id must not contain '/': " + id)
          .errorCode("INVALID_ARGUMENT")
          .build();
    }
  }
}
