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
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluatorsPluginOptionsTest {

  @Test
  void testBuilderWithDefaults() {
    EvaluatorsPluginOptions options = EvaluatorsPluginOptions.builder().build();

    assertNull(options.getJudge());
    assertNull(options.getEmbedder());
    assertNotNull(options.getMetrics());
    assertTrue(options.getMetrics().isEmpty());
  }

  @Test
  void testBuilderWithJudge() {
    EvaluatorsPluginOptions options =
        EvaluatorsPluginOptions.builder().judge("googleai/gemini-2.0-flash").build();

    assertEquals("googleai/gemini-2.0-flash", options.getJudge());
  }

  @Test
  void testBuilderWithMetricTypes() {
    EvaluatorsPluginOptions options =
        EvaluatorsPluginOptions.builder()
            .judge("test/model")
            .metricTypes(List.of(GenkitMetric.FAITHFULNESS, GenkitMetric.REGEX))
            .build();

    List<MetricConfig> metrics = options.getMetrics();
    assertNotNull(metrics);
    assertEquals(2, metrics.size());
    assertEquals(GenkitMetric.FAITHFULNESS, metrics.get(0).getMetricType());
    assertEquals(GenkitMetric.REGEX, metrics.get(1).getMetricType());
  }

  @Test
  void testBuilderWithMetrics() {
    MetricConfig config =
        MetricConfig.builder().metricType(GenkitMetric.MALICIOUSNESS).judge("custom/model").build();

    EvaluatorsPluginOptions options =
        EvaluatorsPluginOptions.builder().judge("default/model").metrics(List.of(config)).build();

    List<MetricConfig> metrics = options.getMetrics();
    assertNotNull(metrics);
    assertEquals(1, metrics.size());
    assertEquals(GenkitMetric.MALICIOUSNESS, metrics.get(0).getMetricType());
    assertEquals("custom/model", metrics.get(0).getJudge());
  }

  @Test
  void testResolveJudge() {
    EvaluatorsPluginOptions options =
        EvaluatorsPluginOptions.builder().judge("default/model").build();

    // Metric without judge should use default
    MetricConfig metricWithoutJudge = MetricConfig.of(GenkitMetric.FAITHFULNESS);
    assertEquals("default/model", options.resolveJudge(metricWithoutJudge));

    // Metric with judge should use its own
    MetricConfig metricWithJudge =
        MetricConfig.builder().metricType(GenkitMetric.FAITHFULNESS).judge("custom/model").build();
    assertEquals("custom/model", options.resolveJudge(metricWithJudge));
  }

  @Test
  void testResolveEmbedder() {
    EvaluatorsPluginOptions options =
        EvaluatorsPluginOptions.builder().embedder("default/embedder").build();

    // Metric without embedder should use default
    MetricConfig metricWithoutEmbedder = MetricConfig.of(GenkitMetric.ANSWER_RELEVANCY);
    assertEquals("default/embedder", options.resolveEmbedder(metricWithoutEmbedder));

    // Metric with embedder should use its own
    MetricConfig metricWithEmbedder =
        MetricConfig.builder()
            .metricType(GenkitMetric.ANSWER_RELEVANCY)
            .embedder("custom/embedder")
            .build();
    assertEquals("custom/embedder", options.resolveEmbedder(metricWithEmbedder));
  }

  @Test
  void testUseAllMetrics() {
    EvaluatorsPluginOptions options =
        EvaluatorsPluginOptions.builder().judge("test/model").useAllMetrics().build();

    List<MetricConfig> metrics = options.getMetrics();
    assertNotNull(metrics);
    assertEquals(GenkitMetric.values().length, metrics.size());
  }

  @Test
  void testResolveJudgeConfig() {
    Map<String, Object> defaultConfig = Map.of("temperature", 0.5);
    Map<String, Object> customConfig = Map.of("temperature", 0.8);

    EvaluatorsPluginOptions options =
        EvaluatorsPluginOptions.builder().judgeConfig(defaultConfig).build();

    // Metric without config should use default
    MetricConfig metricWithoutConfig = MetricConfig.of(GenkitMetric.FAITHFULNESS);
    assertEquals(defaultConfig, options.resolveJudgeConfig(metricWithoutConfig));

    // Metric with config should use its own
    MetricConfig metricWithConfig =
        MetricConfig.builder()
            .metricType(GenkitMetric.FAITHFULNESS)
            .judgeConfig(customConfig)
            .build();
    assertEquals(customConfig, options.resolveJudgeConfig(metricWithConfig));
  }
}
