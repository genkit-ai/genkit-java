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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Configuration options for the GenkitEval plugin.
 * 
 * <p>
 * Allows configuring the default judge model, embedder, and the list of metrics
 * to enable.
 */
public class EvaluatorsPluginOptions {

  private final List<MetricConfig> metrics;
  private final String judge;
  private final Map<String, Object> judgeConfig;
  private final String embedder;
  private final Map<String, Object> embedderOptions;

  private EvaluatorsPluginOptions(Builder builder) {
    this.metrics = builder.metrics != null ? builder.metrics : new ArrayList<>();
    this.judge = builder.judge;
    this.judgeConfig = builder.judgeConfig;
    this.embedder = builder.embedder;
    this.embedderOptions = builder.embedderOptions;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates plugin options with the specified metrics using defaults for judge
   * and embedder.
   *
   * @param metrics
   *            the metrics to enable
   * @return plugin options
   */
  public static EvaluatorsPluginOptions withMetrics(MetricConfig... metrics) {
    return builder().metrics(Arrays.asList(metrics)).build();
  }

  /**
   * Creates plugin options with simple metric types (no per-metric
   * configuration).
   *
   * @param metricTypes
   *            the metric types to enable
   * @return plugin options
   */
  public static EvaluatorsPluginOptions withMetricTypes(GenkitMetric... metricTypes) {
    List<MetricConfig> configs = new ArrayList<>();
    for (GenkitMetric type : metricTypes) {
      configs.add(MetricConfig.of(type));
    }
    return builder().metrics(configs).build();
  }

  public List<MetricConfig> getMetrics() {
    return metrics;
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

  /**
   * Resolves the judge for a specific metric, falling back to the global default.
   *
   * @param config
   *            the metric configuration
   * @return the resolved judge model name
   */
  public String resolveJudge(MetricConfig config) {
    return config.getJudge() != null ? config.getJudge() : judge;
  }

  /**
   * Resolves the judge config for a specific metric, falling back to the global
   * default.
   *
   * @param config
   *            the metric configuration
   * @return the resolved judge configuration
   */
  public Map<String, Object> resolveJudgeConfig(MetricConfig config) {
    return config.getJudgeConfig() != null ? config.getJudgeConfig() : judgeConfig;
  }

  /**
   * Resolves the embedder for a specific metric, falling back to the global
   * default.
   *
   * @param config
   *            the metric configuration
   * @return the resolved embedder name
   */
  public String resolveEmbedder(MetricConfig config) {
    return config.getEmbedder() != null ? config.getEmbedder() : embedder;
  }

  /**
   * Resolves the embedder options for a specific metric, falling back to the
   * global default.
   *
   * @param config
   *            the metric configuration
   * @return the resolved embedder options
   */
  public Map<String, Object> resolveEmbedderOptions(MetricConfig config) {
    return config.getEmbedderOptions() != null ? config.getEmbedderOptions() : embedderOptions;
  }

  public static class Builder {
    private List<MetricConfig> metrics;
    private String judge;
    private Map<String, Object> judgeConfig;
    private String embedder;
    private Map<String, Object> embedderOptions;

    public Builder metrics(List<MetricConfig> metrics) {
      this.metrics = metrics;
      return this;
    }

    public Builder addMetric(MetricConfig metric) {
      if (this.metrics == null) {
        this.metrics = new ArrayList<>();
      }
      this.metrics.add(metric);
      return this;
    }

    public Builder addMetric(GenkitMetric metricType) {
      return addMetric(MetricConfig.of(metricType));
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

    /**
     * Sets the metrics from simple metric types (convenience method).
     *
     * @param metricTypes
     *            the metric types to enable
     * @return this builder
     */
    public Builder metricTypes(List<GenkitMetric> metricTypes) {
      this.metrics = new ArrayList<>();
      for (GenkitMetric type : metricTypes) {
        this.metrics.add(MetricConfig.of(type));
      }
      return this;
    }

    /**
     * Enables all available metrics.
     *
     * @return this builder
     */
    public Builder useAllMetrics() {
      this.metrics = new ArrayList<>();
      for (GenkitMetric type : GenkitMetric.values()) {
        this.metrics.add(MetricConfig.of(type));
      }
      return this;
    }

    public EvaluatorsPluginOptions build() {
      return new EvaluatorsPluginOptions(this);
    }
  }
}
