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

import com.google.genkit.ai.evaluation.EvalStatus;
import com.google.genkit.ai.evaluation.Score;
import java.util.Map;
import java.util.function.Function;

/**
 * Configuration for a specific evaluation metric.
 *
 * <p>This class allows configuring individual metrics with their required dependencies like judge
 * models and embedders.
 */
public class MetricConfig {

  private final GenkitMetric metricType;
  private final String judge;
  private final Map<String, Object> judgeConfig;
  private final String embedder;
  private final Map<String, Object> embedderOptions;
  private final Function<Score, EvalStatus> statusOverrideFn;

  private MetricConfig(Builder builder) {
    this.metricType = builder.metricType;
    this.judge = builder.judge;
    this.judgeConfig = builder.judgeConfig;
    this.embedder = builder.embedder;
    this.embedderOptions = builder.embedderOptions;
    this.statusOverrideFn = builder.statusOverrideFn;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a simple metric configuration with just the metric type.
   *
   * @param metricType the type of metric
   * @return a new MetricConfig
   */
  public static MetricConfig of(GenkitMetric metricType) {
    return builder().metricType(metricType).build();
  }

  /**
   * Creates a metric configuration with a judge model.
   *
   * @param metricType the type of metric
   * @param judge the judge model name
   * @return a new MetricConfig
   */
  public static MetricConfig withJudge(GenkitMetric metricType, String judge) {
    return builder().metricType(metricType).judge(judge).build();
  }

  /**
   * Creates a metric configuration with a judge model and embedder.
   *
   * @param metricType the type of metric
   * @param judge the judge model name
   * @param embedder the embedder name
   * @return a new MetricConfig
   */
  public static MetricConfig withJudgeAndEmbedder(
      GenkitMetric metricType, String judge, String embedder) {
    return builder().metricType(metricType).judge(judge).embedder(embedder).build();
  }

  public GenkitMetric getMetricType() {
    return metricType;
  }

  public String getJudge() {
    return judge;
  }

  public Map<String, Object> getJudgeConfig() {
    return judgeConfig;
  }

  public String getEmbedder() {
    return embedder;
  }

  public Map<String, Object> getEmbedderOptions() {
    return embedderOptions;
  }

  public Function<Score, EvalStatus> getStatusOverrideFn() {
    return statusOverrideFn;
  }

  public static class Builder {
    private GenkitMetric metricType;
    private String judge;
    private Map<String, Object> judgeConfig;
    private String embedder;
    private Map<String, Object> embedderOptions;
    private Function<Score, EvalStatus> statusOverrideFn;

    public Builder metricType(GenkitMetric metricType) {
      this.metricType = metricType;
      return this;
    }

    public Builder judge(String judge) {
      this.judge = judge;
      return this;
    }

    public Builder judgeConfig(Map<String, Object> judgeConfig) {
      this.judgeConfig = judgeConfig;
      return this;
    }

    public Builder embedder(String embedder) {
      this.embedder = embedder;
      return this;
    }

    public Builder embedderOptions(Map<String, Object> embedderOptions) {
      this.embedderOptions = embedderOptions;
      return this;
    }

    public Builder statusOverrideFn(Function<Score, EvalStatus> statusOverrideFn) {
      this.statusOverrideFn = statusOverrideFn;
      return this;
    }

    public MetricConfig build() {
      if (metricType == null) {
        throw new IllegalArgumentException("Metric type is required");
      }
      return new MetricConfig(this);
    }
  }
}
