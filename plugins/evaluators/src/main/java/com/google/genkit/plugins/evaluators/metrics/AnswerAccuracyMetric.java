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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.evaluation.EvalDataPoint;
import com.google.genkit.ai.evaluation.EvalResponse;
import com.google.genkit.ai.evaluation.EvalStatus;
import com.google.genkit.ai.evaluation.Score;
import com.google.genkit.ai.evaluation.ScoreDetails;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.Registry;

/**
 * Answer Accuracy metric evaluator.
 * 
 * <p>
 * Measures the accuracy of the generated answer against a reference answer.
 * Uses bidirectional comparison for semantic equivalence.
 */
public class AnswerAccuracyMetric {

  private static final Logger logger = LoggerFactory.getLogger(AnswerAccuracyMetric.class);
  private static final double PASS_THRESHOLD = 0.5;

  private final Registry registry;
  private final String judgeName;
  private final Map<String, Object> judgeConfig;

  public AnswerAccuracyMetric(Registry registry, String judgeName, Map<String, Object> judgeConfig) {
    this.registry = registry;
    this.judgeName = judgeName;
    this.judgeConfig = judgeConfig;
  }

  /**
   * Evaluates the accuracy of the output against the reference.
   *
   * @param dataPoint
   *            the evaluation data point
   * @return the evaluation response
   * @throws Exception
   *             if evaluation fails
   */
  public EvalResponse evaluate(EvalDataPoint dataPoint) throws Exception {
    // Extract answer from output
    String output = extractAnswer(dataPoint);
    if (output == null || output.isEmpty()) {
      throw new IllegalArgumentException("Output was not provided");
    }

    // Validate reference
    if (dataPoint.getReference() == null) {
      throw new IllegalArgumentException("Reference was not provided");
    }

    String input = extractQuestion(dataPoint);
    if (input == null) {
      input = "";
    }
    String reference = PromptUtils.stringify(dataPoint.getReference());

    Model judge = lookupJudge();

    // Original comparison: output vs reference
    Map<String, String> vars1 = new HashMap<>();
    vars1.put("query", input);
    vars1.put("output", output);
    vars1.put("reference", reference);
    String prompt1 = PromptUtils.loadAndRender("answer_accuracy.prompt", vars1);
    ModelResponse response1 = invokeModel(judge, prompt1);
    int origScore = parseScore(response1.getText());

    // Inverted comparison: reference vs output
    Map<String, String> vars2 = new HashMap<>();
    vars2.put("query", input);
    vars2.put("output", reference);
    vars2.put("reference", output);
    String prompt2 = PromptUtils.loadAndRender("answer_accuracy.prompt", vars2);
    ModelResponse response2 = invokeModel(judge, prompt2);
    int invScore = parseScore(response2.getText());

    // Calculate harmonic mean of both scores (normalized to 0-1)
    double normalizedOrig = (origScore - 1) / 4.0;
    double normalizedInv = (invScore - 1) / 4.0;

    double score;
    if (normalizedOrig == 0 || normalizedInv == 0) {
      score = 0.0;
    } else {
      score = 2 * normalizedOrig * normalizedInv / (normalizedOrig + normalizedInv);
    }

    EvalStatus status = score > PASS_THRESHOLD ? EvalStatus.PASS : EvalStatus.FAIL;

    return EvalResponse.builder().testCaseId(dataPoint.getTestCaseId())
        .evaluation(
            Score.builder().score(score).status(status)
                .details(ScoreDetails.builder()
                    .reasoning(String.format(
                        "Original score: %d/5, Inverted score: %d/5, Harmonic mean: %.2f",
                        origScore, invScore, score))
                    .build())
                .build())
        .build();
  }

  private int parseScore(String text) {
    // Extract the first digit from the response
    String trimmed = text.trim();
    for (char c : trimmed.toCharArray()) {
      if (Character.isDigit(c)) {
        int score = Character.getNumericValue(c);
        if (score >= 1 && score <= 5) {
          return score;
        }
      }
    }
    throw new IllegalStateException("Error parsing score from response: " + text);
  }

  private Model lookupJudge() {
    String modelKey = resolveModelKey(judgeName);
    Action<?, ?, ?> action = registry.lookupAction(modelKey);
    if (action == null) {
      throw new IllegalStateException("Judge model not found: " + judgeName);
    }
    if (!(action instanceof Model)) {
      throw new IllegalStateException("Action is not a model: " + modelKey);
    }
    return (Model) action;
  }

  private String resolveModelKey(String modelName) {
    if (modelName.startsWith("/model/")) {
      return modelName;
    }
    return ActionType.MODEL.keyFromName(modelName);
  }

  private ModelResponse invokeModel(Model model, String prompt) throws Exception {
    Message message = Message.builder().role(Role.USER).content(List.of(Part.text(prompt))).build();

    ModelRequest request = ModelRequest.builder().messages(List.of(message)).config(judgeConfig).build();

    ActionContext ctx = new ActionContext(registry);
    return model.run(ctx, request);
  }

  /**
   * Extracts the question from the datapoint input. Handles both simple string
   * inputs and Map inputs with a "question" key.
   */
  @SuppressWarnings("unchecked")
  private String extractQuestion(EvalDataPoint dataPoint) {
    Object input = dataPoint.getInput();
    if (input == null) {
      return null;
    }
    if (input instanceof Map) {
      Map<String, Object> inputMap = (Map<String, Object>) input;
      Object question = inputMap.get("question");
      if (question != null) {
        return PromptUtils.stringify(question);
      }
      return PromptUtils.stringify(input);
    }
    return PromptUtils.stringify(input);
  }

  /**
   * Extracts the answer from the datapoint output. Handles both simple string
   * outputs and Map outputs with an "answer" key.
   */
  @SuppressWarnings("unchecked")
  private String extractAnswer(EvalDataPoint dataPoint) {
    Object output = dataPoint.getOutput();
    if (output == null) {
      return null;
    }
    if (output instanceof Map) {
      Map<String, Object> outputMap = (Map<String, Object>) output;
      Object answer = outputMap.get("answer");
      if (answer != null) {
        return PromptUtils.stringify(answer);
      }
      return PromptUtils.stringify(output);
    }
    return PromptUtils.stringify(output);
  }
}
