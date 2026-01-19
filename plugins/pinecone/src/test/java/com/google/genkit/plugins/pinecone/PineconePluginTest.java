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

package com.google.genkit.plugins.pinecone;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PineconePluginTest {

  @Test
  void testBuilderCreation() {
    PineconePlugin.Builder builder = PineconePlugin.builder();

    assertNotNull(builder);
  }

  @Test
  void testBuilderWithApiKey() {
    PineconeIndexConfig indexConfig =
        PineconeIndexConfig.builder().indexName("test-index").embedderName("test-embedder").build();

    PineconePlugin plugin =
        PineconePlugin.builder().apiKey("test-api-key").addIndex(indexConfig).build();

    assertNotNull(plugin);
    assertEquals("pinecone", plugin.getName());
  }

  @Test
  void testCreateWithApiKey() {
    PineconePlugin.Builder builder = PineconePlugin.create("test-api-key");

    assertNotNull(builder);
  }

  @Test
  void testGetName() {
    PineconeIndexConfig indexConfig =
        PineconeIndexConfig.builder().indexName("test-index").embedderName("test-embedder").build();

    PineconePlugin plugin =
        PineconePlugin.builder().apiKey("test-key").addIndex(indexConfig).build();

    assertEquals("pinecone", plugin.getName());
  }

  @Test
  void testBuilderChaining() {
    PineconeIndexConfig indexConfig =
        PineconeIndexConfig.builder().indexName("test-index").embedderName("test-embedder").build();

    PineconePlugin plugin =
        PineconePlugin.builder().apiKey("test-key").addIndex(indexConfig).build();

    assertNotNull(plugin);
  }
}
