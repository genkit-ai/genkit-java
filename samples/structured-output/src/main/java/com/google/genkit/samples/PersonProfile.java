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
 * Profile information for a person.
 */
public class PersonProfile {
  @JsonProperty(required = true)
  @JsonPropertyDescription("Full name of the person")
  private String fullName;

  @JsonProperty(required = true)
  @JsonPropertyDescription("Age in years")
  private int age;

  @JsonPropertyDescription("Email address")
  private String email;

  @JsonPropertyDescription("List of hobbies or interests")
  private List<String> interests;

  public PersonProfile() {}

  public String getFullName() { return fullName; }
  public void setFullName(String fullName) { this.fullName = fullName; }

  public int getAge() { return age; }
  public void setAge(int age) { this.age = age; }

  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }

  public List<String> getInterests() { return interests; }
  public void setInterests(List<String> interests) { this.interests = interests; }

  @Override
  public String toString() {
    return String.format("PersonProfile{name='%s', age=%d, email='%s', interests=%s}",
        fullName, age, email, interests);
  }
}
