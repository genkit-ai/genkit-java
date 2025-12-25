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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.genkit.ai.evaluation.EvalDataPoint;
import com.google.genkit.ai.evaluation.EvalResponse;
import com.google.genkit.ai.evaluation.EvalStatus;
import com.google.genkit.ai.evaluation.Score;

class JsonataMetricTest {

  private final JsonataMetric metric = new JsonataMetric();

  private Score getScore(EvalResponse response) {
    return (Score) response.getEvaluation();
  }

  @Test
  void testSimpleTruthyExpression() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-1").output(Map.of("status", "success"))
        .reference("status = 'success'").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals("test-1", response.getTestCaseId());
    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testSimpleFalsyExpression() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-2").output(Map.of("status", "error"))
        .reference("status = 'success'").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.FAIL, score.getStatus());
  }

  @Test
  void testNumericComparison() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-3").output(Map.of("count", 10))
        .reference("count > 5").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testArrayExpression() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-4")
        .output(Map.of("items", List.of("a", "b", "c"))).reference("$count(items) = 3").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testNestedPathExpression() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-5")
        .output(Map.of("user", Map.of("name", "John", "active", true))).reference("user.active").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testStringContains() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-6").output(Map.of("message", "Hello World"))
        .reference("$contains(message, 'World')").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testInvalidExpression() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-7").output(Map.of("test", "value"))
        .reference("$invalid(syntax...").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.UNKNOWN, score.getStatus());
    assertTrue(score.getDetails().getReasoning().contains("Error"));
  }

  @Test
  void testMissingOutput() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-8").reference("test = true").build();

    assertThrows(IllegalArgumentException.class, () -> metric.evaluate(dataPoint));
  }

  @Test
  void testMissingReference() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-9").output(Map.of("test", "value")).build();

    assertThrows(IllegalArgumentException.class, () -> metric.evaluate(dataPoint));
  }

  @Test
  void testEmptyStringIsFalsy() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-10").output(Map.of("name", ""))
        .reference("name").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.FAIL, score.getStatus());
  }

  @Test
  void testZeroIsFalsy() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-11").output(Map.of("count", 0))
        .reference("count").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.FAIL, score.getStatus());
  }
}
