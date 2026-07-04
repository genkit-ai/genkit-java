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

package com.google.genkit.ai.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.genkit.ai.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Artifact represents a named collection of content parts produced during agent execution. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Artifact {

  @JsonProperty("name")
  private String name;

  /** parts is always present on the wire (required field). */
  @JsonProperty("parts")
  private List<Part> parts = new ArrayList<>();

  @JsonProperty("metadata")
  private Map<String, Object> metadata;

  /** Default constructor. */
  public Artifact() {}

  private Artifact(Builder builder) {
    this.name = builder.name;
    this.parts = builder.parts != null ? new ArrayList<>(builder.parts) : new ArrayList<>();
    this.metadata = builder.metadata;
  }

  /**
   * Creates a builder for Artifact.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the artifact name.
   *
   * @return the name
   */
  public String getName() {
    return name;
  }

  /**
   * Sets the artifact name.
   *
   * @param name the name
   */
  public void setName(String name) {
    this.name = name;
  }

  /**
   * Returns the artifact parts.
   *
   * @return the parts (never null)
   */
  public List<Part> getParts() {
    return parts;
  }

  /**
   * Sets the artifact parts.
   *
   * @param parts the parts
   */
  public void setParts(List<Part> parts) {
    this.parts = parts != null ? new ArrayList<>(parts) : new ArrayList<>();
  }

  /**
   * Returns the artifact metadata.
   *
   * @return the metadata, or null if not set
   */
  public Map<String, Object> getMetadata() {
    return metadata;
  }

  /**
   * Sets the artifact metadata.
   *
   * @param metadata the metadata
   */
  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }

  /** Builder for Artifact. */
  public static class Builder {
    private String name;
    private List<Part> parts;
    private Map<String, Object> metadata;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder parts(List<Part> parts) {
      this.parts = parts;
      return this;
    }

    public Builder metadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    public Artifact build() {
      return new Artifact(this);
    }
  }
}
