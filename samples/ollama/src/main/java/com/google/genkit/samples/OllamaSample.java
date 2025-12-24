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
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import com.google.genkit.plugins.ollama.OllamaPlugin;
import com.google.genkit.plugins.ollama.OllamaPluginOptions;

/**
 * Sample application demonstrating Genkit with Ollama local models.
 *
 * This example shows how to: - Configure Genkit with the Ollama plugin - Define
 * flows using local Ollama models - Generate text with various open-source
 * models - Use streaming for real-time responses - Expose flows via HTTP
 * endpoints
 *
 * Prerequisites: 1. Install Ollama from https://ollama.ai 2. Pull a model:
 * ollama pull llama3.2 3. Ensure Ollama is running (it starts automatically
 * after installation)
 *
 * To run: mvn exec:java
 */
public class OllamaSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Ollama plugin with specific models
    // You can specify only the models you have pulled
    OllamaPlugin ollama = new OllamaPlugin(OllamaPluginOptions.builder().models("gemma3n:e4b").build());

    // Create Genkit with plugins
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(ollama).plugin(jetty).build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow = genkit.defineFlow("greeting", String.class, String.class,
        (name) -> "Hello, " + name + "!");

    // Define a chat flow using Gemma 3n
    Flow<String, String, Void> chatFlow = genkit.defineFlow("chat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("ollama/gemma3n:e4b")
              .system("You are a helpful AI assistant running locally via Ollama.").prompt(userMessage)
              .config(GenerationConfig.builder().maxOutputTokens(500).temperature(0.7).build()).build());

          return response.getText();
        });

    // Define a joke generator flow
    Flow<String, String, Void> jokeFlow = genkit.defineFlow("tellJoke", String.class, String.class,
        (ctx, topic) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("ollama/gemma3n:e4b")
              .prompt("Tell me a short, funny joke about: " + topic)
              .config(GenerationConfig.builder().temperature(0.9).maxOutputTokens(200).build()).build());

          return response.getText();
        });

    // Define a streaming chat flow
    Flow<String, String, Void> streamingChatFlow = genkit.defineFlow("streamingChat", String.class, String.class,
        (ctx, userMessage) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Streaming Response ---");
          System.out.println("Query: " + userMessage);
          System.out.println("Response: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder().model("ollama/gemma3n:e4b")
              .system("You are a helpful assistant that provides detailed, comprehensive responses.")
              .prompt(userMessage).config(GenerationConfig.builder().maxOutputTokens(1000).build())
              .build(), (chunk) -> {
                // Process each chunk as it arrives
                String text = chunk.getText();
                if (text != null) {
                  result.append(text);
                  System.out.print(text); // Print chunks in real-time
                }
              });

          System.out.println("\n--- End of Response ---\n");
          return response.getText();
        });

    // Define a code generation flow using CodeLlama
    Flow<String, String, Void> codeGenFlow = genkit.defineFlow("generateCode", String.class, String.class,
        (ctx, prompt) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Code Generation ---");
          System.out.println("Prompt: " + prompt);
          System.out.println("\nCode: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder().model("ollama/gemma3n:e4b")
              .system("You are an expert programmer. Write clean, well-documented code. "
                  + "Include comments explaining your approach.")
              .prompt(prompt)
              .config(GenerationConfig.builder().maxOutputTokens(2000).temperature(0.3).build()).build(),
              (chunk) -> {
                String text = chunk.getText();
                if (text != null) {
                  result.append(text);
                  System.out.print(text);
                }
              });

          System.out.println("\n--- End of Code ---\n");
          return response.getText();
        });

    // Define a fast response flow using Gemma 3n
    Flow<String, String, Void> quickAnswerFlow = genkit.defineFlow("quickAnswer", String.class, String.class,
        (ctx, question) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("ollama/gemma3n:e4b")
              .system("You are a helpful assistant. Give brief, direct answers.").prompt(question)
              .config(GenerationConfig.builder().maxOutputTokens(200).temperature(0.5).build()).build());

          return response.getText();
        });

    // Define a creative writing flow using Mistral
    Flow<String, String, Void> creativeWritingFlow = genkit.defineFlow("creativeWriting", String.class,
        String.class, (ctx, prompt) -> {
          StringBuilder result = new StringBuilder();

          System.out.println("\n--- Creative Writing ---");
          System.out.println("Prompt: " + prompt);
          System.out.println("\nStory: ");

          ModelResponse response = genkit.generateStream(GenerateOptions.builder().model("ollama/gemma3n:e4b")
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

    // Define a translation flow
    Flow<String, String, Void> translateFlow = genkit.defineFlow("translate", String.class, String.class,
        (ctx, text) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("ollama/gemma3n:e4b")
              .system("You are a translator. Translate the given text to Spanish. "
                  + "Only output the translation, nothing else.")
              .prompt(text)
              .config(GenerationConfig.builder().maxOutputTokens(500).temperature(0.3).build()).build());

          return response.getText();
        });

    // Define a summarization flow
    Flow<String, String, Void> summarizeFlow = genkit.defineFlow("summarize", String.class, String.class,
        (ctx, text) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder().model("ollama/gemma3n:e4b")
              .system("You are a summarization expert. Provide concise, accurate summaries "
                  + "that capture the key points of the input text.")
              .prompt("Please summarize the following text:\n\n" + text)
              .config(GenerationConfig.builder().maxOutputTokens(300).temperature(0.3).build()).build());

          return response.getText();
        });

    System.out.println("Genkit Ollama Sample Application Started!");
    System.out.println("==========================================");
    System.out.println("");
    System.out.println("NOTE: Make sure Ollama is running and you have pulled the required model:");
    System.out.println("  ollama pull gemma3n:e4b");
    System.out.println("");
    System.out.println("Dev UI: http://localhost:3100");
    System.out.println("API Endpoints:");
    System.out.println("  POST http://localhost:8080/api/flows/greeting");
    System.out.println("  POST http://localhost:8080/api/flows/chat");
    System.out.println("  POST http://localhost:8080/api/flows/tellJoke");
    System.out.println("  POST http://localhost:8080/api/flows/streamingChat");
    System.out.println("  POST http://localhost:8080/api/flows/generateCode");
    System.out.println("  POST http://localhost:8080/api/flows/quickAnswer");
    System.out.println("  POST http://localhost:8080/api/flows/creativeWriting");
    System.out.println("  POST http://localhost:8080/api/flows/translate");
    System.out.println("  POST http://localhost:8080/api/flows/summarize");
    System.out.println("");
    System.out.println("Example usage:");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/greeting -d '\"World\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/chat -d '\"What is the capital of France?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/tellJoke -d '\"programming\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/streamingChat -d '\"Explain quantum computing\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/generateCode -d '\"Write a Python function to find prime numbers\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/quickAnswer -d '\"What is 2+2?\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/creativeWriting -d '\"Write a short story about a robot learning to paint\"' -H 'Content-Type: application/json'");
    System.out.println(
        "  curl -X POST http://localhost:8080/api/flows/translate -d '\"Hello, how are you?\"' -H 'Content-Type: application/json'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop...");

    // Start the server and block
    jetty.start();
  }
}
