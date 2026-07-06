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

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ChromaPlugin}. */
class ChromaPluginTest {

  private static ChromaCollectionConfig config() {
    return ChromaCollectionConfig.builder().collectionName("films").embedderName("e").build();
  }

  @Test
  void getName() {
    assertEquals("chroma", ChromaPlugin.builder().addCollection(config()).build().getName());
  }

  @Test
  void builderDefaults() {
    // A valid plugin builds with default url/tenant/database.
    ChromaPlugin plugin = ChromaPlugin.builder().addCollection(config()).build();
    assertEquals("chroma", plugin.getName());
  }

  @Test
  void requiresAtLeastOneCollection() {
    assertThrows(IllegalStateException.class, () -> ChromaPlugin.builder().build());
  }

  @Test
  void initWithoutRegistryThrows() {
    ChromaPlugin plugin = ChromaPlugin.builder().addCollection(config()).build();
    assertThrows(IllegalStateException.class, plugin::init);
  }
}
