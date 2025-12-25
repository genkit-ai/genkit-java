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

package com.google.genkit.samples;

import java.util.*;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.*;
import com.google.genkit.ai.evaluation.*;
import com.google.genkit.core.Flow;
import com.google.genkit.plugins.evaluators.EvaluatorsPlugin;
import com.google.genkit.plugins.evaluators.EvaluatorsPluginOptions;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import com.google.genkit.plugins.openai.OpenAIPlugin;

/**
 * Sample application demonstrating the Genkit Evaluators Plugin.
 *
 * This example shows how to use the EvaluatorsPlugin with all 7 metric types:
 * 
 * <h3>LLM-Based Metrics (require a judge model):</h3>
 * <ul>
 * <li><b>FAITHFULNESS</b> - Evaluates if the answer is faithful to the
 * context</li>
 * <li><b>ANSWER_RELEVANCY</b> - Evaluates if the answer is relevant to the
 * question</li>
 * <li><b>ANSWER_ACCURACY</b> - Evaluates if the answer matches the reference
 * answer</li>
 * <li><b>MALICIOUSNESS</b> - Detects harmful or malicious content</li>
 * </ul>
 * 
 * <h3>Programmatic Metrics (no LLM required):</h3>
 * <ul>
 * <li><b>REGEX</b> - Pattern matching evaluation</li>
 * <li><b>DEEP_EQUAL</b> - JSON deep equality comparison</li>
 * <li><b>JSONATA</b> - JSONata expression evaluation</li>
 * </ul>
 *
 * <h2>To run:</h2>
 * <ol>
 * <li>Set the OPENAI_API_KEY environment variable (for LLM-based
 * evaluators)</li>
 * <li>Navigate to the sample directory:
 * {@code cd java/samples/evaluators-plugin}</li>
 * <li>Run the app: {@code mvn exec:java} or {@code ./run.sh}</li>
 * <li>Test with curl commands shown in the console output</li>
 * </ol>
 */
public class EvaluatorsPluginSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Configure the Evaluators Plugin with all metric types
    EvaluatorsPluginOptions evaluatorOptions = EvaluatorsPluginOptions.builder()
        // Use all metrics
        .useAllMetrics()
        // Configure the judge model for LLM-based evaluators
        .judge("openai/gpt-4o-mini")
        // Configure embedder for answer relevancy (optional)
        .embedder("openai/text-embedding-3-small").build();

    EvaluatorsPlugin evaluatorsPlugin = new EvaluatorsPlugin(evaluatorOptions);

    // Create Genkit with plugins
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(OpenAIPlugin.create()).plugin(evaluatorsPlugin).plugin(jetty).build();

    // =====================================================================
    // Define a Q&A flow to evaluate
    // =====================================================================

    Flow<Map<String, Object>, Map<String, Object>, Void> qaFlow = genkit.defineFlow("answerQuestion",
        (Class<Map<String, Object>>) (Class<?>) Map.class, (Class<Map<String, Object>>) (Class<?>) Map.class,
        (ctx, input) -> {
          // Handle null input
          Map<String, Object> safeInput = input != null ? input : new HashMap<>();
          String question = (String) safeInput.getOrDefault("question", "What is the meaning of life?");
          String context = (String) safeInput.getOrDefault("context", "");

          try {
            String prompt = context.isEmpty()
                ? "Answer this question concisely: " + question
                : "Using the following context, answer the question.\n\nContext: " + context
                    + "\n\nQuestion: " + question + "\n\nAnswer:";

            ModelResponse response = genkit.generate(GenerateOptions.builder().model("openai/gpt-4o-mini")
                .prompt(prompt)
                .config(GenerationConfig.builder().temperature(0.3).maxOutputTokens(300).build())
                .build());

            Map<String, Object> result = new HashMap<>();
            result.put("answer", response.getText());
            result.put("question", question);
            result.put("context", context);
            return result;
          } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("answer", "I couldn't generate an answer. Error: " + e.getMessage());
            result.put("question", question);
            result.put("context", context);
            return result;
          }
        });

    // =====================================================================
    // Define a flow that demonstrates programmatic evaluations
    // =====================================================================

    Flow<Map<String, Object>, Map<String, Object>, Void> testEvaluatorsFlow = genkit.defineFlow("testEvaluators",
        (Class<Map<String, Object>>) (Class<?>) Map.class, (Class<Map<String, Object>>) (Class<?>) Map.class,
        (ctx, input) -> {
          // Handle null input - use empty map with defaults
          Map<String, Object> safeInput = input != null ? input : new HashMap<>();
          Map<String, Object> results = new LinkedHashMap<>();

          // Test 1: REGEX evaluation
          String testOutput = (String) safeInput.getOrDefault("output", "This is a successful response");
          String regexPattern = (String) safeInput.getOrDefault("regexPattern", ".*successful.*");

          results.put("regex_test", Map.of("output", testOutput, "pattern", regexPattern, "matches",
              testOutput.matches(regexPattern)));

          // Test 2: DEEP_EQUAL evaluation
          @SuppressWarnings("unchecked")
          Map<String, Object> actual = (Map<String, Object>) safeInput.getOrDefault("actual",
              Map.of("name", "John", "age", 30));
          @SuppressWarnings("unchecked")
          Map<String, Object> expected = (Map<String, Object>) safeInput.getOrDefault("expected",
              Map.of("name", "John", "age", 30));

          results.put("deep_equal_test",
              Map.of("actual", actual, "expected", expected, "equals", actual.equals(expected)));

          // Test 3: JSONATA expression
          String jsonataExpr = (String) safeInput.getOrDefault("jsonataExpression", "$sum(values)");
          @SuppressWarnings("unchecked")
          Map<String, Object> jsonData = (Map<String, Object>) safeInput.getOrDefault("jsonData",
              Map.of("values", Arrays.asList(1, 2, 3, 4, 5)));

          results.put("jsonata_test", Map.of("expression", jsonataExpr, "data", jsonData, "note",
              "Use the evaluators/jsonata evaluator to evaluate this"));

          return results;
        });

    // =====================================================================
    // Define simple pass-through flows for programmatic evaluator testing
    // =====================================================================

    // Simple flow that returns the "value" field from input - for regex testing
    Flow<Map<String, Object>, String, Void> regexTestFlow = genkit.defineFlow("regexTest",
        (Class<Map<String, Object>>) (Class<?>) Map.class, String.class, (ctx, input) -> {
          Map<String, Object> safeInput = input != null ? input : new HashMap<>();
          return (String) safeInput.getOrDefault("value", "");
        });

    // Simple flow that returns the "data" field from input - for JSON comparison
    Flow<Map<String, Object>, Map<String, Object>, Void> jsonTestFlow = genkit.defineFlow("jsonTest",
        (Class<Map<String, Object>>) (Class<?>) Map.class, (Class<Map<String, Object>>) (Class<?>) Map.class,
        (ctx, input) -> {
          Map<String, Object> safeInput = input != null ? input : new HashMap<>();
          @SuppressWarnings("unchecked")
          Map<String, Object> data = (Map<String, Object>) safeInput.getOrDefault("data", new HashMap<>());
          return data;
        });

    // =====================================================================
    // Create Sample Datasets for Evaluation
    // =====================================================================

    DatasetStore datasetStore = genkit.getDatasetStore();

    // Check if sample datasets exist
    List<DatasetMetadata> existingDatasets = datasetStore.listDatasets();

    // Dataset 1: Q&A Dataset for Faithfulness/Relevancy testing
    if (existingDatasets.stream().noneMatch(d -> "qa_evaluation".equals(d.getDatasetId()))) {
      List<DatasetSample> qaSamples = Arrays.asList(DatasetSample.builder().testCaseId("qa_1")
          .input(Map.of("question", "What is the capital of France?", "context",
              "France is a country in Western Europe. Paris is the capital and largest city of France."))
          .reference("Paris is the capital of France.").build(),
          DatasetSample.builder().testCaseId("qa_2").input(Map.of("question",
              "What year was the Eiffel Tower built?", "context",
              "The Eiffel Tower was constructed from 1887 to 1889 as the entrance arch for the 1889 World's Fair."))
              .reference("The Eiffel Tower was built in 1889.").build(),
          DatasetSample.builder().testCaseId("qa_3").input(Map.of("question", "Who wrote Romeo and Juliet?",
              "context",
              "Romeo and Juliet is a tragedy written by William Shakespeare early in his career about two young lovers."))
              .reference("William Shakespeare wrote Romeo and Juliet.").build());

      CreateDatasetRequest qaRequest = CreateDatasetRequest
          .builder().datasetId("qa_evaluation").data(qaSamples).datasetType(DatasetType.FLOW)
          .targetAction("/flow/answerQuestion").metricRefs(Arrays.asList("genkitEval/faithfulness",
              "genkitEval/answer_relevancy", "genkitEval/answer_accuracy", "genkitEval/maliciousness"))
          .build();

      datasetStore.createDataset(qaRequest);
      System.out.println("Created dataset: qa_evaluation");
    }

    // Dataset 2: Regex Validation Dataset
    // Uses regexTest flow which returns input.value as output for regex evaluation
    if (existingDatasets.stream().noneMatch(d -> "regex_validation".equals(d.getDatasetId()))) {
      List<DatasetSample> regexSamples = Arrays.asList(
          DatasetSample.builder().testCaseId("regex_1").input(Map.of("value", "test@example.com"))
              .reference("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$") // email pattern
              .build(),
          DatasetSample.builder().testCaseId("regex_2").input(Map.of("value", "123-456-7890"))
              .reference("^\\d{3}-\\d{3}-\\d{4}$") // phone pattern
              .build(),
          DatasetSample.builder().testCaseId("regex_3").input(Map.of("value", "2024-01-15"))
              .reference("^\\d{4}-\\d{2}-\\d{2}$") // date pattern
              .build());

      CreateDatasetRequest regexRequest = CreateDatasetRequest.builder().datasetId("regex_validation")
          .data(regexSamples).datasetType(DatasetType.FLOW).targetAction("/flow/regexTest")
          .metricRefs(Arrays.asList("genkitEval/regex")).build();

      datasetStore.createDataset(regexRequest);
      System.out.println("Created dataset: regex_validation");
    }

    // Dataset 3: JSON Comparison Dataset
    // Uses jsonTest flow which returns input.data as output for JSON comparison
    if (existingDatasets.stream().noneMatch(d -> "json_comparison".equals(d.getDatasetId()))) {
      List<DatasetSample> jsonSamples = Arrays.asList(
          DatasetSample.builder().testCaseId("json_1")
              .input(Map.of("data", Map.of("name", "John", "age", 30, "city", "NYC")))
              .reference(Map.of("name", "John", "age", 30, "city", "NYC")).build(),
          DatasetSample.builder().testCaseId("json_2")
              .input(Map.of("data", Map.of("items", Arrays.asList("a", "b", "c"))))
              .reference(Map.of("items", Arrays.asList("a", "b", "c"))).build());

      CreateDatasetRequest jsonRequest = CreateDatasetRequest.builder().datasetId("json_comparison")
          .data(jsonSamples).datasetType(DatasetType.FLOW).targetAction("/flow/jsonTest")
          .metricRefs(Arrays.asList("genkitEval/deep_equal")).build();

      datasetStore.createDataset(jsonRequest);
      System.out.println("Created dataset: json_comparison");
    }

    // =====================================================================
    // Print Information
    // =====================================================================

    System.out.println();
    System.out.println("╔════════════════════════════════════════════════════════════════════════╗");
    System.out.println("║              Genkit Evaluators Plugin Sample Application               ║");
    System.out.println("╠════════════════════════════════════════════════════════════════════════╣");
    System.out.println("║ Dev UI: http://localhost:3100                                          ║");
    System.out.println("║ API:    http://localhost:8080                                          ║");
    System.out.println("╠════════════════════════════════════════════════════════════════════════╣");
    System.out.println("║ Registered Evaluators (from EvaluatorsPlugin):                         ║");
    System.out.println("║                                                                        ║");
    System.out.println("║ LLM-Based Metrics (require judge model):                               ║");
    System.out.println("║   • evaluators/faithfulness    - Context faithfulness                  ║");
    System.out.println("║   • evaluators/answerRelevancy - Question relevance                    ║");
    System.out.println("║   • evaluators/answerAccuracy  - Reference accuracy                    ║");
    System.out.println("║   • evaluators/maliciousness   - Harmful content detection             ║");
    System.out.println("║                                                                        ║");
    System.out.println("║ Programmatic Metrics (no LLM required):                                ║");
    System.out.println("║   • evaluators/regex           - Regex pattern matching                ║");
    System.out.println("║   • evaluators/deepEqual       - JSON deep equality                    ║");
    System.out.println("║   • evaluators/jsonata         - JSONata expression evaluation         ║");
    System.out.println("╠════════════════════════════════════════════════════════════════════════╣");
    System.out.println("║ Sample Datasets:                                                       ║");
    System.out.println("║   • qa_evaluation     - Q&A pairs for LLM-based evaluation             ║");
    System.out.println("║   • regex_validation  - Pattern matching test cases                    ║");
    System.out.println("║   • json_comparison   - JSON equality test cases                       ║");
    System.out.println("╠════════════════════════════════════════════════════════════════════════╣");
    System.out.println("║ API Endpoints:                                                         ║");
    System.out.println("║   POST /api/flows/answerQuestion  - Q&A flow for evaluation            ║");
    System.out.println("║   POST /api/flows/testEvaluators  - Test programmatic evaluators       ║");
    System.out.println("╠════════════════════════════════════════════════════════════════════════╣");
    System.out.println("║ Example curl commands:                                                 ║");
    System.out.println("║                                                                        ║");
    System.out.println("║ 1. Answer a question:                                                  ║");
    System.out.println("║    curl -X POST http://localhost:8080/api/flows/answerQuestion \\       ║");
    System.out.println("║      -H 'Content-Type: application/json' \\                             ║");
    System.out
        .println("║      -d '{\"question\":\"What is AI?\",\"context\":\"AI is artificial intelligence\"}' ║");
    System.out.println("║                                                                        ║");
    System.out.println("║ 2. Test evaluators:                                                    ║");
    System.out.println("║    curl -X POST http://localhost:8080/api/flows/testEvaluators \\       ║");
    System.out.println("║      -H 'Content-Type: application/json' \\                             ║");
    System.out.println("║      -d '{\"output\":\"success!\",\"regexPattern\":\".*success.*\"}'         ║");
    System.out.println("╠════════════════════════════════════════════════════════════════════════╣");
    System.out.println("║ Data Storage:                                                          ║");
    System.out.println("║   Datasets:  ./.genkit/datasets/                                       ║");
    System.out.println("║   Eval Runs: ./.genkit/evals/                                          ║");
    System.out.println("╚════════════════════════════════════════════════════════════════════════╝");
    System.out.println();
    System.out.println("Working directory: " + System.getProperty("user.dir"));
    System.out.println();
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
