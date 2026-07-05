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

import com.google.genkit.core.ActionDesc;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompatOAIModelTest {

  @Test
  void testModelCreation() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.test.com/v1")
            .build();

    CompatOAIModel model = new CompatOAIModel("test-provider/model-v1", "Test Model", options);

    assertNotNull(model);
  }

  @Test
  void testModelGetName() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.test.com/v1")
            .build();

    CompatOAIModel model = new CompatOAIModel("test-provider/model-v1", "Test Model", options);

    assertEquals("test-provider/model-v1", model.getName());
  }

  @Test
  void testModelWithCustomLabel() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.test.com/v1")
            .build();

    CompatOAIModel model = new CompatOAIModel("test-provider/model-v1", "Custom Label", options);

    assertNotNull(model);
    assertEquals("test-provider/model-v1", model.getName());
  }

  @Test
  void testModelWithSeparateApiModelName() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.test.com/v1")
            .build();

    CompatOAIModel model =
        new CompatOAIModel("test-provider/model-v1", "api-model-v1", "Test Model", options);

    assertNotNull(model);
    assertEquals("test-provider/model-v1", model.getName());
  }

  private static CompatOAIModel newModel() {
    CompatOAIPluginOptions options =
        CompatOAIPluginOptions.builder()
            .apiKey("test-key")
            .baseUrl("https://api.test.com/v1")
            .build();
    return new CompatOAIModel("test-provider/model-v1", "Test Model", options);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testModelInfoExposesCustomOptions() {
    // Regression test for #183: the model playground needs metadata.model.customOptions to render
    // configuration inputs.
    CompatOAIModel model = newModel();

    Map<String, Object> customOptions = model.getInfo().getCustomOptions();
    assertNotNull(customOptions, "customOptions schema should be present");
    assertEquals("object", customOptions.get("type"));

    Map<String, Object> properties = (Map<String, Object>) customOptions.get("properties");
    assertNotNull(properties, "customOptions should describe config properties");
    assertTrue(properties.containsKey("temperature"), "temperature should be configurable");
    assertTrue(properties.containsKey("topP"), "topP should be configurable");
    assertTrue(properties.containsKey("maxOutputTokens"), "maxOutputTokens should be configurable");
  }

  @Test
  void testModelActionHasInputAndOutputSchemas() {
    // Regression test for #183: model actions should expose input/output schemas.
    ActionDesc desc = newModel().getDesc();

    assertNotNull(desc.getInputSchema(), "model action should expose an input schema");
    assertNotNull(desc.getOutputSchema(), "model action should expose an output schema");
  }

  @Test
  void testCustomOptionsSurfacedInMetadata() {
    // The Dev UI reads customOptions from metadata.model.customOptions.
    CompatOAIModel model = newModel();

    Map<String, Object> metadata = model.getMetadata();
    assertTrue(metadata.get("model") instanceof com.google.genkit.ai.ModelInfo);
    com.google.genkit.ai.ModelInfo info = (com.google.genkit.ai.ModelInfo) metadata.get("model");
    assertNotNull(info.getCustomOptions());
  }
}
