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

/** Represents a menu item with structured fields. */
public class MenuItem {
  @JsonProperty(required = true)
  @JsonPropertyDescription("The name of the menu item")
  private String name;

  @JsonPropertyDescription("A detailed description of the dish, including main ingredients")
  private String description;

  @JsonProperty(required = true)
  @JsonPropertyDescription("The price in USD")
  private double price;

  @JsonPropertyDescription("Estimated preparation time in minutes")
  private int prepTimeMinutes;

  @JsonPropertyDescription("Dietary information (e.g., vegetarian, vegan, gluten-free)")
  private List<String> dietaryInfo;

  // Constructors
  public MenuItem() {}

  public MenuItem(
      String name,
      String description,
      double price,
      int prepTimeMinutes,
      List<String> dietaryInfo) {
    this.name = name;
    this.description = description;
    this.price = price;
    this.prepTimeMinutes = prepTimeMinutes;
    this.dietaryInfo = dietaryInfo;
  }

  // Getters and setters
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public double getPrice() {
    return price;
  }

  public void setPrice(double price) {
    this.price = price;
  }

  public int getPrepTimeMinutes() {
    return prepTimeMinutes;
  }

  public void setPrepTimeMinutes(int prepTimeMinutes) {
    this.prepTimeMinutes = prepTimeMinutes;
  }

  public List<String> getDietaryInfo() {
    return dietaryInfo;
  }

  public void setDietaryInfo(List<String> dietaryInfo) {
    this.dietaryInfo = dietaryInfo;
  }

  @Override
  public String toString() {
    return String.format(
        "MenuItem{name='%s', description='%s', price=$%.2f, prepTime=%dmin, dietary=%s}",
        name, description, price, prepTimeMinutes, dietaryInfo);
  }
}
