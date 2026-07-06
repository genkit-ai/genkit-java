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

package com.google.genkit.plugins.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link MongoVectorStoreConfig}. */
class MongoVectorStoreConfigTest {

  @Test
  void defaultsAreSane() {
    MongoVectorStoreConfig c =
        MongoVectorStoreConfig.builder()
            .collectionName("films")
            .embedderName("googleai/gemini-embedding-001")
            .build();
    assertEquals("genkit", c.getDatabaseName());
    assertEquals("films", c.getCollectionName());
    assertEquals("genkit_vector_index", c.getIndexName());
    assertEquals(768, c.getDimension());
    assertEquals(MongoVectorStoreConfig.Similarity.COSINE, c.getSimilarity());
    assertEquals("cosine", c.getSimilarity().getValue());
    assertEquals("text", c.getTextField());
    assertEquals("embedding", c.getEmbeddingField());
    assertEquals(100, c.getNumCandidates());
    assertEquals(false, c.isCreateIndexIfNotExists());
    assertTrue(c.getAdditionalMetadata().isEmpty());
  }

  @Test
  void customBuilder() {
    MongoVectorStoreConfig c =
        MongoVectorStoreConfig.builder()
            .databaseName("rag")
            .collectionName("docs")
            .embedderName("e")
            .indexName("idx")
            .dimension(1536)
            .similarity(MongoVectorStoreConfig.Similarity.DOT_PRODUCT)
            .textField("content")
            .embeddingField("vector")
            .numCandidates(200)
            .createIndexIfNotExists(true)
            .addAdditionalMetadata("source", "wiki")
            .build();
    assertEquals("rag", c.getDatabaseName());
    assertEquals("idx", c.getIndexName());
    assertEquals(1536, c.getDimension());
    assertEquals("dotProduct", c.getSimilarity().getValue());
    assertEquals("content", c.getTextField());
    assertEquals("vector", c.getEmbeddingField());
    assertEquals(200, c.getNumCandidates());
    assertEquals(true, c.isCreateIndexIfNotExists());
    assertEquals("wiki", c.getAdditionalMetadata().get("source"));
  }

  @Test
  void builderValidates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MongoVectorStoreConfig.builder().embedderName("e").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> MongoVectorStoreConfig.builder().collectionName("c").build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MongoVectorStoreConfig.builder()
                .collectionName("c")
                .embedderName("e")
                .dimension(0)
                .build());
  }
}
