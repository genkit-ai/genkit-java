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

package com.google.genkit.plugins.localvec;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalVecPluginTest {

  @Test
  void testBuilderCreation() {
    LocalVecPlugin.Builder builder = LocalVecPlugin.builder();

    assertNotNull(builder);
  }

  @Test
  void testBuilderWithStore() {
    LocalVecConfig config = LocalVecConfig.builder().indexName("test-index").embedderName("test-embedder").build();

    LocalVecPlugin plugin = LocalVecPlugin.builder().addStore(config).build();

    assertNotNull(plugin);
    assertEquals("devLocalVectorStore", plugin.getName());
  }

  @Test
  void testGetName() {
    LocalVecConfig config = LocalVecConfig.builder().indexName("test-index").embedderName("test-embedder").build();

    LocalVecPlugin plugin = LocalVecPlugin.builder().addStore(config).build();

    assertEquals("devLocalVectorStore", plugin.getName());
  }

  @Test
  void testBuilderChaining() {
    LocalVecConfig config1 = LocalVecConfig.builder().indexName("index1").embedderName("embedder1").build();

    LocalVecConfig config2 = LocalVecConfig.builder().indexName("index2").embedderName("embedder2").build();

    LocalVecPlugin plugin = LocalVecPlugin.builder().addStore(config1).addStore(config2).build();

    assertNotNull(plugin);
  }
}
