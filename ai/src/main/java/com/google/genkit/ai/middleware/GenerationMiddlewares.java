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
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.SchemaUtils;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Factory helpers for building {@link GenerationMiddlewareDesc} descriptors.
 *
 * <p>These mirror the JS SDK's {@code generateMiddleware(...)} helper and the Go SDK's {@code
 * NewMiddleware(description, prototype)}: a descriptor pairs discovery metadata (name, description)
 * and a {@code configSchema} with a factory that binds config to a fresh {@link
 * GenerationMiddleware} hooks bundle.
 */
public final class GenerationMiddlewares {

  private GenerationMiddlewares() {}

  /**
   * Defines a parameterized middleware. The config JSON Schema is inferred from {@code configClass}
   * (via {@link SchemaUtils#inferSchema}), so the Dev UI can render a parameters form. At resolve
   * time the incoming config JSON is deserialized onto a fresh {@code configClass} instance
   * (missing fields keep the class's field defaults, mirroring JS/Go where defaults live in the
   * middleware), then handed to {@code factory}.
   *
   * @param name the unique middleware name
   * @param description a human-readable description
   * @param configClass the configuration POJO type (its fields are the parameters)
   * @param factory builds a fresh middleware from a bound config instance
   * @param <C> the configuration type
   * @return the descriptor
   */
  public static <C> GenerationMiddlewareDesc define(
      String name,
      String description,
      Class<C> configClass,
      Function<C, GenerationMiddleware> factory) {
    Map<String, Object> schema = SchemaUtils.inferSchema(configClass);
    return new GenerationMiddlewareDesc() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public Map<String, Object> configSchema() {
        return schema;
      }

      @Override
      public GenerationMiddleware instantiate(JsonNode config) throws GenkitException {
        C cfg =
            (config == null || config.isNull())
                ? newDefault(configClass)
                : JsonUtils.fromJsonNode(config, configClass);
        return factory.apply(cfg);
      }
    };
  }

  /**
   * Defines a parameterless middleware (no {@code configSchema}).
   *
   * @param name the unique middleware name
   * @param description a human-readable description
   * @param factory builds a fresh middleware instance
   * @return the descriptor
   */
  public static GenerationMiddlewareDesc define(
      String name, String description, Supplier<GenerationMiddleware> factory) {
    return new GenerationMiddlewareDesc() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public GenerationMiddleware instantiate(JsonNode config) {
        return factory.get();
      }
    };
  }

  /**
   * Wraps an already-instantiated {@link GenerationMiddleware} in a parameterless descriptor. Used
   * for backward compatibility so live middleware passed to {@code Genkit.Builder.middleware(...)}
   * or {@code GenerateOptions.use(...)} still shows up in the Dev UI (without a parameters form).
   *
   * @param mw the live middleware to wrap
   * @return the descriptor
   */
  public static GenerationMiddlewareDesc of(GenerationMiddleware mw) {
    return new GenerationMiddlewareDesc() {
      @Override
      public String name() {
        return mw.name();
      }

      @Override
      public GenerationMiddleware instantiate(JsonNode config) {
        return mw.newInstance();
      }
    };
  }

  private static <C> C newDefault(Class<C> configClass) throws GenkitException {
    try {
      return configClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new GenkitException(
          "Middleware config type "
              + configClass.getName()
              + " must have a public no-arg constructor",
          e);
    }
  }
}
