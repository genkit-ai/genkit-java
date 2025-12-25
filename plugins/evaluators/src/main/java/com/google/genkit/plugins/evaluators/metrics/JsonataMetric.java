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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.evaluation.EvalDataPoint;
import com.google.genkit.ai.evaluation.EvalResponse;
import com.google.genkit.ai.evaluation.EvalStatus;
import com.google.genkit.ai.evaluation.Score;
import com.google.genkit.ai.evaluation.ScoreDetails;

/**
 * JSONata metric evaluator.
 * 
 * <p>
 * Evaluates the output using a JSONata expression provided in the reference.
 * The expression should return a truthy value for pass, falsy for fail.
 */
public class JsonataMetric {

  private static final Logger logger = LoggerFactory.getLogger(JsonataMetric.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public JsonataMetric() {
  }

  /**
   * Evaluates the output using the JSONata expression in the reference.
   *
   * @param dataPoint
   *            the evaluation data point
   * @return the evaluation response
   */
  public EvalResponse evaluate(EvalDataPoint dataPoint) {
    // Validate inputs
    if (dataPoint.getOutput() == null) {
      throw new IllegalArgumentException("Output was not provided");
    }
    if (dataPoint.getReference() == null) {
      throw new IllegalArgumentException("Reference (JSONata expression) was not provided");
    }

    String expression = PromptUtils.stringify(dataPoint.getReference());
    Object output = dataPoint.getOutput();

    try {
      // Convert output to a JSON-compatible structure
      Object jsonData = objectMapper.convertValue(output, Object.class);

      // Parse and evaluate the JSONata expression
      Jsonata jsonata = Jsonata.jsonata(expression);
      Object result = jsonata.evaluate(jsonData);

      boolean pass = isTruthy(result);
      double score = pass ? 1.0 : 0.0;
      EvalStatus status = pass ? EvalStatus.PASS : EvalStatus.FAIL;

      String reasoning = String.format("JSONata expression '%s' evaluated to: %s", expression,
          result != null ? result.toString() : "null");

      return EvalResponse
          .builder().testCaseId(dataPoint.getTestCaseId()).evaluation(Score.builder().score(score)
              .status(status).details(ScoreDetails.builder().reasoning(reasoning).build()).build())
          .build();

    } catch (Exception e) {
      logger.error("Error evaluating JSONata expression: {}", expression, e);
      return EvalResponse.builder().testCaseId(dataPoint.getTestCaseId()).evaluation(Score.builder().score(0.0)
          .status(EvalStatus.UNKNOWN).details(ScoreDetails.builder()
              .reasoning("Error evaluating JSONata expression: " + e.getMessage()).build())
          .build()).build();
    }
  }

  /**
   * Determines if a value is truthy. Follows JavaScript-like truthy rules.
   */
  private boolean isTruthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof Number) {
      double num = ((Number) value).doubleValue();
      return num != 0 && !Double.isNaN(num);
    }
    if (value instanceof String) {
      return !((String) value).isEmpty();
    }
    if (value instanceof java.util.Collection) {
      return !((java.util.Collection<?>) value).isEmpty();
    }
    if (value instanceof java.util.Map) {
      return !((java.util.Map<?, ?>) value).isEmpty();
    }
    // For other objects, consider them truthy
    return true;
  }
}
