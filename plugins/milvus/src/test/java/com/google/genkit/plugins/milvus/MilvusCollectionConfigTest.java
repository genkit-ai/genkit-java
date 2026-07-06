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

package com.google.genkit.plugins.milvus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link MilvusCollectionConfig}. */
class MilvusCollectionConfigTest {

  @Test
  void defaultsAreSane() {
    MilvusCollectionConfig c =
        MilvusCollectionConfig.builder().collectionName("films").embedderName("e").build();
    assertEquals("films", c.getCollectionName());
    assertEquals(768, c.getDimension());
    assertEquals(MilvusCollectionConfig.Metric.COSINE, c.getMetric());
    assertEquals("COSINE", c.getMetric().getValue());
    assertTrue(c.isCreateCollectionIfNotExists());
  }

  @Test
  void customBuilder() {
    MilvusCollectionConfig c =
        MilvusCollectionConfig.builder()
            .collectionName("docs")
            .embedderName("e")
            .dimension(1536)
            .metric(MilvusCollectionConfig.Metric.INNER_PRODUCT)
            .createCollectionIfNotExists(false)
            .addAdditionalMetadata("source", "wiki")
            .build();
    assertEquals(1536, c.getDimension());
    assertEquals("IP", c.getMetric().getValue());
    assertEquals(false, c.isCreateCollectionIfNotExists());
    assertEquals("wiki", c.getAdditionalMetadata().get("source"));
  }

  @Test
  void builderValidates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MilvusCollectionConfig.builder().embedderName("e").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> MilvusCollectionConfig.builder().collectionName("c").build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MilvusCollectionConfig.builder()
                .collectionName("c")
                .embedderName("e")
                .dimension(0)
                .build());
  }
}
