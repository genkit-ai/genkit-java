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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.*;
import com.google.genkit.core.Flow;
import com.google.genkit.plugins.deepseek.DeepSeekPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

/**
 * Sample application demonstrating Genkit with DeepSeek models.
 *
 * This example shows how to: - Configure Genkit with the DeepSeek plugin - Use
 * DeepSeek-Chat for general tasks - Use DeepSeek-Reasoner for complex reasoning
 * - Define flows with tool usage - Generate and stream responses
 *
 * To run: 1. Set the DEEPSEEK_API_KEY environment variable 2. Run: mvn
 * exec:java
 */
public class DeepSeekSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Genkit with plugins
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(DeepSeekPlugin.create()).plugin(jetty).build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow = genkit.defineFlow("greeting", String.class, String.class,
        (name) -> "Hello from DeepSeek, " + name + "!");

    // Define a chat flow using DeepSeek-Chat
    Flow<String, String, Void> chatFlow = genkit.defineFlow("chat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("deepseek/deepseek-chat")
              .system("You are a helpful AI assistant powered by DeepSeek.").prompt(userMessage).build());

          return response.getText();
        });

    // Define a tool for mathematical calculations
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, Map<String, Object>> calculatorTool = genkit.defineTool("calculator",
        "Performs basic mathematical calculations",
        Map.of("type", "object", "properties",
            Map.of("operation",
                Map.of("type", "string", "description",
                    "The operation: add, subtract, multiply, divide"),
                "a", Map.of("type", "number", "description", "First number"), "b",
                Map.of("type", "number", "description", "Second number")),
            "required", new String[]{"operation", "a", "b"}),
        (Class<Map<String, Object>>) (Class<?>) Map.class, (ctx, input) -> {
          String operation = (String) input.get("operation");
          double a = ((Number) input.get("a")).doubleValue();
          double b = ((Number) input.get("b")).doubleValue();

          double result = switch (operation.toLowerCase()) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> b != 0 ? a / b : Double.NaN;
            default -> Double.NaN;
          };

          Map<String, Object> output = new HashMap<>();
          output.put("result", result);
          output.put("expression", a + " " + operation + " " + b + " = " + result);
          return output;
        });

    // Define a math assistant flow that uses tools
    Flow<String, String, Void> mathAssistantFlow = genkit.defineFlow("mathAssistant", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("deepseek/deepseek-chat")
              .system("You are a helpful math assistant. Use the calculator tool when needed.")
              .prompt(userMessage).tools(List.of(calculatorTool)).build());

          return response.getText();
        });

    // Define a reasoning flow using DeepSeek-Reasoner
    Flow<String, String, Void> reasoningFlow = genkit.defineFlow("reasoning", String.class, String.class,
        (ctx, problem) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("deepseek/deepseek-reasoner")
              .system("You are DeepSeek-Reasoner. Break down complex problems step by step and provide clear reasoning.")
              .prompt(problem)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.7).build()).build());

          return response.getText();
        });

    // Define a streaming chat flow
    Flow<String, String, Void> streamingChatFlow = genkit.defineFlow("streamingChat", String.class, String.class,
        (ctx, userMessage) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Streaming Response ---");
          System.out.println("User: " + userMessage);
          System.out.println("DeepSeek: ");

          ModelResponse response = genkit.generateStream(
              GenerateOptions.builder().model("deepseek/deepseek-chat")
                  .system("You are a knowledgeable assistant providing detailed explanations.")
                  .prompt(userMessage)
                  .config(GenerationConfig.builder().maxOutputTokens(1000).build()).build(),
              (chunk) -> {
                String text = chunk.getText();
                if (text != null) {
                  result.append(text);
                  System.out.print(text);
                }
              });

          System.out.println("\n--- End of Response ---\n");
          return response.getText();
        });

    // Define a code generation flow
    Flow<String, String, Void> codeGenFlow = genkit.defineFlow("generateCode", String.class, String.class,
        (ctx, prompt) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("deepseek/deepseek-chat")
              .system("You are an expert programmer. Write clean, efficient, well-documented code.")
              .prompt(prompt)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.3).build()).build());

          return response.getText();
        });

    // Define a code review flow
    Flow<String, String, Void> codeReviewFlow = genkit.defineFlow("codeReview", String.class, String.class,
        (ctx, code) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("deepseek/deepseek-chat")
              .system("You are a senior code reviewer. Provide constructive feedback on code quality, "
                  + "performance, security, and best practices.")
              .prompt("Please review this code:\n\n" + code)
              .config(GenerationConfig.builder().maxOutputTokens(1500).temperature(0.5).build()).build());

          return response.getText();
        });

    // Define a problem-solving flow with reasoning
    Flow<String, String, Void> problemSolvingFlow = genkit.defineFlow("problemSolving", String.class, String.class,
        (ctx, problem) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Problem Solving with DeepSeek-Reasoner ---");
          System.out.println("Problem: " + problem);
          System.out.println("\nReasoning: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder()
              .model("deepseek/deepseek-reasoner")
              .system("You are an expert problem solver. Think through problems systematically "
                  + "and explain your reasoning clearly.")
              .prompt(problem)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.8).build()).build(),
              (chunk) -> {
                String text = chunk.getText();
                if (text != null) {
                  result.append(text);
                  System.out.print(text);
                }
              });

          System.out.println("\n--- End of Reasoning ---\n");
          return response.getText();
        });

    System.out.println("Genkit DeepSeek Sample Application Started!");
    System.out.println("=============================================");
    System.out.println("Dev UI: http://localhost:3100");
    System.out.println("API Endpoints:");
    System.out.println("  POST http://localhost:8080/api/flows/greeting");
    System.out.println("  POST http://localhost:8080/api/flows/chat");
    System.out.println("  POST http://localhost:8080/api/flows/mathAssistant (uses tools)");
    System.out.println("  POST http://localhost:8080/api/flows/reasoning (uses deepseek-reasoner)");
    System.out.println("  POST http://localhost:8080/api/flows/streamingChat (uses streaming)");
    System.out.println("  POST http://localhost:8080/api/flows/generateCode (code generation)");
    System.out.println("  POST http://localhost:8080/api/flows/codeReview (code review)");
    System.out.println("  POST http://localhost:8080/api/flows/problemSolving (reasoning with streaming)");
    System.out.println("");
    System.out.println("Example usage:");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/greeting -d '\"World\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/chat -d '\"Explain quantum entanglement\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/mathAssistant -d '\"What is 25 times 47?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/reasoning -d '\"How can we solve climate change?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/generateCode -d '\"Write a Python function to calculate Fibonacci numbers\"' -H 'Content-Type: application/json'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
