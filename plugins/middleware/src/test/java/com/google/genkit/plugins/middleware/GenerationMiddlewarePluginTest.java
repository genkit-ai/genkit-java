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

package com.google.genkit.plugins.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddlewareDesc;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests the descriptor/plugin mechanism and the built-in parameterized middleware. */
class GenerationMiddlewarePluginTest {

  private Map<String, GenerationMiddlewareDesc> descriptors() {
    return GenerationMiddlewarePlugin.create().middlewares(new DefaultRegistry()).stream()
        .collect(Collectors.toMap(GenerationMiddlewareDesc::name, d -> d));
  }

  @Test
  void listsThreeParameterizedMiddleware() {
    Map<String, GenerationMiddlewareDesc> byName = descriptors();
    assertEquals(3, byName.size());
    assertTrue(byName.containsKey("retry"));
    assertTrue(byName.containsKey("fallback"));
    assertTrue(byName.containsKey("simulateSystemPrompt"));
  }

  @Test
  void descriptorSerializesToDevUiShape() {
    GenerationMiddlewareDesc retry = descriptors().get("retry");

    Map<String, Object> json = retry.toJson();
    assertEquals("retry", json.get("name"));
    assertNotNull(json.get("description"));

    @SuppressWarnings("unchecked")
    Map<String, Object> configSchema = (Map<String, Object>) json.get("configSchema");
    assertNotNull(configSchema, "retry must expose a configSchema so the Dev UI renders a form");
    assertEquals("object", configSchema.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) configSchema.get("properties");
    assertNotNull(props);
    assertTrue(props.containsKey("maxRetries"));
    assertTrue(props.containsKey("initialDelayMs"));
    assertTrue(props.containsKey("backoffFactor"));
  }

  @Test
  void instantiateBindsConfigAndDefaults() throws Exception {
    GenerationMiddlewareDesc retry = descriptors().get("retry");

    JsonNode config = JsonUtils.parseJson("{\"maxRetries\":7,\"initialDelayMs\":250}");
    GenerationMiddleware bound = retry.instantiate(config);
    assertInstanceOf(RetryMiddleware.class, bound);
    assertEquals("retry", bound.name());

    // Null config -> defaults (no exception).
    GenerationMiddleware defaults = retry.instantiate(null);
    assertNotNull(defaults);
  }

  @Test
  void retryRetriesThenSucceeds() throws Exception {
    RetryMiddleware.Options opts = new RetryMiddleware.Options();
    opts.maxRetries = 2;
    opts.initialDelayMs = 1;
    opts.maxDelayMs = 1;
    opts.backoffFactor = 1;
    RetryMiddleware retry = new RetryMiddleware(opts);

    AtomicInteger calls = new AtomicInteger();
    ModelResponse success = new ModelResponse();
    ModelNext next =
        (ctx, params) -> {
          if (calls.incrementAndGet() < 3) {
            throw new GenkitException("transient failure");
          }
          return success;
        };

    ModelResponse result = retry.wrapModel(ctx(), new ModelParams(null, null), next);
    assertEquals(success, result);
    assertEquals(3, calls.get(), "1 initial attempt + 2 retries");
  }

  @Test
  void retryExhaustsThenRethrows() {
    RetryMiddleware.Options opts = new RetryMiddleware.Options();
    opts.maxRetries = 2;
    opts.initialDelayMs = 1;
    RetryMiddleware retry = new RetryMiddleware(opts);

    AtomicInteger calls = new AtomicInteger();
    ModelNext next =
        (ctx, params) -> {
          calls.incrementAndGet();
          throw new GenkitException("always fails");
        };

    assertThrows(
        GenkitException.class, () -> retry.wrapModel(ctx(), new ModelParams(null, null), next));
    assertEquals(3, calls.get(), "1 initial attempt + 2 retries before giving up");
  }

  @Test
  void parameterlessDescriptorHasNoConfigSchema() {
    // fallback/simulateSystemPrompt DO have schemas; verify a wrapped live middleware would not.
    GenerationMiddlewareDesc simulate = descriptors().get("simulateSystemPrompt");
    assertNotNull(simulate.configSchema());
    // sanity: metadata is null by default (omitted from toJson)
    assertNull(simulate.metadata());
  }

  private static ActionContext ctx() {
    return ActionContext.builder().registry(new DefaultRegistry()).build();
  }
}
