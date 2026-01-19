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

package com.google.genkit.plugins.evaluators;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GenkitMetricTest {

  @Test
  void testRequiresJudge() {
    assertTrue(GenkitMetric.FAITHFULNESS.requiresJudge());
    assertTrue(GenkitMetric.ANSWER_RELEVANCY.requiresJudge());
    assertTrue(GenkitMetric.ANSWER_ACCURACY.requiresJudge());
    assertTrue(GenkitMetric.MALICIOUSNESS.requiresJudge());
    assertFalse(GenkitMetric.REGEX.requiresJudge());
    assertFalse(GenkitMetric.DEEP_EQUAL.requiresJudge());
    assertFalse(GenkitMetric.JSONATA.requiresJudge());
  }

  @Test
  void testRequiresEmbedder() {
    assertFalse(GenkitMetric.FAITHFULNESS.requiresEmbedder());
    assertTrue(GenkitMetric.ANSWER_RELEVANCY.requiresEmbedder());
    assertFalse(GenkitMetric.ANSWER_ACCURACY.requiresEmbedder());
    assertFalse(GenkitMetric.MALICIOUSNESS.requiresEmbedder());
    assertFalse(GenkitMetric.REGEX.requiresEmbedder());
    assertFalse(GenkitMetric.DEEP_EQUAL.requiresEmbedder());
    assertFalse(GenkitMetric.JSONATA.requiresEmbedder());
  }

  @Test
  void testIsBilled() {
    assertTrue(GenkitMetric.FAITHFULNESS.isBilled());
    assertTrue(GenkitMetric.ANSWER_RELEVANCY.isBilled());
    assertTrue(GenkitMetric.ANSWER_ACCURACY.isBilled());
    assertTrue(GenkitMetric.MALICIOUSNESS.isBilled());
    assertFalse(GenkitMetric.REGEX.isBilled());
    assertFalse(GenkitMetric.DEEP_EQUAL.isBilled());
    assertFalse(GenkitMetric.JSONATA.isBilled());
  }

  @Test
  void testAllValues() {
    List<GenkitMetric> metrics = List.of(GenkitMetric.values());
    assertEquals(7, metrics.size());
    assertTrue(metrics.contains(GenkitMetric.FAITHFULNESS));
    assertTrue(metrics.contains(GenkitMetric.ANSWER_RELEVANCY));
    assertTrue(metrics.contains(GenkitMetric.ANSWER_ACCURACY));
    assertTrue(metrics.contains(GenkitMetric.MALICIOUSNESS));
    assertTrue(metrics.contains(GenkitMetric.REGEX));
    assertTrue(metrics.contains(GenkitMetric.DEEP_EQUAL));
    assertTrue(metrics.contains(GenkitMetric.JSONATA));
  }
}
