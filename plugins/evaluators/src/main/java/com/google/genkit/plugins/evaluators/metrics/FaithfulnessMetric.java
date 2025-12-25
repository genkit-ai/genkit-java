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
import java.util.stream.Collectors;

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
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.Registry;

/**
 * Faithfulness metric evaluator.
 * 
 * <p>
 * Measures the factual consistency of the generated answer against the given
 * context. Uses a two-step process: 1. Extract statements from the answer 2.
 * Check each statement against the context using NLI
 */
public class FaithfulnessMetric {

  private static final Logger logger = LoggerFactory.getLogger(FaithfulnessMetric.class);
  private static final double PASS_THRESHOLD = 0.5;

  private final Registry registry;
  private final String judgeName;
  private final Map<String, Object> judgeConfig;

  public FaithfulnessMetric(Registry registry, String judgeName, Map<String, Object> judgeConfig) {
    this.registry = registry;
    this.judgeName = judgeName;
    this.judgeConfig = judgeConfig;
  }

  /**
   * Evaluates faithfulness of the output against the context.
   *
   * @param dataPoint
   *            the evaluation data point
   * @return the evaluation response
   * @throws Exception
   *             if evaluation fails
   */
  public EvalResponse evaluate(EvalDataPoint dataPoint) throws Exception {
    logger.debug(
        "FaithfulnessMetric.evaluate called with dataPoint: testCaseId={}, input={}, output={}, context={}",
        dataPoint.getTestCaseId(), dataPoint.getInput(), dataPoint.getOutput(), dataPoint.getContext());

    // Extract question from input
    String question = extractQuestion(dataPoint);
    if (question == null || question.isEmpty()) {
      throw new IllegalArgumentException(
          "Input (question) was not provided. DataPoint input: " + dataPoint.getInput());
    }

    // Extract answer from output
    String answer = extractAnswer(dataPoint);
    if (answer == null || answer.isEmpty()) {
      throw new IllegalArgumentException("Output was not provided. DataPoint output: " + dataPoint.getOutput()
          + ", input: " + dataPoint.getInput());
    }

    // Extract context - try multiple sources
    String context = extractContext(dataPoint);
    if (context == null || context.isEmpty()) {
      throw new IllegalArgumentException("Context was not provided. Provide context in the input, "
          + "output, or set it in the EvalDataPoint.context field.");
    }

    // Step 1: Extract statements from the answer
    Map<String, String> longFormVars = new HashMap<>();
    longFormVars.put("question", question);
    longFormVars.put("answer", answer);
    String longFormPrompt = PromptUtils.loadAndRender("faithfulness_long_form.prompt", longFormVars);

    Model judge = lookupJudge();
    ModelResponse longFormResponse = invokeModel(judge, longFormPrompt);

    LongFormResponse parsedLongForm = parseResponse(longFormResponse.getText(), LongFormResponse.class);
    List<String> statements = parsedLongForm.getStatements();

    if (statements == null || statements.isEmpty()) {
      throw new IllegalStateException("No statements returned from long-form extraction");
    }

    // Step 2: NLI check - verify each statement against context
    String allStatements = statements.stream().map(s -> "statement: " + s).collect(Collectors.joining("\n"));

    Map<String, String> nliVars = new HashMap<>();
    nliVars.put("context", context);
    nliVars.put("statements", allStatements);
    String nliPrompt = PromptUtils.loadAndRender("faithfulness_nli.prompt", nliVars);

    ModelResponse nliResponse = invokeModel(judge, nliPrompt);
    NliResponse parsedNli = parseResponse(nliResponse.getText(), NliResponse.class);

    // Calculate score
    Score score = calculateScore(parsedNli.getResponses());

    return EvalResponse.builder().testCaseId(dataPoint.getTestCaseId()).evaluation(score).build();
  }

  private Score calculateScore(List<NliResponseItem> responses) {
    if (responses == null || responses.isEmpty()) {
      throw new IllegalStateException("Evaluator response empty");
    }

    long faithfulCount = responses.stream().filter(NliResponseItem::isVerdict).count();

    double score = (double) faithfulCount / responses.size();
    String reasoning = responses.stream().map(NliResponseItem::getReason).collect(Collectors.joining("; "));

    EvalStatus status = score > PASS_THRESHOLD ? EvalStatus.PASS : EvalStatus.FAIL;

    return Score.builder().score(score).status(status).details(ScoreDetails.builder().reasoning(reasoning).build())
        .build();
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

  private <T> T parseResponse(String text, Class<T> clazz) throws Exception {
    // Try to extract JSON from the response
    String json = extractJson(text);
    return JsonUtils.fromJson(json, clazz);
  }

  private String extractJson(String text) {
    // Find JSON object in the response
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return text.substring(start, end + 1);
    }
    // Try to find JSON array
    start = text.indexOf('[');
    end = text.lastIndexOf(']');
    if (start >= 0 && end > start) {
      return text.substring(start, end + 1);
    }
    return text;
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
      // If no question key, stringify the whole input
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
      // If no answer key, stringify the whole output
      return PromptUtils.stringify(output);
    }
    return PromptUtils.stringify(output);
  }

  /**
   * Extracts context from multiple possible sources: 1. EvalDataPoint.context
   * field (list) 2. Input map with "context" key 3. Output map with "context" key
   */
  @SuppressWarnings("unchecked")
  private String extractContext(EvalDataPoint dataPoint) {
    // First, check the context field directly
    if (dataPoint.getContext() != null && !dataPoint.getContext().isEmpty()) {
      return dataPoint.getContext().stream().map(PromptUtils::stringify).collect(Collectors.joining("\n"));
    }

    // Check if input is a Map with a "context" key
    if (dataPoint.getInput() instanceof Map) {
      Map<String, Object> inputMap = (Map<String, Object>) dataPoint.getInput();
      Object context = inputMap.get("context");
      if (context != null) {
        return PromptUtils.stringify(context);
      }
    }

    // Check if output is a Map with a "context" key
    if (dataPoint.getOutput() instanceof Map) {
      Map<String, Object> outputMap = (Map<String, Object>) dataPoint.getOutput();
      Object context = outputMap.get("context");
      if (context != null) {
        return PromptUtils.stringify(context);
      }
    }

    return null;
  }
}
