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

/** ToolResume provides the parts needed to resume agent execution after a tool interrupt. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResume {

  @JsonProperty("respond")
  private List<Part> respond;

  @JsonProperty("restart")
  private List<Part> restart;

  /** Default constructor. */
  public ToolResume() {}

  private ToolResume(Builder builder) {
    this.respond = builder.respond;
    this.restart = builder.restart;
  }

  /**
   * Creates a builder for ToolResume.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the response parts to resume with.
   *
   * @return the respond parts
   */
  public List<Part> getRespond() {
    return respond;
  }

  /**
   * Sets the response parts.
   *
   * @param respond the respond parts
   */
  public void setRespond(List<Part> respond) {
    this.respond = respond != null ? new ArrayList<>(respond) : null;
  }

  /**
   * Returns the restart parts.
   *
   * @return the restart parts
   */
  public List<Part> getRestart() {
    return restart;
  }

  /**
   * Sets the restart parts.
   *
   * @param restart the restart parts
   */
  public void setRestart(List<Part> restart) {
    this.restart = restart != null ? new ArrayList<>(restart) : null;
  }

  /** Builder for ToolResume. */
  public static class Builder {
    private List<Part> respond;
    private List<Part> restart;

    public Builder respond(List<Part> respond) {
      this.respond = respond;
      return this;
    }

    public Builder restart(List<Part> restart) {
      this.restart = restart;
      return this;
    }

    public ToolResume build() {
      return new ToolResume(this);
    }
  }
}
