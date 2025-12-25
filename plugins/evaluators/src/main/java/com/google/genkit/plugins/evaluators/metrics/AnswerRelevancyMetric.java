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

import com.google.genkit.ai.Document;
import com.google.genkit.ai.EmbedRequest;
import com.google.genkit.ai.EmbedResponse;
import com.google.genkit.ai.Embedder;
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
 * Answer Relevancy metric evaluator.
 * 
 * <p>
 * Assesses how pertinent the generated answer is to the given prompt. Uses an
 * LLM judge to analyze the answer and optionally uses embeddings for similarity
 * comparison.
 */
public class AnswerRelevancyMetric {

  private static final Logger logger = LoggerFactory.getLogger(AnswerRelevancyMetric.class);
  private static final double PASS_THRESHOLD = 0.5;

  private final Registry registry;
  private final String judgeName;
  private final Map<String, Object> judgeConfig;
  private final String embedderName;
  private final Map<String, Object> embedderOptions;

  public AnswerRelevancyMetric(Registry registry, String judgeName, Map<String, Object> judgeConfig,
      String embedderName, Map<String, Object> embedderOptions) {
    this.registry = registry;
    this.judgeName = judgeName;
    this.judgeConfig = judgeConfig;
    this.embedderName = embedderName;
    this.embedderOptions = embedderOptions;
  }

  /**
   * Evaluates the relevancy of the answer to the question.
   *
   * @param dataPoint
   *            the evaluation data point
   * @return the evaluation response
   * @throws Exception
   *             if evaluation fails
   */
  public EvalResponse evaluate(EvalDataPoint dataPoint) throws Exception {
    // Extract question from input
    String question = extractQuestion(dataPoint);
    if (question == null || question.isEmpty()) {
      throw new IllegalArgumentException("Input (question) was not provided");
    }

    // Extract answer from output
    String answer = extractAnswer(dataPoint);
    if (answer == null || answer.isEmpty()) {
      throw new IllegalArgumentException("Output was not provided");
    }

    // Extract context - try multiple sources (optional for this metric)
    String context = extractContext(dataPoint);
    if (context == null) {
      context = ""; // Context is optional for answer relevancy
    }

    // Use LLM to analyze answer relevancy
    Map<String, String> vars = new HashMap<>();
    vars.put("question", question);
    vars.put("answer", answer);
    vars.put("context", context);
    String prompt = PromptUtils.loadAndRender("answer_relevancy.prompt", vars);

    Model judge = lookupJudge();
    ModelResponse response = invokeModel(judge, prompt);
    AnswerRelevancyResponse parsed = parseResponse(response.getText(), AnswerRelevancyResponse.class);

    // Calculate score
    double score;
    String reasoning;

    if (!parsed.isAnswered()) {
      score = 0.0;
      reasoning = "Answer does not address the question";
    } else if (parsed.isNoncommittal()) {
      score = 0.0;
      reasoning = "Answer is non-committal or evasive";
    } else {
      // Calculate similarity using embeddings if available
      if (embedderName != null) {
        double similarity = calculateCosineSimilarity(question, parsed.getQuestion());
        score = similarity * (parsed.isNoncommittal() ? 0 : 1);
        reasoning = "Cosine similarity"
            + (parsed.isNoncommittal() ? " with penalty for insufficient answer" : "");
      } else {
        score = parsed.isAnswered() && !parsed.isNoncommittal() ? 1.0 : 0.0;
        reasoning = "Answer is relevant to the question";
      }
    }

    EvalStatus status = score > PASS_THRESHOLD ? EvalStatus.PASS : EvalStatus.FAIL;

    return EvalResponse.builder().testCaseId(dataPoint.getTestCaseId()).evaluation(Score.builder().score(score)
        .status(status).details(ScoreDetails.builder().reasoning(reasoning).build()).build()).build();
  }

  private double calculateCosineSimilarity(String text1, String text2) throws Exception {
    if (embedderName == null) {
      return 0.5; // Default similarity if no embedder
    }

    Embedder embedder = lookupEmbedder();
    ActionContext ctx = new ActionContext(registry);

    // Get embeddings for both texts
    EmbedRequest request1 = new EmbedRequest(List.of(Document.fromText(text1)));
    EmbedRequest request2 = new EmbedRequest(List.of(Document.fromText(text2)));

    EmbedResponse response1 = embedder.run(ctx, request1);
    EmbedResponse response2 = embedder.run(ctx, request2);

    float[] embedding1 = response1.getEmbeddings().get(0).getValues();
    float[] embedding2 = response2.getEmbeddings().get(0).getValues();

    return cosineSimilarity(embedding1, embedding2);
  }

  private double cosineSimilarity(float[] vec1, float[] vec2) {
    if (vec1.length != vec2.length) {
      throw new IllegalArgumentException("Vectors must have the same dimension");
    }

    double dotProduct = 0.0;
    double norm1 = 0.0;
    double norm2 = 0.0;

    for (int i = 0; i < vec1.length; i++) {
      dotProduct += vec1[i] * vec2[i];
      norm1 += vec1[i] * vec1[i];
      norm2 += vec2[i] * vec2[i];
    }

    if (norm1 == 0 || norm2 == 0) {
      return 0.0;
    }

    return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
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

  private Embedder lookupEmbedder() {
    String embedderKey = ActionType.EMBEDDER.keyFromName(embedderName);
    Action<?, ?, ?> action = registry.lookupAction(embedderKey);
    if (action == null) {
      throw new IllegalStateException("Embedder not found: " + embedderName);
    }
    if (!(action instanceof Embedder)) {
      throw new IllegalStateException("Action is not an embedder: " + embedderKey);
    }
    return (Embedder) action;
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
    String json = extractJson(text);
    return JsonUtils.fromJson(json, clazz);
  }

  private String extractJson(String text) {
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
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
