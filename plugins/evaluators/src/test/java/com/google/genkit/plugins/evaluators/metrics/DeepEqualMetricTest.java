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

class DeepEqualMetricTest {

  private final DeepEqualMetric metric = new DeepEqualMetric();

  private Score getScore(EvalResponse response) {
    return (Score) response.getEvaluation();
  }

  @Test
  void testEqualStrings() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-1").output("Hello World")
        .reference("Hello World").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals("test-1", response.getTestCaseId());
    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testDifferentStrings() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-2").output("Hello World")
        .reference("Goodbye World").build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.FAIL, score.getStatus());
  }

  @Test
  void testEqualNumbers() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-3").output(42).reference(42).build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testEqualLists() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-4").output(List.of("a", "b", "c"))
        .reference(List.of("a", "b", "c")).build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testDifferentLists() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-5").output(List.of("a", "b", "c"))
        .reference(List.of("a", "b", "d")).build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(0.0, score.getScore());
    assertEquals(EvalStatus.FAIL, score.getStatus());
  }

  @Test
  void testEqualMaps() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-6").output(Map.of("key", "value", "num", 42))
        .reference(Map.of("key", "value", "num", 42)).build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testNestedStructures() {
    Map<String, Object> nested = Map.of("name", "test", "values", List.of(1, 2, 3), "metadata",
        Map.of("active", true));

    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-7").output(nested).reference(nested).build();

    EvalResponse response = metric.evaluate(dataPoint);
    Score score = getScore(response);

    assertEquals(1.0, score.getScore());
    assertEquals(EvalStatus.PASS, score.getStatus());
  }

  @Test
  void testMissingOutput() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-8").reference("test").build();

    assertThrows(IllegalArgumentException.class, () -> metric.evaluate(dataPoint));
  }

  @Test
  void testMissingReference() {
    EvalDataPoint dataPoint = EvalDataPoint.builder().testCaseId("test-9").output("test").build();

    assertThrows(IllegalArgumentException.class, () -> metric.evaluate(dataPoint));
  }
}
