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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.JsonUtils;
import org.junit.jupiter.api.Test;

/** Unit tests for SnapshotStatus and AgentFinishReason serialization/deserialization. */
class EnumSerdeTest {

  private static final ObjectMapper objectMapper = JsonUtils.getObjectMapper();

  // SnapshotStatus tests

  @Test
  void testSnapshotStatusSerialize() throws Exception {
    String json = objectMapper.writeValueAsString(SnapshotStatus.PENDING);
    assertEquals("\"pending\"", json);
  }

  @Test
  void testSnapshotStatusDeserialize() throws Exception {
    SnapshotStatus status = objectMapper.readValue("\"completed\"", SnapshotStatus.class);
    assertEquals(SnapshotStatus.COMPLETED, status);
  }

  @Test
  void testSnapshotStatusRoundTrip() throws Exception {
    SnapshotStatus[] values = {
      SnapshotStatus.PENDING,
      SnapshotStatus.COMPLETED,
      SnapshotStatus.ABORTED,
      SnapshotStatus.FAILED,
      SnapshotStatus.EXPIRED
    };
    for (SnapshotStatus status : values) {
      String json = objectMapper.writeValueAsString(status);
      SnapshotStatus deserialized = objectMapper.readValue(json, SnapshotStatus.class);
      assertEquals(status, deserialized);
    }
  }

  @Test
  void testSnapshotStatusFromValueOrCompletedWithNull() {
    SnapshotStatus status = SnapshotStatus.fromValueOrCompleted(null);
    assertEquals(SnapshotStatus.COMPLETED, status);
  }

  @Test
  void testSnapshotStatusFromValueOrCompletedWithEmpty() {
    SnapshotStatus status = SnapshotStatus.fromValueOrCompleted("");
    assertEquals(SnapshotStatus.COMPLETED, status);
  }

  @Test
  void testSnapshotStatusFromValueOrCompletedWithAborted() {
    SnapshotStatus status = SnapshotStatus.fromValueOrCompleted("aborted");
    assertEquals(SnapshotStatus.ABORTED, status);
  }

  @Test
  void testSnapshotStatusFromValueOrCompletedWithUnknown() {
    assertThrows(
        IllegalArgumentException.class, () -> SnapshotStatus.fromValueOrCompleted("unknown"));
  }

  // AgentFinishReason tests

  @Test
  void testAgentFinishReasonSerialize() throws Exception {
    String json = objectMapper.writeValueAsString(AgentFinishReason.DETACHED);
    assertEquals("\"detached\"", json);
  }

  @Test
  void testAgentFinishReasonDeserialize() throws Exception {
    AgentFinishReason reason = objectMapper.readValue("\"interrupted\"", AgentFinishReason.class);
    assertEquals(AgentFinishReason.INTERRUPTED, reason);
  }

  @Test
  void testAgentFinishReasonRoundTrip() throws Exception {
    AgentFinishReason[] values = {
      AgentFinishReason.STOP,
      AgentFinishReason.LENGTH,
      AgentFinishReason.BLOCKED,
      AgentFinishReason.INTERRUPTED,
      AgentFinishReason.OTHER,
      AgentFinishReason.UNKNOWN,
      AgentFinishReason.ABORTED,
      AgentFinishReason.DETACHED,
      AgentFinishReason.FAILED
    };
    for (AgentFinishReason reason : values) {
      String json = objectMapper.writeValueAsString(reason);
      AgentFinishReason deserialized = objectMapper.readValue(json, AgentFinishReason.class);
      assertEquals(reason, deserialized);
    }
  }
}
