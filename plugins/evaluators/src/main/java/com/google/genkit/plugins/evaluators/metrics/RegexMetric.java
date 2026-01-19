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

import com.google.genkit.ai.evaluation.EvalDataPoint;
import com.google.genkit.ai.evaluation.EvalResponse;
import com.google.genkit.ai.evaluation.EvalStatus;
import com.google.genkit.ai.evaluation.Score;
import com.google.genkit.ai.evaluation.ScoreDetails;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regex metric evaluator.
 *
 * <p>Evaluates whether the output matches a regular expression pattern provided in the reference
 * field.
 */
public class RegexMetric {

  private static final Logger logger = LoggerFactory.getLogger(RegexMetric.class);

  public RegexMetric() {}

  /**
   * Evaluates whether the output matches the regex pattern in the reference.
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
      throw new IllegalArgumentException("Reference (regex pattern) was not provided");
    }

    String output = PromptUtils.stringify(dataPoint.getOutput());
    String regexPattern = PromptUtils.stringify(dataPoint.getReference());

    boolean matches;
    String reasoning;

    try {
      Pattern pattern = Pattern.compile(regexPattern);
      Matcher matcher = pattern.matcher(output);
      matches = matcher.find();

      if (matches) {
        reasoning =
            String.format(
                "Output matches regex pattern '%s' at position %d-%d: '%s'",
                regexPattern, matcher.start(), matcher.end(), matcher.group());
      } else {
        reasoning = String.format("Output does not match regex pattern '%s'", regexPattern);
      }
    } catch (Exception e) {
      logger.error("Invalid regex pattern: {}", regexPattern, e);
      return EvalResponse.builder()
          .testCaseId(dataPoint.getTestCaseId())
          .evaluation(
              Score.builder()
                  .score(0.0)
                  .status(EvalStatus.UNKNOWN)
                  .details(
                      ScoreDetails.builder()
                          .reasoning("Invalid regex pattern: " + e.getMessage())
                          .build())
                  .build())
          .build();
    }

    double score = matches ? 1.0 : 0.0;
    EvalStatus status = matches ? EvalStatus.PASS : EvalStatus.FAIL;

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
