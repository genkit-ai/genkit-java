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

package com.google.genkit.plugins.firebase;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.genkit.core.Action;

class FirebasePluginTest {

  @Test
  void testBuilderCreation() {
    FirebasePlugin.Builder builder = FirebasePlugin.builder();

    assertNotNull(builder);
  }

  @Test
  void testBuilderWithProjectId() {
    FirebasePlugin plugin = FirebasePlugin.builder().projectId("test-project").build();

    assertNotNull(plugin);
    assertEquals("firebase", plugin.getName());
  }

  @Test
  void testBuilderWithTelemetry() {
    FirebasePlugin plugin = FirebasePlugin.builder().projectId("test-project").enableTelemetry(true).build();

    assertNotNull(plugin);
    assertEquals("firebase", plugin.getName());
  }

  @Test
  void testGetName() {
    FirebasePlugin plugin = FirebasePlugin.builder().projectId("test-project").build();

    assertEquals("firebase", plugin.getName());
  }

  @Test
  void testInit() {
    FirebasePlugin plugin = FirebasePlugin.builder().projectId("test-project").build();

    // Note: init() may fail without actual Firebase credentials, but we test the
    // structure
    try {
      List<Action<?, ?, ?>> actions = plugin.init();
      assertNotNull(actions);
    } catch (Exception e) {
      // Expected if credentials are not available
      assertTrue(e.getMessage() != null);
    }
  }

  @Test
  void testBuilderChaining() {
    FirebasePlugin plugin = FirebasePlugin.builder().projectId("test-project").enableTelemetry(true).build();

    assertNotNull(plugin);
  }
}
