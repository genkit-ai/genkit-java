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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.core.SchemaUtils;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The decision types the tests share: one question of each kind. */
final class TestTypes {

  private TestTypes() {}

  enum Dept implements SystemOne.Option {
    @JsonProperty("billing")
    BILLING("Payments, invoicing, refunds"),
    @JsonProperty("technical")
    TECHNICAL("Bugs, outages, integrations"),
    @JsonProperty("other")
    OTHER("None of the above");

    private final String criteria;

    Dept(String criteria) {
      this.criteria = criteria;
    }

    @Override
    public String criteria() {
      return criteria;
    }
  }

  enum Anger implements SystemOne.Level {
    CALM("Calm"),
    CONCERNED("Concerned but civil"),
    VERY_ANGRY("Very angry");

    private final String description;

    Anger(String description) {
      this.description = description;
    }

    @Override
    public String description() {
      return description;
    }
  }

  static final class Urgent implements SystemOne.YesNo {
    @Override
    public String yes() {
      return "Explicitly time-sensitive";
    }

    @Override
    public String no() {
      return "No urgency expressed";
    }
  }

  record Triage(
      @JsonProperty("department") @JsonPropertyDescription("Which team should handle this?")
          Choice<Dept> department,
      @JsonProperty("is_urgent")
          @JsonPropertyDescription("Does the ticket explicitly communicate time pressure?")
          NoulOf<Urgent> isUrgent,
      @JsonProperty("frustration") @JsonPropertyDescription("How frustrated is the customer?")
          Score<Anger> frustration) {}

  /** What the triage schema compiles to. */
  static Map<String, WireQuestion> triageQuestions() {
    Map<String, WireQuestion> q = new TreeMap<>();
    q.put(
        "department",
        new WireQuestion(
            Wire.KIND_CHOICE,
            "Which team should handle this?",
            WireQuestion.Options.of(
                new WireQuestion.Option("billing", "Payments, invoicing, refunds"),
                new WireQuestion.Option("other", "None of the above"),
                new WireQuestion.Option("technical", "Bugs, outages, integrations"))));
    q.put(
        "is_urgent",
        new WireQuestion(
            Wire.KIND_NOUL,
            "Does the ticket explicitly communicate time pressure?",
            new TreeMap<>(
                Map.of("true", "Explicitly time-sensitive", "false", "No urgency expressed"))));
    List<String> levels = List.of("Calm", "Concerned but civil", "Very angry");
    q.put(
        "frustration",
        new WireQuestion(
            Wire.KIND_SCORE, "How frustrated is the customer?", List.copyOf(levels), levels));
    return q;
  }

  /** A calibrated answer set, as the API returns it. */
  static final String TRIAGE_ANSWERS =
      "{"
          + "\"department\": {\"type\": \"choice\", \"choice\": \"billing\", \"probabilities\":"
          + " {\"billing\": 0.84, \"technical\": 0.15, \"other\": 0.01}, \"confidence\": 0.6},"
          + "\"is_urgent\": {\"type\": \"noul\", \"noul\": 0.93},"
          + "\"frustration\": {\"type\": \"score\", \"score\": 1.3, \"legend\": {\"0\": \"Calm\","
          + " \"1\": \"Concerned but civil\", \"2\": \"Very angry\"}, \"probabilities\": {\"0\": 0,"
          + " \"1\": 0.7, \"2\": 0.3}, \"confidence\": 0.54}"
          + "}";

  static Map<String, Object> triageSchema() {
    return SchemaUtils.inferSchema(Triage.class);
  }

  // The guided decision type: one question of each kind with structured guidance on some keys,
  // and plain strings on the rest.

  enum GuidedDept implements SystemOne.Option {
    @JsonProperty("billing")
    BILLING(
        "Payments, invoicing, refunds",
        Map.of("not_for", "Where an order is", "examples", List.of("I was charged twice."))),
    @JsonProperty("technical")
    TECHNICAL(
        "Bugs, outages, integrations",
        Map.of("what", "Anything that is broken", "examples", List.of("The app crashes."))),
    @JsonProperty("other")
    OTHER("None of the above", null);

    private final String criteria;
    private final Object guidance;

    GuidedDept(String criteria, Object guidance) {
      this.criteria = criteria;
      this.guidance = guidance;
    }

    @Override
    public String criteria() {
      return criteria;
    }

    @Override
    public Object guidance() {
      return guidance;
    }
  }

  enum GuidedAnger implements SystemOne.Level {
    CALM("Calm", null),
    CONCERNED("Concerned but civil", null),
    VERY_ANGRY("Very angry", Map.of("signals", List.of("threats", "all caps")));

    private final String description;
    private final Object guidance;

    GuidedAnger(String description, Object guidance) {
      this.description = description;
      this.guidance = guidance;
    }

    @Override
    public String description() {
      return description;
    }

    @Override
    public Object guidance() {
      return guidance;
    }
  }

  static final class GuidedUrgent implements SystemOne.YesNo {
    @Override
    public String yes() {
      return "Explicitly time-sensitive";
    }

    @Override
    public String no() {
      return "No urgency expressed";
    }

    @Override
    public Object yesGuidance() {
      return Map.of("examples", List.of("today", "by Friday"));
    }
  }

  record GuidedTriage(
      @JsonProperty("department") @JsonPropertyDescription("Which team should handle this?")
          Choice<GuidedDept> department,
      @JsonProperty("is_urgent")
          @JsonPropertyDescription("Does the ticket explicitly communicate time pressure?")
          NoulOf<GuidedUrgent> isUrgent,
      @JsonProperty("frustration") @JsonPropertyDescription("How frustrated is the customer?")
          Score<GuidedAnger> frustration) {}
}
