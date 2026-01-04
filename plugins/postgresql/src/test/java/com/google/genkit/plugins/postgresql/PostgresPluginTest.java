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

package com.google.genkit.plugins.postgresql;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PostgresPluginTest {

  @Test
  void testBuilderCreation() {
    PostgresPlugin.Builder builder = PostgresPlugin.builder();

    assertNotNull(builder);
  }

  @Test
  void testBuilderWithConnectionString() {
    PostgresTableConfig tableConfig = PostgresTableConfig.builder().tableName("test_table")
        .embedderName("test-embedder").build();

    PostgresPlugin plugin = PostgresPlugin.builder().connectionString("jdbc:postgresql://localhost:5432/testdb")
        .username("testuser").password("testpass").addTable(tableConfig).build();

    assertNotNull(plugin);
    assertEquals("postgresql", plugin.getName());
  }

  @Test
  void testGetName() {
    PostgresTableConfig tableConfig = PostgresTableConfig.builder().tableName("test_table")
        .embedderName("test-embedder").build();

    PostgresPlugin plugin = PostgresPlugin.builder().connectionString("jdbc:postgresql://localhost:5432/testdb")
        .username("testuser").password("testpass").addTable(tableConfig).build();

    assertEquals("postgresql", plugin.getName());
  }

  @Test
  void testBuilderChaining() {
    PostgresTableConfig tableConfig = PostgresTableConfig.builder().tableName("test_table")
        .embedderName("test-embedder").build();

    PostgresPlugin plugin = PostgresPlugin.builder().connectionString("jdbc:postgresql://localhost:5432/testdb")
        .username("user").password("pass").addTable(tableConfig).build();

    assertNotNull(plugin);
  }
}
