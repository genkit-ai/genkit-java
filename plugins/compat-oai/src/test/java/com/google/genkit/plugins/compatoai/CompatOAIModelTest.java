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

package com.google.genkit.plugins.compatoai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompatOAIModelTest {

  @Test
  void testModelCreation() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").build();

    CompatOAIModel model = new CompatOAIModel("test-provider/model-v1", "Test Model", options);

    assertNotNull(model);
  }

  @Test
  void testModelGetName() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").build();

    CompatOAIModel model = new CompatOAIModel("test-provider/model-v1", "Test Model", options);

    assertEquals("test-provider/model-v1", model.getName());
  }

  @Test
  void testModelWithCustomLabel() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").build();

    CompatOAIModel model = new CompatOAIModel("test-provider/model-v1", "Custom Label", options);

    assertNotNull(model);
    assertEquals("test-provider/model-v1", model.getName());
  }

  @Test
  void testModelWithSeparateApiModelName() {
    CompatOAIPluginOptions options = CompatOAIPluginOptions.builder().apiKey("test-key")
        .baseUrl("https://api.test.com/v1").build();

    CompatOAIModel model = new CompatOAIModel("test-provider/model-v1", "api-model-v1", "Test Model", options);

    assertNotNull(model);
    assertEquals("test-provider/model-v1", model.getName());
  }
}
