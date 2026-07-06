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

package com.google.genkit.plugins.qdrant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link QdrantPlugin}. */
class QdrantPluginTest {

  private static QdrantCollectionConfig config() {
    return QdrantCollectionConfig.builder().collectionName("films").embedderName("e").build();
  }

  @Test
  void getName() {
    assertEquals("qdrant", QdrantPlugin.builder().addCollection(config()).build().getName());
  }

  @Test
  void requiresAtLeastOneCollection() {
    assertThrows(IllegalStateException.class, () -> QdrantPlugin.builder().build());
  }

  @Test
  void initWithoutRegistryThrows() {
    QdrantPlugin plugin = QdrantPlugin.builder().addCollection(config()).build();
    assertThrows(IllegalStateException.class, plugin::init);
  }
}
