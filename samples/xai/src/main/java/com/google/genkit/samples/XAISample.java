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
import com.google.genkit.plugins.xai.XAIPlugin;

/**
 * Sample application demonstrating Genkit with XAI (Grok) models.
 *
 * This example shows how to: - Configure Genkit with the XAI plugin - Define
 * flows with Grok models - Use tools with Grok - Generate text with streaming -
 * Expose flows via HTTP endpoints
 *
 * To run: 1. Set the XAI_API_KEY environment variable 2. Run: mvn exec:java
 */
public class XAISample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Genkit with plugins
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(XAIPlugin.create()).plugin(jetty).build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow = genkit.defineFlow("greeting", String.class, String.class,
        (name) -> "Hello from Grok, " + name + "!");

    // Define a chat flow using Grok
    Flow<String, String, Void> chatFlow = genkit.defineFlow("chat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("xai/grok-4")
              .system("You are Grok, a witty and helpful AI assistant created by xAI.")
              .prompt(userMessage).build());

          return response.getText();
        });

    // Define a tool for getting current weather (mock implementation)
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, Map<String, Object>> weatherTool = genkit.defineTool("getWeather",
        "Gets the current weather for a location",
        Map.of("type", "object", "properties",
            Map.of("location", Map.of("type", "string", "description", "The city name")), "required",
            new String[]{"location"}),
        (Class<Map<String, Object>>) (Class<?>) Map.class, (ctx, input) -> {
          String location = (String) input.get("location");
          Map<String, Object> weather = new HashMap<>();
          weather.put("location", location);
          weather.put("temperature", "72°F");
          weather.put("conditions", "Sunny");
          return weather;
        });

    // Define a weather assistant flow that uses tools
    Flow<String, String, Void> weatherAssistantFlow = genkit.defineFlow("weatherAssistant", String.class,
        String.class, (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("xai/grok-4-1-fast")
              .system("You are a helpful weather assistant. Use the getWeather tool to provide weather information.")
              .prompt(userMessage).tools(List.of(weatherTool)).build());

          return response.getText();
        });

    // Define a streaming chat flow
    Flow<String, String, Void> streamingChatFlow = genkit.defineFlow("streamingChat", String.class, String.class,
        (ctx, userMessage) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Streaming Response ---");
          System.out.println("User: " + userMessage);
          System.out.println("Grok: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder().model("xai/grok-4")
              .system("You are Grok, providing detailed and engaging responses.").prompt(userMessage)
              .config(GenerationConfig.builder().maxOutputTokens(1000).build()).build(), (chunk) -> {
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
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("xai/grok-3").system(
              "You are an expert programmer. Write clean, well-documented code with helpful comments.")
              .prompt(prompt)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.3).build()).build());

          return response.getText();
        });

    // Define an analysis flow
    Flow<String, String, Void> analyzeFlow = genkit.defineFlow("analyze", String.class, String.class,
        (ctx, text) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("xai/grok-3-mini")
              .system("You are an analytical expert. Provide detailed, insightful analysis.")
              .prompt("Please analyze the following:\n\n" + text)
              .config(GenerationConfig.builder().maxOutputTokens(1000).temperature(0.5).build()).build());

          return response.getText();
        });

    // Define a creative writing flow with vision model
    Flow<String, String, Void> creativeWritingFlow = genkit.defineFlow("creativeWriting", String.class,
        String.class, (ctx, prompt) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Creative Writing with Grok Vision ---");
          System.out.println("Prompt: " + prompt);
          System.out.println("\nStory: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder().model("xai/grok-4").system(
              "You are a creative writer with a unique perspective. Write engaging, imaginative content.")
              .prompt(prompt)
              .config(GenerationConfig.builder().maxOutputTokens(1500).temperature(0.9).build()).build(),
              (chunk) -> {
                String text = chunk.getText();
                if (text != null) {
                  result.append(text);
                  System.out.print(text);
                }
              });

          System.out.println("\n--- End of Story ---\n");
          return response.getText();
        });

    System.out.println("Genkit XAI (Grok) Sample Application Started!");
    System.out.println("=============================================");
    System.out.println("Dev UI: http://localhost:3100");
    System.out.println("API Endpoints:");
    System.out.println("  POST http://localhost:8080/api/flows/greeting");
    System.out.println("  POST http://localhost:8080/api/flows/chat");
    System.out.println("  POST http://localhost:8080/api/flows/weatherAssistant (uses tools)");
    System.out.println("  POST http://localhost:8080/api/flows/streamingChat (uses streaming)");
    System.out.println("  POST http://localhost:8080/api/flows/generateCode (code generation)");
    System.out.println("  POST http://localhost:8080/api/flows/analyze (text analysis)");
    System.out.println("  POST http://localhost:8080/api/flows/creativeWriting (creative with vision model)");
    System.out.println("");
    System.out.println("Example usage:");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/greeting -d '\"World\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/chat -d '\"What makes you different from other AI models?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/weatherAssistant -d '\"What is the weather in New York?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/generateCode -d '\"Write a Java function to check if a string is a palindrome\"' -H 'Content-Type: application/json'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
