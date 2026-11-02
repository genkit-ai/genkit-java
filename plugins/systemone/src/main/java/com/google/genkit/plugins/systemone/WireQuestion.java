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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One question as it goes on the wire: its kind, its instructions, and its criteria. Criteria are a
 * choice question's options in order, a noul question's map of side to description, or a score
 * question's ordered level descriptions; a description is a string, or the guidance that replaces
 * it on the wire.
 */
@JsonPropertyOrder({"type", "instructions", "criteria"})
final class WireQuestion {

  @JsonProperty("type")
  final String type;

  @JsonProperty("instructions")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  final Object instructions;

  @JsonProperty("criteria")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  final Object criteria;

  /** A score question's level strings, which the legend is built from. */
  @JsonIgnore final List<String> labels;

  WireQuestion(String type, Object instructions, Object criteria, List<String> labels) {
    this.type = type;
    this.instructions = instructions;
    this.criteria = criteria;
    this.labels = labels;
  }

  WireQuestion(String type, Object instructions, Object criteria) {
    this(type, instructions, criteria, null);
  }

  /** Each level number mapped to its label, the shape of a score's legend. */
  Map<String, Object> legend() {
    Map<String, Object> legend = new java.util.LinkedHashMap<>();
    for (int i = 0; i < labels.size(); i++) {
      legend.put(Integer.toString(i), labels.get(i));
    }
    return legend;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof WireQuestion other
        && Objects.equals(type, other.type)
        && Objects.equals(instructions, other.instructions)
        && Objects.equals(criteria, other.criteria)
        && Objects.equals(labels, other.labels);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, instructions, criteria, labels);
  }

  @Override
  public String toString() {
    return Wire.json(this);
  }

  /** One option of a choice question and its description on the wire. */
  record Option(String name, Object description) {}

  /**
   * A choice question's options with their descriptions, in the order they were declared. It
   * encodes as a JSON object whose keys keep that order.
   */
  @JsonSerialize(using = Options.Serializer.class)
  static final class Options {
    final List<Option> entries;

    Options(List<Option> entries) {
      this.entries = new ArrayList<>(entries);
    }

    static Options of(Option... entries) {
      return new Options(List.of(entries));
    }

    boolean contains(String name) {
      return entries.stream().anyMatch(o -> o.name().equals(name));
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Options other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
      return entries.hashCode();
    }

    @Override
    public String toString() {
      return Wire.json(this);
    }

    static final class Serializer extends StdSerializer<Options> {
      Serializer() {
        super(Options.class);
      }

      @Override
      public void serialize(Options value, JsonGenerator gen, SerializerProvider provider)
          throws IOException {
        gen.writeStartObject();
        for (Option entry : value.entries) {
          gen.writeFieldName(entry.name());
          provider.defaultSerializeValue(entry.description(), gen);
        }
        gen.writeEndObject();
      }
    }
  }
}
