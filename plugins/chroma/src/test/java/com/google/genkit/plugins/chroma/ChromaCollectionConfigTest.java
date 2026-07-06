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

package com.google.genkit.plugins.chroma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ChromaCollectionConfig}. */
class ChromaCollectionConfigTest {

  @Test
  void defaultsAreSane() {
    ChromaCollectionConfig c =
        ChromaCollectionConfig.builder()
            .collectionName("films")
            .embedderName("googleai/gemini-embedding-001")
            .build();
    assertEquals("films", c.getCollectionName());
    assertEquals("googleai/gemini-embedding-001", c.getEmbedderName());
    assertEquals(ChromaCollectionConfig.Distance.COSINE, c.getDistance());
    assertEquals("cosine", c.getDistance().getValue());
    assertTrue(c.isCreateCollectionIfNotExists());
    assertTrue(c.getAdditionalMetadata().isEmpty());
  }

  @Test
  void customBuilder() {
    ChromaCollectionConfig c =
        ChromaCollectionConfig.builder()
            .collectionName("docs")
            .embedderName("e")
            .distance(ChromaCollectionConfig.Distance.L2)
            .createCollectionIfNotExists(false)
            .addAdditionalMetadata("source", "wiki")
            .build();
    assertEquals(ChromaCollectionConfig.Distance.L2, c.getDistance());
    assertEquals("l2", c.getDistance().getValue());
    assertEquals(false, c.isCreateCollectionIfNotExists());
    assertEquals("wiki", c.getAdditionalMetadata().get("source"));
  }

  @Test
  void builderValidates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ChromaCollectionConfig.builder().embedderName("e").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> ChromaCollectionConfig.builder().collectionName("c").build());
  }
}
