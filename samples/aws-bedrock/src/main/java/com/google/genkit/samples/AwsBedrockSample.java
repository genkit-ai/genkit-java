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
import com.google.genkit.plugins.awsbedrock.AwsBedrockPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

/**
 * Sample application demonstrating Genkit with AWS Bedrock models.
 *
 * This example shows how to: - Configure Genkit with the AWS Bedrock plugin -
 * Use models with ON_DEMAND support - Use models with inference profiles for
 * cross-region inference - Define flows - Use tools with models - Generate text
 * with various Bedrock models - Use streaming for real-time responses - Expose
 * flows via HTTP endpoints
 *
 * To run: 1. Configure AWS credentials (environment variables,
 * ~/.aws/credentials, or IAM role) 2. Ensure you have access to the models in
 * AWS Bedrock console 3. For inference profile models, enable cross-region
 * inference in Bedrock console 4. Run: ./run.sh or mvn exec:java
 */
public class AwsBedrockSample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Create Genkit with AWS Bedrock plugin (uses default credentials and
    // us-east-1)
    // Register custom inference profile using customModel() before passing to
    // Genkit
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(AwsBedrockPlugin.create("us-east-1").customModel("us.anthropic.claude-sonnet-4-20250514-v1:0"))
        .plugin(jetty).build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow = genkit.defineFlow("greeting", String.class, String.class,
        (name) -> "Hello, " + name + "!");

    // Define a joke generator flow using Amazon Nova
    Flow<String, String, Void> jokeFlow = genkit.defineFlow("tellJoke", String.class, String.class,
        (ctx, topic) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("aws-bedrock/amazon.nova-pro-v1:0")
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

    // Define a chat flow using Amazon Nova Lite on Bedrock
    Flow<String, String, Void> chatFlow = genkit.defineFlow("chat", String.class, String.class,
        (ctx, userMessage) -> {
          ModelResponse response = genkit
              .generate(GenerateOptions.builder().model("aws-bedrock/amazon.nova-lite-v1:0")
                  .system("You are a helpful AI assistant running on AWS Bedrock.")
                  .prompt(userMessage).build());

          return response.getText();
        });

    // Define a flow that uses the weather tool
    Flow<String, String, Void> weatherAssistantFlow = genkit.defineFlow("weatherAssistant", String.class,
        String.class, (ctx, userMessage) -> {
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("aws-bedrock/amazon.nova-pro-v1:0")
              .system("You are a helpful weather assistant. Use the getWeather tool to provide weather information when asked about the weather in a specific location.")
              .prompt(userMessage).tools(List.of(weatherTool)).build());

          return response.getText();
        });

    // Define a multi-model comparison flow
    Flow<String, String, Void> compareModelsFlow = genkit.defineFlow("compareModels", String.class, String.class,
        (ctx, question) -> {
          StringBuilder result = new StringBuilder();

          // Amazon Nova response
          ModelResponse novaResponse = genkit.generate(GenerateOptions.builder()
              .model("aws-bedrock/amazon.nova-pro-v1:0").prompt(question).build());
          result.append("Amazon Nova Pro:\n").append(novaResponse.getText()).append("\n\n");

          // Amazon Nova Lite response
          ModelResponse novaLiteResponse = genkit.generate(GenerateOptions.builder()
              .model("aws-bedrock/amazon.nova-lite-v1:0").prompt(question).build());
          result.append("Amazon Nova Lite:\n").append(novaLiteResponse.getText()).append("\n\n");

          // Meta Llama response
          ModelResponse llamaResponse = genkit.generate(GenerateOptions.builder()
              .model("aws-bedrock/meta.llama3-3-70b-instruct-v1:0").prompt(question).build());
          result.append("Meta Llama 3.3 70B:\n").append(llamaResponse.getText());

          return result.toString();
        });

    // Define a streaming example flow
    Flow<String, String, Void> streamingFlow = genkit.defineFlow("streamingDemo", String.class, String.class,
        (ctx, prompt) -> {
          System.out.println("\n=== Streaming Response ===");
          StringBuilder fullResponse = new StringBuilder();

          genkit.generateStream(
              GenerateOptions.builder().model("aws-bedrock/amazon.nova-pro-v1:0").prompt(prompt).build(),
              chunk -> {
                String text = chunk.getText();
                System.out.print(text);
                fullResponse.append(text);
              });

          System.out.println("\n=== End of Stream ===\n");
          return fullResponse.toString();
        });

    // Define a flow using an inference profile for cross-region access
    // Note: Requires cross-region inference enabled in AWS Bedrock console
    Flow<String, String, Void> inferenceProfileFlow = genkit.defineFlow("inferenceProfileDemo", String.class,
        String.class, (ctx, prompt) -> {
          // Using US inference profile for Claude 4 Sonnet
          // Format: {region-prefix}.{provider}.{model-name}
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("aws-bedrock/us.anthropic.claude-sonnet-4-20250514-v1:0")
              .system("You are Claude 4 Sonnet running via inference profile for cross-region routing.")
              .prompt(prompt)
              .config(GenerationConfig.builder().temperature(0.7).maxOutputTokens(500).build()).build());

          return response.getText();
        });

    System.out.println("AWS Bedrock Sample Started!");
    System.out.println("==============================");
    System.out.println("Genkit UI available at: http://localhost:3100");
    System.out.println("HTTP endpoints available at: http://localhost:8080");
    System.out.println("");
    System.out.println("Available flows:");
    System.out.println("  - greeting: Simple greeting");
    System.out.println("  - tellJoke: Generate a joke using Amazon Nova Pro");
    System.out.println("  - chat: Chat using Amazon Nova Lite");
    System.out.println("  - weatherAssistant: Weather assistant with tool use (Nova Pro)");
    System.out.println("  - compareModels: Compare responses from Nova Pro, Nova Lite, and Llama 3.3");
    System.out.println("  - streamingDemo: Demonstrate streaming responses");
    System.out.println("  - inferenceProfileDemo: Use Claude 4 Sonnet via inference profile");
    System.out.println("");
    System.out.println("Models used:");
    System.out.println("  - amazon.nova-pro-v1:0 (ON_DEMAND)");
    System.out.println("  - amazon.nova-lite-v1:0 (ON_DEMAND)");
    System.out.println("  - meta.llama3-3-70b-instruct-v1:0 (requires inference profile in some regions)");
    System.out.println("  - us.anthropic.claude-sonnet-4-20250514-v1:0 (INFERENCE_PROFILE)");
    System.out.println("");
    System.out.println("Note: Inference profiles enable cross-region routing for better availability.");
    System.out.println("      Format: {region}.{provider}.{model} (e.g., us./eu./apac. prefix)");
    System.out.println("      Requires cross-region inference enabled in AWS Bedrock console.");
    System.out.println("");
    System.out.println("Example requests:");
    System.out.println(
        "  curl -X POST http://localhost:8080/greeting -H 'Content-Type: application/json' -d '{\"data\": \"World\"}'");
    System.out.println(
        "  curl -X POST http://localhost:8080/tellJoke -H 'Content-Type: application/json' -d '{\"data\": \"cats\"}'");
    System.out.println(
        "  curl -X POST http://localhost:8080/chat -H 'Content-Type: application/json' -d '{\"data\": \"What is AWS Bedrock?\"}'");
    System.out.println(
        "  curl -X POST http://localhost:8080/weatherAssistant -H 'Content-Type: application/json' -d '{\"data\": \"What\\'s the weather in Seattle?\"}'");
    System.out.println(
        "  curl -X POST http://localhost:8080/inferenceProfileDemo -H 'Content-Type: application/json' -d '{\"data\": \"Explain quantum computing in simple terms.\"}'");
    System.out.println("");
    System.out.println("Press Ctrl+C to stop.");

    // Start the server and block
    jetty.start();
  }
}
