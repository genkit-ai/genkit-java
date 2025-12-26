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

package com.google.genkit.samples;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for requesting a dish by cuisine.
 */
public class DishRequest {

  @JsonProperty(required = true)
  @JsonPropertyDescription("The cuisine type (e.g., Italian, French, Japanese)")
  private String cuisine;

  @JsonPropertyDescription("Optional dietary restriction (e.g., vegetarian, gluten-free)")
  private String dietary;

  public DishRequest() {
  }

  public DishRequest(String cuisine) {
    this.cuisine = cuisine;
  }

  public DishRequest(String cuisine, String dietary) {
    this.cuisine = cuisine;
    this.dietary = dietary;
  }

  public String getCuisine() {
    return cuisine;
  }

  public void setCuisine(String cuisine) {
    this.cuisine = cuisine;
  }

  public String getDietary() {
    return dietary;
  }

  public void setDietary(String dietary) {
    this.dietary = dietary;
  }
}
