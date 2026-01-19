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

import com.google.genkit.ai.evaluation.Evaluator;
import com.google.genkit.core.Action;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import com.google.genkit.plugins.evaluators.metrics.AnswerAccuracyMetric;
import com.google.genkit.plugins.evaluators.metrics.AnswerRelevancyMetric;
import com.google.genkit.plugins.evaluators.metrics.DeepEqualMetric;
import com.google.genkit.plugins.evaluators.metrics.FaithfulnessMetric;
import com.google.genkit.plugins.evaluators.metrics.JsonataMetric;
import com.google.genkit.plugins.evaluators.metrics.MaliciousnessMetric;
import com.google.genkit.plugins.evaluators.metrics.RegexMetric;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluators plugin for Genkit.
 *
 * <p>This plugin provides a set of pre-defined evaluators for assessing the quality of your LLM
 * outputs. Evaluators include:
 *
 * <ul>
 *   <li>FAITHFULNESS - Measures factual accuracy against provided context
 *   <li>ANSWER_RELEVANCY - Assesses how well the answer pertains to the question
 *   <li>ANSWER_ACCURACY - Compares output against a reference answer
 *   <li>MALICIOUSNESS - Detects harmful, misleading, or deceptive content
 *   <li>REGEX - Validates output against a regex pattern
 *   <li>DEEP_EQUAL - Checks deep equality between output and reference
 *   <li>JSONATA - Evaluates output using JSONata expressions
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .addPlugin(
 *         EvaluatorsPlugin.create(
 *             EvaluatorsPluginOptions.builder()
 *                 .judge("googleai/gemini-2.0-flash")
 *                 .metricTypes(List.of(GenkitMetric.FAITHFULNESS, GenkitMetric.ANSWER_RELEVANCY))
 *                 .build()))
 *     .build();
 * }</pre>
 */
public class EvaluatorsPlugin implements Plugin {

  private static final Logger logger = LoggerFactory.getLogger(EvaluatorsPlugin.class);
  private static final String PLUGIN_NAME = "genkitEval";

  private final EvaluatorsPluginOptions options;
  private Registry registry;

  /** Creates an EvaluatorsPlugin with default options. */
  public EvaluatorsPlugin() {
    this(EvaluatorsPluginOptions.builder().build());
  }

  /**
   * Creates an EvaluatorsPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public EvaluatorsPlugin(EvaluatorsPluginOptions options) {
    this.options = options;
  }

  /**
   * Creates an EvaluatorsPlugin with default options.
   *
   * @return a new EvaluatorsPlugin
   */
  public static EvaluatorsPlugin create() {
    return new EvaluatorsPlugin();
  }

  /**
   * Creates an EvaluatorsPlugin with the specified options.
   *
   * @param options the plugin options
   * @return a new EvaluatorsPlugin
   */
  public static EvaluatorsPlugin create(EvaluatorsPluginOptions options) {
    return new EvaluatorsPlugin(options);
  }

  @Override
  public String getName() {
    return PLUGIN_NAME;
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    return init(null);
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    this.registry = registry;
    List<Action<?, ?, ?>> actions = new ArrayList<>();
    List<MetricConfig> metricsToRegister = options.getMetrics();

    if (metricsToRegister == null || metricsToRegister.isEmpty()) {
      logger.warn("No metrics configured for EvaluatorsPlugin");
      return actions;
    }

    for (MetricConfig metricConfig : metricsToRegister) {
      try {
        Evaluator<?> evaluator = createEvaluator(metricConfig);
        if (evaluator != null) {
          actions.add(evaluator);
          logger.debug("Created evaluator: {}", metricConfig.getMetricType().name());
        }
      } catch (Exception e) {
        logger.error("Failed to create evaluator for metric: {}", metricConfig.getMetricType(), e);
      }
    }

    logger.info("Evaluators plugin initialized with {} evaluators", actions.size());
    return actions;
  }

  private Evaluator<?> createEvaluator(MetricConfig metricConfig) {
    GenkitMetric metricType = metricConfig.getMetricType();
    String judgeName = options.resolveJudge(metricConfig);
    String embedderName = options.resolveEmbedder(metricConfig);
    Map<String, Object> judgeConfig = options.resolveJudgeConfig(metricConfig);
    Map<String, Object> embedderOptions = options.resolveEmbedderOptions(metricConfig);

    switch (metricType) {
      case FAITHFULNESS:
        return createFaithfulnessEvaluator(judgeName, judgeConfig);
      case ANSWER_RELEVANCY:
        return createAnswerRelevancyEvaluator(
            judgeName, judgeConfig, embedderName, embedderOptions);
      case ANSWER_ACCURACY:
        return createAnswerAccuracyEvaluator(judgeName, judgeConfig);
      case MALICIOUSNESS:
        return createMaliciousnessEvaluator(judgeName, judgeConfig);
      case REGEX:
        return createRegexEvaluator();
      case DEEP_EQUAL:
        return createDeepEqualEvaluator();
      case JSONATA:
        return createJsonataEvaluator();
      default:
        logger.warn("Unknown metric type: {}", metricType);
        return null;
    }
  }

  private Evaluator<Void> createFaithfulnessEvaluator(
      String judgeName, Map<String, Object> judgeConfig) {
    FaithfulnessMetric metric = new FaithfulnessMetric(registry, judgeName, judgeConfig);

    return Evaluator.<Void>builder()
        .name(PLUGIN_NAME + "/" + GenkitMetric.FAITHFULNESS.name().toLowerCase())
        .displayName("Faithfulness")
        .definition(
            "Measures the factual accuracy of the generated answer against the given context. "
                + "A higher score indicates better alignment with the context.")
        .isBilled(true)
        .evaluatorFn(
            (dataPoint, opts) -> {
              try {
                return metric.evaluate(dataPoint);
              } catch (Exception e) {
                throw new RuntimeException("Faithfulness evaluation failed", e);
              }
            })
        .build();
  }

  private Evaluator<Void> createAnswerRelevancyEvaluator(
      String judgeName,
      Map<String, Object> judgeConfig,
      String embedderName,
      Map<String, Object> embedderOptions) {
    AnswerRelevancyMetric metric =
        new AnswerRelevancyMetric(registry, judgeName, judgeConfig, embedderName, embedderOptions);

    return Evaluator.<Void>builder()
        .name(PLUGIN_NAME + "/" + GenkitMetric.ANSWER_RELEVANCY.name().toLowerCase())
        .displayName("Answer Relevancy")
        .definition(
            "Assesses how well the generated answer pertains to the given question. "
                + "Higher scores indicate more relevant answers.")
        .isBilled(true)
        .evaluatorFn(
            (dataPoint, opts) -> {
              try {
                return metric.evaluate(dataPoint);
              } catch (Exception e) {
                throw new RuntimeException("Answer relevancy evaluation failed", e);
              }
            })
        .build();
  }

  private Evaluator<Void> createAnswerAccuracyEvaluator(
      String judgeName, Map<String, Object> judgeConfig) {
    AnswerAccuracyMetric metric = new AnswerAccuracyMetric(registry, judgeName, judgeConfig);

    return Evaluator.<Void>builder()
        .name(PLUGIN_NAME + "/" + GenkitMetric.ANSWER_ACCURACY.name().toLowerCase())
        .displayName("Answer Accuracy")
        .definition(
            "Measures the accuracy of the generated answer against a reference answer. "
                + "Uses bidirectional comparison for semantic equivalence.")
        .isBilled(true)
        .evaluatorFn(
            (dataPoint, opts) -> {
              try {
                return metric.evaluate(dataPoint);
              } catch (Exception e) {
                throw new RuntimeException("Answer accuracy evaluation failed", e);
              }
            })
        .build();
  }

  private Evaluator<Void> createMaliciousnessEvaluator(
      String judgeName, Map<String, Object> judgeConfig) {
    MaliciousnessMetric metric = new MaliciousnessMetric(registry, judgeName, judgeConfig);

    return Evaluator.<Void>builder()
        .name(PLUGIN_NAME + "/" + GenkitMetric.MALICIOUSNESS.name().toLowerCase())
        .displayName("Maliciousness")
        .definition(
            "Detects whether the output contains harmful, misleading, or deceptive content. "
                + "Returns FAIL if malicious content is detected.")
        .isBilled(true)
        .evaluatorFn(
            (dataPoint, opts) -> {
              try {
                return metric.evaluate(dataPoint);
              } catch (Exception e) {
                throw new RuntimeException("Maliciousness evaluation failed", e);
              }
            })
        .build();
  }

  private Evaluator<Void> createRegexEvaluator() {
    RegexMetric metric = new RegexMetric();

    return Evaluator.<Void>builder()
        .name(PLUGIN_NAME + "/" + GenkitMetric.REGEX.name().toLowerCase())
        .displayName("Regex Match")
        .definition(
            "Evaluates whether the output matches a regular expression pattern "
                + "provided in the reference field.")
        .isBilled(false)
        .evaluatorFn((dataPoint, opts) -> metric.evaluate(dataPoint))
        .build();
  }

  private Evaluator<Void> createDeepEqualEvaluator() {
    DeepEqualMetric metric = new DeepEqualMetric();

    return Evaluator.<Void>builder()
        .name(PLUGIN_NAME + "/" + GenkitMetric.DEEP_EQUAL.name().toLowerCase())
        .displayName("Deep Equal")
        .definition(
            "Checks if the output is deeply equal to the reference. "
                + "Supports JSON comparison for structured data.")
        .isBilled(false)
        .evaluatorFn((dataPoint, opts) -> metric.evaluate(dataPoint))
        .build();
  }

  private Evaluator<Void> createJsonataEvaluator() {
    JsonataMetric metric = new JsonataMetric();

    return Evaluator.<Void>builder()
        .name(PLUGIN_NAME + "/" + GenkitMetric.JSONATA.name().toLowerCase())
        .displayName("JSONata")
        .definition(
            "Evaluates the output using a JSONata expression provided in the reference. "
                + "The expression should return a truthy value for pass, falsy for fail.")
        .isBilled(false)
        .evaluatorFn((dataPoint, opts) -> metric.evaluate(dataPoint))
        .build();
  }

  /**
   * Gets the plugin options.
   *
   * @return the options
   */
  public EvaluatorsPluginOptions getOptions() {
    return options;
  }
}
