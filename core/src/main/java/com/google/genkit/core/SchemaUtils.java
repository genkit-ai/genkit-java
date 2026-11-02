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

package com.google.genkit.core;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** SchemaUtils provides utilities for JSON Schema generation and validation. */
public final class SchemaUtils {

  private static final SchemaGenerator schemaGenerator;

  /** The providers named by {@link JsonSchemaProvider}, one instance per class. */
  private static final Map<Class<? extends SchemaProvider>, SchemaProvider> providers =
      new ConcurrentHashMap<>();

  static {
    SchemaGeneratorConfigBuilder configBuilder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);

    configBuilder.with(Option.EXTRA_OPEN_API_FORMAT_VALUES);
    configBuilder.with(Option.FLATTENED_ENUMS);
    // Note: Removed NULLABLE_FIELDS_BY_DEFAULT as it generates "type": ["string",
    // "null"]
    // for all fields, which causes issues with the Genkit UI input form generation.
    // Fields should be explicitly marked as nullable using @Nullable annotation if
    // needed.

    // Add Jackson module to support @JsonPropertyDescription and other Jackson
    // annotations
    JacksonModule jacksonModule = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
    configBuilder.with(jacksonModule);

    // Mark non-optional fields as "required" (mirrors Zod/JS behavior in the JS/Go SDKs). This is
    // what lets the Dev UI build a default input skeleton: its flow/prompt runner only pre-fills
    // properties that appear in the schema's "required" array. A field is treated as optional only
    // when it is an Optional<> or annotated with a @Nullable annotation.
    configBuilder
        .forFields()
        .withRequiredCheck(
            field -> {
              if (field.getType() != null
                  && field.getType().getErasedType() == java.util.Optional.class) {
                return false;
              }
              for (java.lang.annotation.Annotation annotation :
                  field.getRawMember().getAnnotations()) {
                if ("Nullable".equals(annotation.annotationType().getSimpleName())) {
                  return false;
                }
              }
              return true;
            });

    // A type annotated with @JsonSchemaProvider supplies its own schema, given the type as it is
    // used. Only annotated types are affected; every other type keeps the inferred schema. The
    // property's own attributes, such as its description, still apply.
    configBuilder.forTypesInGeneral().withCustomDefinitionProvider(SchemaUtils::providedDefinition);

    SchemaGeneratorConfig config = configBuilder.build();
    schemaGenerator = new SchemaGenerator(config);
  }

  private SchemaUtils() {
    // Utility class
  }

  /**
   * The schema of a type annotated with {@link JsonSchemaProvider}, from its provider, or null for
   * any other type.
   */
  private static CustomDefinition providedDefinition(
      ResolvedType type, SchemaGenerationContext context) {
    JsonSchemaProvider annotation = type.getErasedType().getAnnotation(JsonSchemaProvider.class);
    if (annotation == null) {
      return null;
    }
    SchemaProvider provider =
        providers.computeIfAbsent(annotation.value(), SchemaUtils::newProvider);
    Map<String, Object> schema = provider.jsonSchema(toType(type));
    if (schema == null) {
      return null;
    }
    ObjectNode node = context.getGeneratorConfig().getObjectMapper().valueToTree(schema);
    return new CustomDefinition(
        node, CustomDefinition.DefinitionType.INLINE, CustomDefinition.AttributeInclusion.YES);
  }

  private static SchemaProvider newProvider(Class<? extends SchemaProvider> providerClass) {
    try {
      java.lang.reflect.Constructor<? extends SchemaProvider> constructor =
          providerClass.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "schema provider " + providerClass.getName() + " needs a no-argument constructor", e);
    }
  }

  /** The reflection type of a resolved type, with its type arguments. */
  private static Type toType(ResolvedType type) {
    List<ResolvedType> params = type.getTypeParameters();
    if (params.isEmpty()) {
      return type.getErasedType();
    }
    Type[] args = new Type[params.size()];
    for (int i = 0; i < args.length; i++) {
      args[i] = toType(params.get(i));
    }
    return new ResolvedParameterizedType(type.getErasedType(), args);
  }

  /** A parameterized type built from a resolved one. */
  private static final class ResolvedParameterizedType implements ParameterizedType {
    private final Class<?> raw;
    private final Type[] args;

    ResolvedParameterizedType(Class<?> raw, Type[] args) {
      this.raw = raw;
      this.args = args;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return args.clone();
    }

    @Override
    public Type getRawType() {
      return raw;
    }

    @Override
    public Type getOwnerType() {
      return raw.getDeclaringClass();
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ParameterizedType)) {
        return false;
      }
      ParameterizedType other = (ParameterizedType) o;
      return raw.equals(other.getRawType())
          && Arrays.equals(args, other.getActualTypeArguments())
          && Objects.equals(getOwnerType(), other.getOwnerType());
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(args) ^ raw.hashCode();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(raw.getTypeName()).append('<');
      for (int i = 0; i < args.length; i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(args[i].getTypeName());
      }
      return sb.append('>').toString();
    }
  }

  /**
   * Generates a JSON Schema for the given class.
   *
   * @param clazz the class to generate schema for
   * @return the JSON schema as a map
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> inferSchema(Class<?> clazz) {
    if (clazz == null || clazz == Void.class || clazz == void.class) {
      return null;
    }

    try {
      JsonNode schemaNode = schemaGenerator.generateSchema(clazz);
      return JsonUtils.getObjectMapper().convertValue(schemaNode, Map.class);
    } catch (Exception e) {
      // If schema generation fails, return a simple object schema
      return Map.of("type", "object");
    }
  }

  /**
   * Generates a JSON Schema for a primitive type.
   *
   * @param typeName the type name (string, number, integer, boolean, array, object)
   * @return the JSON schema as a map
   */
  public static Map<String, Object> simpleSchema(String typeName) {
    return Map.of("type", typeName);
  }

  /**
   * Creates a schema for a string type.
   *
   * @return the string schema
   */
  public static Map<String, Object> stringSchema() {
    return simpleSchema("string");
  }

  /**
   * Creates a schema for an integer type.
   *
   * @return the integer schema
   */
  public static Map<String, Object> integerSchema() {
    return simpleSchema("integer");
  }

  /**
   * Creates a schema for a number type.
   *
   * @return the number schema
   */
  public static Map<String, Object> numberSchema() {
    return simpleSchema("number");
  }

  /**
   * Creates a schema for a boolean type.
   *
   * @return the boolean schema
   */
  public static Map<String, Object> booleanSchema() {
    return simpleSchema("boolean");
  }

  /**
   * Creates a schema for an array type with the given items schema.
   *
   * @param itemsSchema the schema for array items
   * @return the array schema
   */
  public static Map<String, Object> arraySchema(Map<String, Object> itemsSchema) {
    return Map.of("type", "array", "items", itemsSchema);
  }

  /**
   * Creates a schema for an object type with the given properties.
   *
   * @param properties the property schemas
   * @return the object schema
   */
  public static Map<String, Object> objectSchema(Map<String, Object> properties) {
    return Map.of("type", "object", "properties", properties);
  }
}
