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

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.ai.evaluation.EvalDataPoint;
import com.google.genkit.ai.evaluation.EvalResponse;
import com.google.genkit.ai.evaluation.EvalStatus;
import com.google.genkit.ai.evaluation.Score;
import org.junit.jupiter.api.Test;

class RegexMetricTest {

  private final RegexMetric metric = new RegexMetric();

  private Score getScore(EvalResponse response) {
    return (Score) response.getEvaluation();
  }

  @Test
  void testSimpleMatch() {
    EvalDataPoint dataPoint =
        EvalDataPoint.builder()
            .testCaseId("test-1")
            .output("Hello World")
            .reference("World")
            .build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals("test-1", response.getTestCaseId());
    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testNoMatch() {
    EvalDataPoint dataPoint =
        EvalDataPoint.builder()
            .testCaseId("test-2")
            .output("Hello World")
            .reference("Goodbye")
            .build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals("test-2", response.getTestCaseId());
    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.FAIL, score.getStatus());
  }

  @Test
  void testRegexPattern() {
    EvalDataPoint dataPoint =
        EvalDataPoint.builder()
            .testCaseId("test-3")
            .output("The answer is 42")
            .reference("\\d+")
            .build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testEmailPattern() {
    EvalDataPoint dataPoint =
        EvalDataPoint.builder()
            .testCaseId("test-4")
            .output("Contact us at test@example.com for more info")
            .reference("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
            .build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testInvalidRegex() {
    EvalDataPoint dataPoint =
        EvalDataPoint.builder().testCaseId("test-5").output("test").reference("[invalid").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.UNKNOWN, score.getStatus());
    assertTrue(score.getDetails().getReasoning().contains("Invalid regex"));
  }

  @Test
  void testMissingOutput() {
    EvalDataPoint dataPoint =
        EvalDataPoint.builder().testCaseId("test-6").reference("test").build();

    assertThrows(IllegalArgumentException.class, () -> metric.evaluate(dataPoint));
  }

  @Test
  void testMissingReference() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-7").output("test").build();

    assertThrows(IllegalArgumentException.class, () -> metric.evaluate(dataPoint));
  }
}
