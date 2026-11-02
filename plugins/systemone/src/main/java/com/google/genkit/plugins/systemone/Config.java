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

package com.google.genkit.plugins.systemone;

import com.google.genkit.ai.GenerationConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-request configuration of a decision model.
 *
 * <ul>
 *   <li>{@code stateJSON} parses each text part of the state as JSON, so a prompt template that
 *       renders a JSON document produces an object state rather than a string one. A text that is
 *       not JSON is an error. A fallback to another model takes the same request config, or send
 *       the state as a data part, which needs no parsing.
 *   <li>{@code extra} is merged into the top-level fields of the request body. It reaches fields
 *       the plugin does not model, such as a gateway's {@code session_id} or {@code trace}. It
 *       cannot name a field the request builds itself: model, state, questions, images, audio, or
 *       videos.
 * </ul>
 *
 * <pre>{@code
 * genkit.generate(GenerateOptions.builder()
 *     .model("typesafe/jev-1.13.0")
 *     .prompt(ticketJson)
 *     .config(Config.builder().stateJson(true).build().toGenerationConfig())
 *     .outputClass(Triage.class)
 *     .build());
 * }</pre>
 *
 * <p>In a prompt file, the same fields go under {@code config}: {@code stateJSON: true}.
 *
 * <p>Preview: this API may change in any minor release.
 */
public final class Config {

  /** The config key of {@link #stateJson()}. */
  public static final String STATE_JSON = "stateJSON";

  /** The config key of {@link #extra()}. */
  public static final String EXTRA = "extra";

  private final boolean stateJson;
  private final Map<String, Object> extra;

  private Config(boolean stateJson, Map<String, Object> extra) {
    this.stateJson = stateJson;
    this.extra = extra;
  }

  /**
   * Returns a builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns whether each text part of the state is parsed as JSON.
   *
   * @return true to parse text parts as JSON
   */
  public boolean stateJson() {
    return stateJson;
  }

  /**
   * Returns the extra top-level request fields.
   *
   * @return the extra fields, empty when there are none
   */
  public Map<String, Object> extra() {
    return extra == null ? Map.of() : Collections.unmodifiableMap(extra);
  }

  /**
   * Returns this config as the generation config a generate call takes.
   *
   * @return the generation config
   */
  public GenerationConfig toGenerationConfig() {
    Map<String, Object> custom = new LinkedHashMap<>();
    if (stateJson) {
      custom.put(STATE_JSON, true);
    }
    if (extra != null && !extra.isEmpty()) {
      custom.put(EXTRA, new LinkedHashMap<>(extra));
    }
    return GenerationConfig.builder().custom(custom).build();
  }

  /**
   * Reads the config of a model request. The fields are read at the top level, as a generate call
   * flattens them, or under {@code custom}.
   *
   * @param config the model request's config
   * @return the config
   */
  static Config from(Map<String, Object> config) {
    Map<String, Object> fields = new LinkedHashMap<>();
    if (config != null) {
      if (config.get("custom") instanceof Map<?, ?> custom) {
        custom.forEach((k, v) -> fields.put(String.valueOf(k), v));
      }
      fields.putAll(config);
    }
    Object stateJson = fields.get(STATE_JSON);
    boolean parse =
        stateJson instanceof Boolean b
            ? b
            : stateJson != null && "true".equals(stateJson.toString());
    Map<String, Object> extra = null;
    if (fields.get(EXTRA) instanceof Map<?, ?> map) {
      extra = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        extra.put(String.valueOf(e.getKey()), e.getValue());
      }
    }
    return new Config(parse, extra);
  }

  /** Builds a {@link Config}. */
  public static final class Builder {
    private boolean stateJson;
    private Map<String, Object> extra;

    private Builder() {}

    /**
     * Sets whether each text part of the state is parsed as JSON.
     *
     * @param stateJson true to parse text parts as JSON
     * @return this builder
     */
    public Builder stateJson(boolean stateJson) {
      this.stateJson = stateJson;
      return this;
    }

    /**
     * Sets the extra top-level request fields.
     *
     * @param extra the fields
     * @return this builder
     */
    public Builder extra(Map<String, Object> extra) {
      this.extra = extra == null ? null : new LinkedHashMap<>(extra);
      return this;
    }

    /**
     * Adds one extra top-level request field.
     *
     * @param name the field name
     * @param value the field value
     * @return this builder
     */
    public Builder extra(String name, Object value) {
      if (extra == null) {
        extra = new LinkedHashMap<>();
      }
      extra.put(name, value);
      return this;
    }

    /**
     * Builds the config.
     *
     * @return the config
     */
    public Config build() {
      return new Config(stateJson, extra);
    }
  }
}
