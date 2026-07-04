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

package com.google.genkit.plugins.azurefoundry.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.genkit.ai.agent.SessionStoreOptions;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CosmosSessionStoreOptions}. */
class CosmosSessionStoreOptionsTest {

  @Test
  void defaultsAreSane() {
    CosmosSessionStoreOptions o = CosmosSessionStoreOptions.defaults();
    assertEquals("genkit", o.getDatabaseName());
    assertEquals("genkit-sessions", o.getContainerName());
    assertEquals(25, o.getCheckpointInterval());
    assertEquals(1024 * 1024, o.getShardSize());
    assertEquals("global", o.getSnapshotPathPrefix().apply(SessionStoreOptions.empty()));
    assertFalse(o.isCreateIfNotExists());
    assertEquals(2000L, o.getPollIntervalMs());
  }

  @Test
  void customBuilder() {
    CosmosSessionStoreOptions o =
        CosmosSessionStoreOptions.builder()
            .databaseName("my-db")
            .containerName("my-sessions")
            .checkpointInterval(10)
            .shardSize(4096)
            .snapshotPathPrefix(so -> "tenant-1")
            .createIfNotExists(true)
            .pollIntervalMs(500)
            .build();
    assertEquals("my-db", o.getDatabaseName());
    assertEquals("my-sessions", o.getContainerName());
    assertEquals(10, o.getCheckpointInterval());
    assertEquals(4096, o.getShardSize());
    assertEquals("tenant-1", o.getSnapshotPathPrefix().apply(SessionStoreOptions.empty()));
    assertEquals(true, o.isCreateIfNotExists());
    assertEquals(500L, o.getPollIntervalMs());
  }

  @Test
  void builderValidates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CosmosSessionStoreOptions.builder().databaseName("").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> CosmosSessionStoreOptions.builder().containerName("").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> CosmosSessionStoreOptions.builder().shardSize(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> CosmosSessionStoreOptions.builder().pollIntervalMs(0).build());
  }
}
