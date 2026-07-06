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

import org.junit.jupiter.api.Test;

/** Unit tests for {@link MongoPlugin}. */
class MongoPluginTest {

  private static MongoVectorStoreConfig config() {
    return MongoVectorStoreConfig.builder().collectionName("films").embedderName("e").build();
  }

  @Test
  void getName() {
    MongoPlugin plugin =
        MongoPlugin.builder()
            .connectionString("mongodb://localhost:27017")
            .addCollection(config())
            .build();
    assertEquals("mongodb", plugin.getName());
  }

  @Test
  void requiresConnectionStringOrClient() {
    assertThrows(
        IllegalStateException.class, () -> MongoPlugin.builder().addCollection(config()).build());
  }

  @Test
  void requiresAtLeastOneCollection() {
    assertThrows(
        IllegalStateException.class,
        () -> MongoPlugin.builder().connectionString("mongodb://localhost:27017").build());
  }

  @Test
  void initWithoutRegistryThrows() {
    MongoPlugin plugin =
        MongoPlugin.builder()
            .connectionString("mongodb://localhost:27017")
            .addCollection(config())
            .build();
    assertThrows(IllegalStateException.class, plugin::init);
  }
}
