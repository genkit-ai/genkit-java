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
import com.google.genkit.plugins.groq.GroqPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

/**
 * Sample application demonstrating Genkit with Groq's ultra-fast LLMs.
 *
 * This example shows how to: - Configure Genkit with the Groq plugin - Use
 * Llama models with ultra-fast inference - Use Mixtral for high-quality
 * responses - Use Gemma 2 for efficient processing - Define flows with tool
 * usage - Leverage Groq's speed for real-time applications
 *
 * To run: 1. Set the GROQ_API_KEY environment variable 2. Run: mvn exec:java
 */
public class GroqSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Genkit with plugins
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(GroqPlugin.create()).plugin(jetty).build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow = genkit.defineFlow("greeting", String.class, String.class,
        (name) -> "Hello from Groq, " + name + "!");

    // Define a chat flow using Llama 3.3 70B (most capable)
    Flow<String, String, Void> chatFlow = genkit.defineFlow("chat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("groq/llama-3.3-70b-versatile")
              .system("You are a helpful AI assistant providing fast, accurate responses powered by Groq's ultra-fast inference.")
              .prompt(userMessage).build());

          return response.getText();
        });

    // Define a time conversion tool
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, Map<String, Object>> timezoneTool = genkit.defineTool("convertTimezone",
        "Converts time between timezones",
        Map.of("type", "object", "properties",
            Map.of("time", Map.of("type", "string", "description", "The time to convert (e.g., '3:00 PM')"),
                "fromZone", Map.of("type", "string", "description", "Source timezone (e.g., 'PST')"),
                "toZone", Map.of("type", "string", "description", "Target timezone (e.g., 'EST')")),
            "required", new String[]{"time", "fromZone", "toZone"}),
        (Class<Map<String, Object>>) (Class<?>) Map.class, (ctx, input) -> {
          String time = (String) input.get("time");
          String fromZone = (String) input.get("fromZone");
          String toZone = (String) input.get("toZone");
          Map<String, Object> result = new HashMap<>();
          result.put("originalTime", time + " " + fromZone);
          result.put("convertedTime", "[Converted: " + time + " " + toZone + "]");
          result.put("timezone", toZone);
          return result;
        });

    // Define a time assistant flow that uses tools
    Flow<String, String, Void> timeAssistantFlow = genkit.defineFlow("timeAssistant", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("groq/llama-3.3-70b-versatile")
              .system("You are a helpful time zone assistant. Use the convertTimezone tool when needed.")
              .prompt(userMessage).tools(List.of(timezoneTool)).build());

          return response.getText();
        });

    // Define an ultra-fast streaming chat flow using Llama 3.1 8B
    Flow<String, String, Void> fastStreamingFlow = genkit.defineFlow("fastStreaming", String.class, String.class,
        (ctx, userMessage) -> {
          StringBuilder result = new StringBuilder();
          long startTime = System.currentTimeMillis();

          System.out.println("\n--- Ultra-Fast Streaming (Llama 3.1 8B) ---");
          System.out.println("User: " + userMessage);
          System.out.println("Groq: ");

          ModelResponse response = genkit.generateStream(
              GenerateOptions.builder().model("groq/llama-3.1-8b-instant")
                  .system("You are a fast, helpful assistant. Provide concise, accurate responses.")
                  .prompt(userMessage)
                  .config(GenerationConfig.builder().maxOutputTokens(1000).build()).build(),
              (chunk) -> {
                String text = chunk.getText();
                if (text != null) {
                  result.append(text);
                  System.out.print(text);
                }
              });

          long endTime = System.currentTimeMillis();
          System.out.println("\n--- Response Time: " + (endTime - startTime) + "ms ---\n");
          return response.getText();
        });

    // Define a high-quality chat flow using Mixtral
    Flow<String, String, Void> qualityChatFlow = genkit.defineFlow("qualityChat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("groq/mixtral-8x7b-32768")
              .system("You are a knowledgeable assistant providing detailed, high-quality responses.")
              .prompt(userMessage)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.7).build()).build());

          return response.getText();
        });

    // Define an efficient chat flow using Gemma 2
    Flow<String, String, Void> efficientChatFlow = genkit.defineFlow("efficientChat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("groq/gemma2-9b-it")
              .system("You are an efficient, helpful assistant.").prompt(userMessage)
              .config(GenerationConfig.builder().maxOutputTokens(800).temperature(0.7).build()).build());

          return response.getText();
        });

    // Define a real-time Q&A flow showcasing Groq's speed
    Flow<String, String, Void> realTimeQAFlow = genkit.defineFlow("realTimeQA", String.class, String.class,
        (ctx, question) -> {
          long startTime = System.currentTimeMillis();

          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("groq/llama-3.1-70b-versatile")
              .system("You are a real-time Q&A assistant. Provide quick, accurate answers.")
              .prompt(question)
              .config(GenerationConfig.builder().maxOutputTokens(500).temperature(0.5).build()).build());

          long endTime = System.currentTimeMillis();
          System.out.println("Response generated in " + (endTime - startTime) + "ms");

          return response.getText();
        });

    // Define a speed comparison flow
    Flow<String, String, Void> speedComparisonFlow = genkit.defineFlow("speedComparison", String.class,
        String.class, (ctx, prompt) -> {
          StringBuilder result = new StringBuilder();
          result.append("=== Groq Speed Comparison ===\n\n");

          // Test with Llama 3.1 8B (fastest)
          long start1 = System.currentTimeMillis();
          ModelResponse response1 = genkit
              .generate(GenerateOptions.builder().model("groq/llama-3.1-8b-instant").prompt(prompt)
                  .config(GenerationConfig.builder().maxOutputTokens(200).build()).build());
          long time1 = System.currentTimeMillis() - start1;

          // Test with Llama 3.3 70B (larger, still fast)
          long start2 = System.currentTimeMillis();
          ModelResponse response2 = genkit
              .generate(GenerateOptions.builder().model("groq/llama-3.3-70b-versatile").prompt(prompt)
                  .config(GenerationConfig.builder().maxOutputTokens(200).build()).build());
          long time2 = System.currentTimeMillis() - start2;

          result.append("Llama 3.1 8B (instant): ").append(time1).append("ms\n");
          result.append("Llama 3.3 70B (versatile): ").append(time2).append("ms\n\n");
          result.append("Even the larger 70B model is incredibly fast with Groq!\n");

          return result.toString();
        });

    System.out.println("Genkit Groq Sample Application Started!");
    System.out.println("=============================================");
    System.out.println("Dev UI: http://localhost:3100");
    System.out.println("API Endpoints:");
    System.out.println("  POST http://localhost:8080/api/flows/greeting");
    System.out.println("  POST http://localhost:8080/api/flows/chat (llama-3.3-70b)");
    System.out.println("  POST http://localhost:8080/api/flows/timeAssistant (uses tools)");
    System.out.println("  POST http://localhost:8080/api/flows/fastStreaming (llama-3.1-8b ultra-fast)");
    System.out.println("  POST http://localhost:8080/api/flows/qualityChat (mixtral-8x7b)");
    System.out.println("  POST http://localhost:8080/api/flows/efficientChat (gemma2-9b)");
    System.out.println("  POST http://localhost:8080/api/flows/realTimeQA (real-time Q&A)");
    System.out.println("  POST http://localhost:8080/api/flows/speedComparison (benchmark Groq speed)");
    System.out.println("");
    System.out.println("Example usage:");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/greeting -d '\"World\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/chat -d '\"Explain quantum computing\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/timeAssistant -d '\"Convert 3 PM PST to EST\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/fastStreaming -d '\"Tell me about black holes\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/speedComparison -d '\"What is AI?\"' -H 'Content-Type: application/json'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
