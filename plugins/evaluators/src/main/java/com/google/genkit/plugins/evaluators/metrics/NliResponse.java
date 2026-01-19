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
import java.util.List;

/**
 * Response schema for NLI (Natural Language Inference) evaluation. Contains a list of statement
 * verdicts.
 */
public class NliResponse {

  @JsonProperty("responses")
  private List<NliResponseItem> responses;

  public NliResponse() {}

  public NliResponse(List<NliResponseItem> responses) {
    this.responses = responses;
  }

  public List<NliResponseItem> getResponses() {
    return responses;
  }

  public void setResponses(List<NliResponseItem> responses) {
    this.responses = responses;
  }
}
