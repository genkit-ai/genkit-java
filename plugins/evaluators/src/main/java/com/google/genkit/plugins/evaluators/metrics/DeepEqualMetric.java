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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.evaluation.EvalDataPoint;
import com.google.genkit.ai.evaluation.EvalResponse;
import com.google.genkit.ai.evaluation.EvalStatus;
import com.google.genkit.ai.evaluation.Score;
import com.google.genkit.ai.evaluation.ScoreDetails;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deep Equal metric evaluator.
 *
 * <p>Evaluates whether the output is deeply equal to the reference. Supports JSON comparison for
 * structured data.
 */
public class DeepEqualMetric {

  private static final Logger logger = LoggerFactory.getLogger(DeepEqualMetric.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public DeepEqualMetric() {}

  /**
   * Evaluates whether the output is deeply equal to the reference.
   *
   * @param dataPoint the evaluation data point
   * @return the evaluation response
   */
  public EvalResponse evaluate(EvalDataPoint dataPoint) {
    // Validate inputs
    if (dataPoint.getOutput() == null) {
      throw new IllegalArgumentException("Output was not provided");
    }
    if (dataPoint.getReference() == null) {
      throw new IllegalArgumentException("Reference was not provided");
    }

    Object output = dataPoint.getOutput();
    Object reference = dataPoint.getReference();

    boolean isEqual;
    String reasoning;

    try {
      // Try JSON comparison first
      JsonNode outputNode = objectMapper.valueToTree(output);
      JsonNode referenceNode = objectMapper.valueToTree(reference);

      isEqual = outputNode.equals(referenceNode);

      if (isEqual) {
        reasoning = "Output is deeply equal to reference";
      } else {
        reasoning =
            String.format(
                "Output differs from reference.\nOutput: %s\nReference: %s",
                objectMapper.writeValueAsString(outputNode),
                objectMapper.writeValueAsString(referenceNode));
      }
    } catch (Exception e) {
      // Fall back to standard equality check
      isEqual = Objects.deepEquals(output, reference);

      if (isEqual) {
        reasoning = "Output is equal to reference (using Object.equals)";
      } else {
        reasoning =
            String.format(
                "Output differs from reference.\nOutput: %s\nReference: %s",
                output.toString(), reference.toString());
      }
    }

    double score = isEqual ? 1.0 : 0.0;
    EvalStatus status = isEqual ? EvalStatus.PASS : EvalStatus.FAIL;

    return EvalResponse.builder()
        .testCaseId(dataPoint.getTestCaseId())
        .evaluation(
            Score.builder()
                .score(score)
                .status(status)
                .details(ScoreDetails.builder().reasoning(reasoning).build())
                .build())
        .build();
  }
}
