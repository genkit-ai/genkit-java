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

package com.google.genkit.plugins.weaviate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WeaviatePluginTest {

  @Test
  void testBuilderCreation() {
    WeaviatePlugin.Builder builder = WeaviatePlugin.builder();

    assertNotNull(builder);
  }

  @Test
  void testBuilderWithHost() {
    WeaviateCollectionConfig config = WeaviateCollectionConfig.builder().name("test-collection")
        .embedderName("test-embedder").build();

    WeaviatePlugin plugin = WeaviatePlugin.builder().host("localhost").port(8080).addCollection(config).build();

    assertNotNull(plugin);
    assertEquals("weaviate", plugin.getName());
  }

  @Test
  void testBuilderWithApiKey() {
    WeaviateCollectionConfig config = WeaviateCollectionConfig.builder().name("test-collection")
        .embedderName("test-embedder").build();

    WeaviatePlugin plugin = WeaviatePlugin.builder().host("weaviate.example.com").apiKey("test-api-key")
        .secure(true).addCollection(config).build();

    assertNotNull(plugin);
    assertEquals("weaviate", plugin.getName());
  }

  @Test
  void testGetName() {
    WeaviateCollectionConfig config = WeaviateCollectionConfig.builder().name("test-collection")
        .embedderName("test-embedder").build();

    WeaviatePlugin plugin = WeaviatePlugin.builder().host("localhost").addCollection(config).build();

    assertEquals("weaviate", plugin.getName());
  }

  @Test
  void testBuilderChaining() {
    WeaviateCollectionConfig config = WeaviateCollectionConfig.builder().name("test-collection")
        .embedderName("test-embedder").build();

    WeaviatePlugin plugin = WeaviatePlugin.builder().host("localhost").port(8080).grpcPort(50051).secure(false)
        .addCollection(config).build();

    assertNotNull(plugin);
  }
}
