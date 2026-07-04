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

package com.google.genkit.ai.middleware;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.core.GenkitException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A registered, self-describing, parameterized middleware factory.
 *
 * <p>This is the descriptor that gets stored in the registry's {@code "middleware"} value bucket
 * and surfaced to the Genkit Dev UI via the reflection API. It mirrors the JS SDK's {@code
 * GenerateMiddleware} descriptor (whose {@code toJson()} produces {@code MiddlewareDesc}) and the
 * Go SDK's {@code MiddlewareDesc}.
 *
 * <p>Unlike a bare {@link GenerationMiddleware} (which is the runtime <em>hooks</em> bundle), a
 * descriptor carries metadata for discovery and a {@link #configSchema()} that lets the Dev UI
 * render a parameters form. When the user selects a middleware in the Dev UI (optionally filling in
 * parameters), the selection is sent back as a {@code {name, config}} reference and resolved via
 * {@link #instantiate(JsonNode)}.
 *
 * <p>Build descriptors with {@link GenerationMiddlewares#define}. Plugins expose them via {@link
 * MiddlewarePlugin}.
 */
public interface GenerationMiddlewareDesc {

  /** Returns the middleware's unique name. Must equal the key it is registered under. */
  String name();

  /** Returns a human-readable description, or {@code null} if none. */
  default String description() {
    return null;
  }

  /**
   * Returns the JSON Schema describing this middleware's configuration parameters, or {@code null}
   * if the middleware takes no parameters. The Dev UI renders a form from this schema.
   */
  default Map<String, Object> configSchema() {
    return null;
  }

  /** Returns arbitrary metadata, or {@code null} if none. */
  default Map<String, Object> metadata() {
    return null;
  }

  /**
   * Instantiates a fresh {@link GenerationMiddleware} (hooks bundle) bound to the given
   * configuration.
   *
   * <p>Mirrors the JS {@code def.instantiate({config})} and Go {@code buildFromJSON(configJSON)}.
   * The {@code config} is applied opaquely — no server-side validation is performed; defaults live
   * in the middleware/config type. A fresh instance is returned per call so per-invocation state is
   * isolated.
   *
   * @param config the configuration as a JSON node (from the {@code use[].config} field), or {@code
   *     null}/JSON null when no configuration was supplied
   * @return a fresh middleware instance
   * @throws GenkitException if the configuration cannot be bound
   */
  GenerationMiddleware instantiate(JsonNode config) throws GenkitException;

  /**
   * Serializes this descriptor to the JSON shape expected by the reflection API / Dev UI: {@code
   * {name, description?, configSchema?, metadata?}} (null fields omitted). Matches the JS/Go {@code
   * MiddlewareDesc} wire shape.
   *
   * @return the serialized descriptor
   */
  default Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("name", name());
    if (description() != null) {
      json.put("description", description());
    }
    if (configSchema() != null) {
      json.put("configSchema", configSchema());
    }
    if (metadata() != null) {
      json.put("metadata", metadata());
    }
    return json;
  }
}
