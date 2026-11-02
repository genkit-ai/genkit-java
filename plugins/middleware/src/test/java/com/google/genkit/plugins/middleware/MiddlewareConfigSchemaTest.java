/*
 * Copyright 2026 Google LLC
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

package com.google.genkit.plugins.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddlewareDesc;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Checks the descriptors the Dev UI renders and dispatches by name. */
class MiddlewareConfigSchemaTest {

  private static Map<String, GenerationMiddlewareDesc> descriptors() {
    return GenerationMiddlewarePlugin.create().middlewares(new DefaultRegistry()).stream()
        .collect(Collectors.toMap(GenerationMiddlewareDesc::name, d -> d));
  }

  @Test
  void registersUnderTheProviderPrefix() {
    Map<String, GenerationMiddlewareDesc> byName = descriptors();
    for (String name :
        List.of(
            "genkit-middleware/toolApproval",
            "genkit-middleware/softToolErrors",
            "genkit-middleware/skills",
            "genkit-middleware/filesystem")) {
      assertTrue(byName.containsKey(name), "missing " + name);
    }
  }

  /**
   * Every field the Dev UI renders for these middleware configs carries a description, so none
   * shows up as a bare input box.
   */
  @Test
  @SuppressWarnings("unchecked")
  void configSchemasDocumented() {
    for (String name :
        List.of(ToolApproval.NAME, SoftToolErrors.NAME, Skills.NAME, Filesystem.NAME)) {
      GenerationMiddlewareDesc d = descriptors().get(name);
      assertNotNull(d.description(), name + " has no description");
      assertFalse(d.description().isEmpty());
      Map<String, Object> schema = d.configSchema();
      assertNotNull(schema, name + " has no config schema");
      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertNotNull(props, name);
      for (Map.Entry<String, Object> p : props.entrySet()) {
        Object desc = ((Map<String, Object>) p.getValue()).get("description");
        assertTrue(
            desc instanceof String && !((String) desc).isEmpty(),
            name + "." + p.getKey() + " has no description");
      }
    }
  }

  /**
   * Each dispatch builds its own config: a field one call sets does not stay set for the calls
   * after it.
   */
  @Test
  void jsonDispatchDoesNotLeakConfigBetweenCalls() {
    GenerationMiddlewareDesc d = descriptors().get(ToolApproval.NAME);
    GenerationMiddleware first =
        d.instantiate(
            JsonUtils.toJsonNode(
                Map.of(
                    "allowedTools", List.of("a"),
                    "judge", "test/judge",
                    "judgePolicy", "be careful")));
    ToolApproval.Options firstOptions = ((ToolApproval) first).getOptions();
    assertEquals(List.of("a"), firstOptions.allowedTools);
    assertEquals("test/judge", firstOptions.judge.name);

    GenerationMiddleware second = d.instantiate(JsonUtils.toJsonNode(Map.of()));
    ToolApproval.Options secondOptions = ((ToolApproval) second).getOptions();
    assertTrue(secondOptions.allowedTools.isEmpty(), "allowedTools leaked between dispatches");
    assertEquals(null, secondOptions.judge);
    assertEquals(null, secondOptions.judgePolicy);
  }

  @Test
  void judgeAcceptsTheObjectForm() {
    GenerationMiddleware mw =
        descriptors()
            .get(ToolApproval.NAME)
            .instantiate(
                JsonUtils.toJsonNode(
                    Map.of(
                        "judge",
                        Map.of("name", "test/judge", "config", Map.of("temperature", 0)))));
    ToolApproval ta = assertInstanceOf(ToolApproval.class, mw);
    assertEquals("test/judge", ta.getOptions().judge.name);
    assertEquals(Map.of("temperature", 0), ta.getOptions().judge.config);
  }

  @Test
  void softToolErrorsBindsTools() {
    GenerationMiddleware mw =
        descriptors()
            .get(SoftToolErrors.NAME)
            .instantiate(JsonUtils.toJsonNode(Map.of("tools", List.of("a"))));
    SoftToolErrors s = assertInstanceOf(SoftToolErrors.class, mw);
    assertTrue(s.coversToolErrors("a"));
    assertFalse(s.coversToolErrors("b"));
  }
}
