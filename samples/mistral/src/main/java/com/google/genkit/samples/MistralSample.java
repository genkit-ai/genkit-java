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
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import com.google.genkit.plugins.mistral.MistralPlugin;

/**
 * Sample application demonstrating Genkit with Mistral AI models.
 *
 * This example shows how to: - Configure Genkit with the Mistral plugin - Use
 * Mistral Large for complex tasks - Use smaller models for efficient processing
 * - Use specialized models (Codestral for code, Pixtral for vision) - Define
 * flows with tool usage - Generate and stream responses
 *
 * To run: 1. Set the MISTRAL_API_KEY environment variable 2. Run: mvn exec:java
 */
public class MistralSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Genkit with plugins
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(MistralPlugin.create()).plugin(jetty).build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow = genkit.defineFlow("greeting", String.class, String.class,
        (name) -> "Hello from Mistral, " + name + "!");

    // Define a chat flow using Mistral Large
    Flow<String, String, Void> chatFlow = genkit.defineFlow("chat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit
              .generate(GenerateOptions.builder().model("mistral/mistral-large-2512")
                  .system("You are a helpful AI assistant powered by Mistral AI.").prompt(userMessage)
                  .build());

          return response.getText();
        });

    // Define a translation tool (mock implementation)
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, Map<String, Object>> translateTool = genkit.defineTool("translate",
        "Translates text to a target language",
        Map.of("type", "object", "properties",
            Map.of("text", Map.of("type", "string", "description", "The text to translate"),
                "targetLanguage",
                Map.of("type", "string", "description",
                    "The target language (e.g., 'French', 'Spanish')")),
            "required", new String[]{"text", "targetLanguage"}),
        (Class<Map<String, Object>>) (Class<?>) Map.class, (ctx, input) -> {
          String text = (String) input.get("text");
          String targetLanguage = (String) input.get("targetLanguage");
          Map<String, Object> result = new HashMap<>();
          result.put("originalText", text);
          result.put("targetLanguage", targetLanguage);
          result.put("translatedText", "[Mock translation to " + targetLanguage + ": " + text + "]");
          return result;
        });

    // Define a translation assistant flow that uses tools
    Flow<String, String, Void> translationAssistantFlow = genkit.defineFlow("translationAssistant", String.class,
        String.class, (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("mistral/mistral-medium-2508")
              .system("You are a translation assistant. Use the translate tool when needed to translate text.")
              .prompt(userMessage).tools(List.of(translateTool)).build());

          return response.getText();
        });

    // Define a streaming chat flow with Small model
    Flow<String, String, Void> streamingChatFlow = genkit.defineFlow("streamingChat", String.class, String.class,
        (ctx, userMessage) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Streaming Response ---");
          System.out.println("User: " + userMessage);
          System.out.println("Mistral: ");

          ModelResponse response = genkit.generateStream(
              GenerateOptions.builder().model("mistral/mistral-small-2506")
                  .system("You are a helpful assistant providing clear, concise responses.")
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

    // Define a code generation flow using Codestral
    Flow<String, String, Void> codeGenFlow = genkit.defineFlow("generateCode", String.class, String.class,
        (ctx, prompt) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("mistral/codestral-2508")
              .system("You are Codestral, an expert programming assistant. Write clean, efficient, well-documented code.")
              .prompt(prompt)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.3).build()).build());

          return response.getText();
        });

    // Define a quick Q&A flow using Ministral 3B
    Flow<String, String, Void> quickQAFlow = genkit.defineFlow("quickQA", String.class, String.class,
        (ctx, question) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("mistral/ministral-3b-2512")
              .system("You are a helpful assistant. Provide quick, accurate answers.").prompt(question)
              .config(GenerationConfig.builder().maxOutputTokens(500).temperature(0.5).build()).build());

          return response.getText();
        });

    // Define an efficient chat flow using Ministral 8B
    Flow<String, String, Void> efficientChatFlow = genkit.defineFlow("efficientChat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("mistral/ministral-8b-2512")
              .system("You are a balanced assistant. Provide helpful responses efficiently.")
              .prompt(userMessage)
              .config(GenerationConfig.builder().maxOutputTokens(800).temperature(0.7).build()).build());

          return response.getText();
        });

    // Define a creative writing flow with Pixtral Large
    Flow<String, String, Void> creativeWritingFlow = genkit.defineFlow("creativeWriting", String.class,
        String.class, (ctx, prompt) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Creative Writing with Pixtral Large ---");
          System.out.println("Prompt: " + prompt);
          System.out.println("\nContent: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder()
              .model("mistral/mistral-large-2512")
              .system("You are a creative writer. Write engaging, imaginative content with vivid descriptions.")
              .prompt(prompt)
              .config(GenerationConfig.builder().maxOutputTokens(1500).temperature(0.9).build()).build(),
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

    System.out.println("Genkit Mistral AI Sample Application Started!");
    System.out.println("=============================================");
    System.out.println("Dev UI: http://localhost:3100");
    System.out.println("API Endpoints:");
    System.out.println("  POST http://localhost:8080/api/flows/greeting");
    System.out.println("  POST http://localhost:8080/api/flows/chat (mistral-large)");
    System.out.println("  POST http://localhost:8080/api/flows/translationAssistant (uses tools)");
    System.out.println("  POST http://localhost:8080/api/flows/streamingChat (mistral-small with streaming)");
    System.out.println("  POST http://localhost:8080/api/flows/generateCode (codestral)");
    System.out.println("  POST http://localhost:8080/api/flows/quickQA (ministral-3b)");
    System.out.println("  POST http://localhost:8080/api/flows/efficientChat (ministral-8b)");
    System.out.println("  POST http://localhost:8080/api/flows/creativeWriting (pixtral-large with streaming)");
    System.out.println("");
    System.out.println("Example usage:");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/greeting -d '\"World\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/chat -d '\"Explain machine learning\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/translationAssistant -d '\"Translate 'Hello World' to French\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/generateCode -d '\"Write a Python function to find the nth Fibonacci number\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/quickQA -d '\"What is photosynthesis?\"' -H 'Content-Type: application/json'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
