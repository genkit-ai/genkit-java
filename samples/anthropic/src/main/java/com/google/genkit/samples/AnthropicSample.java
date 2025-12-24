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
import com.google.genkit.plugins.anthropic.AnthropicPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

/**
 * Sample application demonstrating Genkit with Anthropic Claude models.
 *
 * This example shows how to: - Configure Genkit with the Anthropic plugin -
 * Define flows - Use tools with Claude 3.5 - Generate text with Claude models -
 * Use streaming for real-time responses - Expose flows via HTTP endpoints
 *
 * To run: 1. Set the ANTHROPIC_API_KEY environment variable 2. Run: mvn
 * exec:java
 */
public class AnthropicSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Genkit with plugins
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(AnthropicPlugin.create()).plugin(jetty).build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow = genkit.defineFlow("greeting", String.class, String.class,
        (name) -> "Hello, " + name + "!");

    // Define a joke generator flow using Claude
    Flow<String, String, Void> jokeFlow = genkit.defineFlow("tellJoke", String.class, String.class,
        (ctx, topic) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("anthropic/claude-sonnet-4-5-20250929")
              .prompt("Tell me a short, funny joke about: " + topic)
              .config(GenerationConfig.builder().temperature(0.9).maxOutputTokens(200).build()).build());

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

    // Define a chat flow
    Flow<String, String, Void> chatFlow = genkit.defineFlow("chat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit
              .generate(GenerateOptions.builder().model("anthropic/claude-sonnet-4-5-20250929")
                  .system("You are Claude, a helpful AI assistant created by Anthropic.")
                  .prompt(userMessage).build());

          return response.getText();
        });

    // Define a flow that uses the weather tool
    Flow<String, String, Void> weatherAssistantFlow = genkit.defineFlow("weatherAssistant", String.class,
        String.class, (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("anthropic/claude-sonnet-4-5-20250929")
              .system("You are a helpful weather assistant. Use the getWeather tool to provide weather information when asked about the weather in a specific location.")
              .prompt(userMessage).tools(List.of(weatherTool)).build());

          return response.getText();
        });

    // Define a streaming chat flow
    Flow<String, String, Void> streamingChatFlow = genkit.defineFlow("streamingChat", String.class, String.class,
        (ctx, userMessage) -> {
          StringBuilder result = new StringBuilder();

          ModelResponse response = genkit.generateStream(GenerateOptions.builder()
              .model("anthropic/claude-sonnet-4-5-20250929")
              .system("You are Claude, a helpful assistant that provides detailed, comprehensive responses.")
              .prompt(userMessage).config(GenerationConfig.builder().maxOutputTokens(1000).build())
              .build(), (chunk) -> {
                // Process each chunk as it arrives
                String text = chunk.getText();
                if (text != null) {
                  result.append(text);
                  System.out.print(text); // Print chunks in real-time
                }
              });

          System.out.println(); // New line after streaming completes
          return response.getText();
        });

    // Define a streaming flow that uses tools
    Flow<String, String, Void> streamingWeatherFlow = genkit.defineFlow("streamingWeather", String.class,
        String.class, (ctx, userMessage) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Streaming Weather Assistant ---");
          System.out.println("Query: " + userMessage);
          System.out.println("Response: ");

          ModelResponse response = genkit.generateStream(
              GenerateOptions.builder().model("anthropic/claude-sonnet-4-5-20250929")
                  .system("You are a helpful weather assistant. When asked about weather, "
                      + "use the getWeather tool to get current conditions, then provide "
                      + "a friendly, detailed response about the weather.")
                  .prompt(userMessage).tools(List.of(weatherTool))
                  .config(GenerationConfig.builder().maxOutputTokens(500).build()).build(),
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

    // Define a code generation flow using Claude
    Flow<String, String, Void> codeGenFlow = genkit.defineFlow("generateCode", String.class, String.class,
        (ctx, prompt) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("anthropic/claude-sonnet-4-5-20250929")
              .system("You are an expert programmer. Write clean, well-documented code. "
                  + "Include comments explaining your approach.")
              .prompt(prompt)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.3).build()).build());

          return response.getText();
        });

    // Define a creative writing flow using Claude
    Flow<String, String, Void> creativeWritingFlow = genkit.defineFlow("creativeWriting", String.class,
        String.class, (ctx, prompt) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Creative Writing ---");
          System.out.println("Prompt: " + prompt);
          System.out.println("\nStory: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder()
              .model("anthropic/claude-sonnet-4-5-20250929")
              .system("You are a creative writer. Write engaging, imaginative stories "
                  + "with vivid descriptions and compelling characters.")
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

    // Define a summarization flow
    Flow<String, String, Void> summarizeFlow = genkit.defineFlow("summarize", String.class, String.class,
        (ctx, text) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("anthropic/claude-haiku-4-5-20251001") // Using faster Haiku model
              .system("You are a summarization expert. Provide concise, accurate summaries "
                  + "that capture the key points of the input text.")
              .prompt("Please summarize the following text:\n\n" + text)
              .config(GenerationConfig.builder().maxOutputTokens(500).temperature(0.3).build()).build());

          return response.getText();
        });

    System.out.println("Genkit Anthropic Sample Application Started!");
    System.out.println("=============================================");
    System.out.println("Dev UI: http://localhost:3100");
    System.out.println("API Endpoints:");
    System.out.println("  POST http://localhost:8080/api/flows/greeting");
    System.out.println("  POST http://localhost:8080/api/flows/tellJoke");
    System.out.println("  POST http://localhost:8080/api/flows/chat");
    System.out.println("  POST http://localhost:8080/api/flows/weatherAssistant (uses tools)");
    System.out.println("  POST http://localhost:8080/api/flows/streamingChat (uses streaming)");
    System.out.println("  POST http://localhost:8080/api/flows/streamingWeather (streaming + tools)");
    System.out.println("  POST http://localhost:8080/api/flows/generateCode (code generation)");
    System.out.println("  POST http://localhost:8080/api/flows/creativeWriting (creative streaming)");
    System.out.println("  POST http://localhost:8080/api/flows/summarize (text summarization)");
    System.out.println("");
    System.out.println("Example usage:");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/greeting -d '\"World\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/tellJoke -d '\"programming\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/chat -d '\"What makes Claude different from other AI assistants?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/weatherAssistant -d '\"What is the weather in San Francisco?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/streamingChat -d '\"Explain quantum computing\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/generateCode -d '\"Write a Python function to find prime numbers\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/creativeWriting -d '\"Write a short story about a robot learning to paint\"' -H 'Content-Type: application/json'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
