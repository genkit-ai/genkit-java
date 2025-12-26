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

import java.util.List;

/**
 * Request object for recipe generation with dietary preferences.
 */
public class RecipeRequest {
  @JsonProperty(required = true)
  @JsonPropertyDescription("The type of cuisine (e.g., Italian, Japanese, Mexican)")
  private String cuisine;

  @JsonPropertyDescription("Maximum preparation time in minutes")
  private Integer maxPrepTime;

  @JsonPropertyDescription("Dietary restrictions to consider")
  private List<String> dietaryRestrictions;

  public RecipeRequest() {}
  
  public RecipeRequest(String cuisine, Integer maxPrepTime, List<String> dietaryRestrictions) {
    this.cuisine = cuisine;
    this.maxPrepTime = maxPrepTime;
    this.dietaryRestrictions = dietaryRestrictions;
  }

  public String getCuisine() { return cuisine; }
  public void setCuisine(String cuisine) { this.cuisine = cuisine; }

  public Integer getMaxPrepTime() { return maxPrepTime; }
  public void setMaxPrepTime(Integer maxPrepTime) { this.maxPrepTime = maxPrepTime; }

  public List<String> getDietaryRestrictions() { return dietaryRestrictions; }
  public void setDietaryRestrictions(List<String> dietaryRestrictions) { 
    this.dietaryRestrictions = dietaryRestrictions; 
  }
}
