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

package com.google.genkit.core;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Supplies the JSON schema of a type that describes itself, in place of the schema {@link
 * SchemaUtils} would infer from its fields.
 *
 * <p>A type opts in with {@link JsonSchemaProvider}. The provider is given the type as it is used,
 * with its type arguments resolved: a field declared as {@code Box<Integer>} is given a {@link
 * java.lang.reflect.ParameterizedType} whose raw type is {@code Box} and whose argument is {@code
 * Integer}, so a generic type can describe itself in terms of its arguments. A use without type
 * arguments, such as a non-generic subclass, is given the {@link Class} itself.
 *
 * <p>Implementations need a public no-argument constructor. The annotations of the property that
 * holds the type, such as {@code @JsonPropertyDescription}, are still applied to the returned
 * schema.
 */
@FunctionalInterface
public interface SchemaProvider {

  /**
   * Returns the JSON schema of the given type.
   *
   * @param type the type as it is used: a {@link Class}, or a {@link
   *     java.lang.reflect.ParameterizedType} when it is used with type arguments
   * @return the schema as a map, or null to fall back to the inferred schema
   */
  Map<String, Object> jsonSchema(Type type);
}
