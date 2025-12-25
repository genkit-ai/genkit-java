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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response schema for the long-form answer extraction in faithfulness
 * evaluation. Extracts individual statements from a generated answer.
 */
public class LongFormResponse {

  @JsonProperty("statements")
  private List<String> statements;

  public LongFormResponse() {
  }

  public LongFormResponse(List<String> statements) {
    this.statements = statements;
  }

  public List<String> getStatements() {
    return statements;
  }

  public void setStatements(List<String> statements) {
    this.statements = statements;
  }
}
