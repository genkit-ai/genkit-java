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

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.*;
import com.google.genkit.core.Flow;
import com.google.genkit.plugins.cohere.CoherePlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample application demonstrating Genkit with Cohere models.
 *
 * <p>This example shows how to: - Configure Genkit with the Cohere plugin - Use Command R+ for
 * complex tasks - Use Command models for various tasks - Define flows with tool usage - Generate
 * and stream responses
 *
 * <p>To run: 1. Set the COHERE_API_KEY environment variable 2. Run: mvn exec:java
 */
public class CohereSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Genkit with plugins
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
            .plugin(CoherePlugin.create())
            .plugin(jetty)
            .build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow =
        genkit.defineFlow(
            "greeting", String.class, String.class, (name) -> "Hello from Cohere, " + name + "!");

    // Define a chat flow using Command A
    Flow<String, String, Void> chatFlow =
        genkit.defineFlow(
            "chat",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("cohere/command-a-03-2025")
                          .system("You are a helpful AI assistant powered by Cohere.")
                          .prompt(userMessage)
                          .build());

              return response.getText();
            });

    // Define a search tool (mock implementation)
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, Map<String, Object>> searchTool =
        genkit.defineTool(
            "search",
            "Searches the internet for information",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("query", Map.of("type", "string", "description", "The search query")),
                "required",
                new String[] {"query"}),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, input) -> {
              String query = (String) input.get("query");
              Map<String, Object> results = new HashMap<>();
              results.put("query", query);
              results.put(
                  "results",
                  List.of(
                      Map.of(
                          "title",
                          "Result 1 for: " + query,
                          "snippet",
                          "This is a mock search result."),
                      Map.of(
                          "title", "Result 2 for: " + query, "snippet", "Another mock result.")));
              return results;
            });

    // Define a research assistant flow that uses tools
    Flow<String, String, Void> researchAssistantFlow =
        genkit.defineFlow(
            "researchAssistant",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("cohere/command-a-03-2025")
                          .system(
                              "You are a research assistant. Use the search tool to find information when needed.")
                          .prompt(userMessage)
                          .tools(List.of(searchTool))
                          .build());

              return response.getText();
            });

    // Define a streaming chat flow
    Flow<String, String, Void> streamingChatFlow =
        genkit.defineFlow(
            "streamingChat",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              StringBuilder result = new StringBuilder();

              System.out.println("\n--- Streaming Response ---");
              System.out.println("User: " + userMessage);
              System.out.println("Cohere: ");

              ModelResponse response =
                  genkit.generateStream(
                      GenerateOptions.builder()
                          .model("cohere/command-a-03-2025")
                          .system(
                              "You are a knowledgeable assistant providing detailed, comprehensive responses.")
                          .prompt(userMessage)
                          .config(GenerationConfig.builder().maxOutputTokens(1000).build())
                          .build(),
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

    // Define a content generation flow using Command R
    Flow<String, String, Void> contentGenFlow =
        genkit.defineFlow(
            "generateContent",
            String.class,
            String.class,
            (ctx, prompt) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("cohere/command-r-08-2024")
                          .system(
                              "You are a professional content writer. Create engaging, well-structured content.")
                          .prompt(prompt)
                          .config(
                              GenerationConfig.builder()
                                  .maxOutputTokens(1500)
                                  .temperature(0.7)
                                  .build())
                          .build());

              return response.getText();
            });

    // Define a summarization flow using Command R7B
    Flow<String, String, Void> summarizeFlow =
        genkit.defineFlow(
            "summarize",
            String.class,
            String.class,
            (ctx, text) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("cohere/command-r7b-12-2024")
                          .system(
                              "You are a summarization expert. Provide clear, concise summaries.")
                          .prompt("Please summarize the following text:\n\n" + text)
                          .config(
                              GenerationConfig.builder()
                                  .maxOutputTokens(500)
                                  .temperature(0.3)
                                  .build())
                          .build());

              return response.getText();
            });

    // Define a Q&A flow using Command
    Flow<String, String, Void> qaFlow =
        genkit.defineFlow(
            "qa",
            String.class,
            String.class,
            (ctx, question) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("cohere/command-r-08-2024")
                          .system(
                              "You are a knowledgeable assistant. Provide accurate, helpful answers.")
                          .prompt(question)
                          .config(
                              GenerationConfig.builder()
                                  .maxOutputTokens(800)
                                  .temperature(0.5)
                                  .build())
                          .build());

              return response.getText();
            });

    // Define a creative writing flow with streaming
    Flow<String, String, Void> creativeWritingFlow =
        genkit.defineFlow(
            "creativeWriting",
            String.class,
            String.class,
            (ctx, prompt) -> {
              StringBuilder result = new StringBuilder();

              System.out.println("\n--- Creative Writing ---");
              System.out.println("Prompt: " + prompt);
              System.out.println("\nContent: ");

              ModelResponse response =
                  genkit.generateStream(
                      GenerateOptions.builder()
                          .model("cohere/command-a-03-2025")
                          .system(
                              "You are a creative writer with excellent storytelling skills. Write engaging, imaginative content.")
                          .prompt(prompt)
                          .config(
                              GenerationConfig.builder()
                                  .maxOutputTokens(1500)
                                  .temperature(0.9)
                                  .build())
                          .build(),
                      (chunk) -> {
                        String text = chunk.getText();
                        if (text != null) {
                          result.append(text);
                          System.out.print(text);
                        }
                      });

              System.out.println("\n--- End of Content ---\n");
              return response.getText();
            });

    System.out.println("Genkit Cohere Sample Application Started!");
    System.out.println("=============================================");
    System.out.println("Dev UI: http://localhost:3100");
    System.out.println("API Endpoints:");
    System.out.println("  POST http://localhost:8080/api/flows/greeting");
    System.out.println("  POST http://localhost:8080/api/flows/chat");
    System.out.println("  POST http://localhost:8080/api/flows/researchAssistant (uses tools)");
    System.out.println("  POST http://localhost:8080/api/flows/streamingChat (uses streaming)");
    System.out.println(
        "  POST http://localhost:8080/api/flows/generateContent (content generation)");
    System.out.println("  POST http://localhost:8080/api/flows/summarize (text summarization)");
    System.out.println("  POST http://localhost:8080/api/flows/qa (question answering)");
    System.out.println(
        "  POST http://localhost:8080/api/flows/creativeWriting (creative with streaming)");
    System.out.println("");
    System.out.println("Example usage:");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/greeting -d '\"World\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/chat -d '\"What are the benefits of AI?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/researchAssistant -d '\"Find information about renewable energy\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/qa -d '\"What is machine learning?\"' -H 'Content-Type: application/json'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
