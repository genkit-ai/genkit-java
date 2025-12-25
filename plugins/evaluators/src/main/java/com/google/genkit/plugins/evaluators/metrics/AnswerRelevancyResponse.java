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

package com.google.genkit.plugins.evaluators.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response schema for answer relevancy evaluation.
 */
public class AnswerRelevancyResponse {

  @JsonProperty("question")
  private String question;

  @JsonProperty("answered")
  private boolean answered;

  @JsonProperty("noncommittal")
  private boolean noncommittal;

  public AnswerRelevancyResponse() {
  }

  public AnswerRelevancyResponse(String question, boolean answered, boolean noncommittal) {
    this.question = question;
    this.answered = answered;
    this.noncommittal = noncommittal;
  }

  public String getQuestion() {
    return question;
  }

  public void setQuestion(String question) {
    this.question = question;
  }

  public boolean isAnswered() {
    return answered;
  }

  public void setAnswered(boolean answered) {
    this.answered = answered;
  }

  public boolean isNoncommittal() {
    return noncommittal;
  }

  public void setNoncommittal(boolean noncommittal) {
    this.noncommittal = noncommittal;
  }
}
