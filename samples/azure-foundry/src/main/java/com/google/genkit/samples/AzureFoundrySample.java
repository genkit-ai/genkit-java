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
import com.google.genkit.plugins.azurefoundry.AzureFoundryPlugin;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPluginOptions;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample application demonstrating Genkit with Azure AI Foundry models.
 *
 * <p>This example shows how to: - Configure Genkit with the Azure Foundry plugin - Define flows -
 * Use tools with GPT-4o and o1 models - Generate text with various Azure Foundry models - Use
 * streaming for real-time responses - Use Azure Managed Identity for authentication - Expose flows
 * via HTTP endpoints
 *
 * <p>Supported models include: - Azure OpenAI: gpt-5-turbo, o1, o3-mini, gpt-4o, gpt-4o-mini,
 * gpt-4, gpt-35-turbo - Azure Direct: MAI-DS-R1, Grok-4, Llama-3.3, DeepSeek-V3/R1, GPT-OSS -
 * Partner: Claude Opus/Sonnet/Haiku 4.x
 *
 * <p>To run: 1. Set AZURE_AI_FOUNDRY_ENDPOINT environment variable 2. Configure authentication (API
 * key or Azure credentials) 3. Ensure models are deployed in your Azure AI Foundry project 4. Run:
 * ./run.sh or mvn exec:java
 */
public class AzureFoundrySample {

  public static void main(String[] args) throws Exception {
    // Create the Jetty server plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Get endpoint from environment or use provided value
    String endpoint = System.getenv("AZURE_AI_FOUNDRY_ENDPOINT");
    if (endpoint == null) {
      endpoint = "https://your-project.region.models.ai.azure.com";
    }

    String apiKey = System.getenv("AZURE_AI_FOUNDRY_API_KEY");

    // Create Azure Foundry plugin with custom deployment
    AzureFoundryPlugin azureFoundry =
        new AzureFoundryPlugin(
                AzureFoundryPluginOptions.builder()
                    .endpoint(endpoint)
                    .apiKey(apiKey)
                    .apiVersion("2025-01-01-preview") // Optional:
                    // specify
                    // API
                    // version
                    .build())
            .customModel("gpt-4.1"); // Register custom deployment

    // Create Genkit with Azure Foundry plugin
    // This uses DefaultAzureCredential which works with:
    // - Managed Identity (in Azure)
    // - Azure CLI (az login)
    // - Environment variables (AZURE_CLIENT_ID, AZURE_CLIENT_SECRET,
    // AZURE_TENANT_ID)
    // - Or use apiKey() if you have an API key
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
            .plugin(azureFoundry)
            .plugin(jetty)
            .build();

    // Define a simple greeting flow
    Flow<String, String, Void> greetingFlow =
        genkit.defineFlow("greeting", String.class, String.class, (name) -> "Hello, " + name + "!");

    // Define a joke generator flow using GPT-4
    Flow<String, String, Void> jokeFlow =
        genkit.defineFlow(
            "tellJoke",
            String.class,
            String.class,
            (ctx, topic) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("azure-foundry/gpt-4.1")
                          .prompt("Tell me a short, funny joke about: " + topic)
                          .config(
                              GenerationConfig.builder()
                                  .temperature(0.9)
                                  .maxOutputTokens(200)
                                  .build())
                          .build());

              return response.getText();
            });

    // Define a tool for getting current weather (mock implementation)
    @SuppressWarnings("unchecked")
    Tool<Map<String, Object>, Map<String, Object>> weatherTool =
        genkit.defineTool(
            "getWeather",
            "Gets the current weather for a location",
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("location", Map.of("type", "string", "description", "The city name")),
                "required",
                new String[] {"location"}),
            (Class<Map<String, Object>>) (Class<?>) Map.class,
            (ctx, input) -> {
              String location = (String) input.get("location");
              Map<String, Object> weather = new HashMap<>();
              weather.put("location", location);
              weather.put("temperature", "72°F");
              weather.put("conditions", "Sunny");
              return weather;
            });

    // Define a chat flow using GPT-4
    Flow<String, String, Void> chatFlow =
        genkit.defineFlow(
            "chat",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("azure-foundry/gpt-4.1")
                          .system("You are a helpful AI assistant running on Azure AI Foundry.")
                          .prompt(userMessage)
                          .build());

              return response.getText();
            });

    // Define a flow that uses the weather tool
    Flow<String, String, Void> weatherAssistantFlow =
        genkit.defineFlow(
            "weatherAssistant",
            String.class,
            String.class,
            (ctx, userMessage) -> {
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("azure-foundry/gpt-4.1")
                          .system(
                              "You are a helpful weather assistant. Use the getWeather tool to provide weather information when asked about the weather in a specific location.")
                          .prompt(userMessage)
                          .tools(List.of(weatherTool))
                          .build());

              return response.getText();
            });

    // Define a multi-model comparison flow
    Flow<String, String, Void> compareModelsFlow =
        genkit.defineFlow(
            "compareModels",
            String.class,
            String.class,
            (ctx, question) -> {
              StringBuilder result = new StringBuilder();

              // GPT-4 response
              try {
                ModelResponse gpt4Response =
                    genkit.generate(
                        GenerateOptions.builder()
                            .model("azure-foundry/gpt-4.1")
                            .prompt(question)
                            .build());
                result.append("GPT-4.1:\n").append(gpt4Response.getText()).append("\n\n");
              } catch (Exception e) {
                result.append("GPT-4.1: Not available or not deployed\n\n");
              }

              return result.toString();
            });

    // Define a streaming example flow
    Flow<String, String, Void> streamingFlow =
        genkit.defineFlow(
            "streamingDemo",
            String.class,
            String.class,
            (ctx, prompt) -> {
              System.out.println("\n=== Streaming Response ===");
              StringBuilder fullResponse = new StringBuilder();

              genkit.generateStream(
                  GenerateOptions.builder().model("azure-foundry/gpt-4.1").prompt(prompt).build(),
                  chunk -> {
                    String text = chunk.getText();
                    System.out.print(text);
                    fullResponse.append(text);
                  });

              System.out.println("\n=== End of Stream ===\n");
              return fullResponse.toString();
            });

    System.out.println("Azure AI Foundry Sample Started!");
    System.out.println("=================================");
    System.out.println("Endpoint: " + endpoint);
    System.out.println("Genkit UI available at: http://localhost:3100");
    System.out.println("HTTP endpoints available at: http://localhost:8080");
    System.out.println("");
    System.out.println("Available flows:");
    System.out.println("  - greeting: Simple greeting");
    System.out.println("  - tellJoke: Generate a joke about a topic");
    System.out.println("  - chat: Chat with GPT-4 on Azure Foundry");
    System.out.println("  - weatherAssistant: Weather assistant with tool use");
    System.out.println("  - compareModels: Compare responses from multiple models");
    System.out.println("  - streamingDemo: Demonstrate streaming responses");
    System.out.println("");
    System.out.println("Example requests:");
    System.out.println(
        "  curl -X POST http://localhost:8080/greeting -H 'Content-Type: application/json' -d '{\"data\": \"World\"}'");
    System.out.println(
        "  curl -X POST http://localhost:8080/tellJoke -H 'Content-Type: application/json' -d '{\"data\": \"AI\"}'");
    System.out.println(
        "  curl -X POST http://localhost:8080/chat -H 'Content-Type: application/json' -d '{\"data\": \"What is Azure AI Foundry?\"}'");
    System.out.println(
        "  curl -X POST http://localhost:8080/weatherAssistant -H 'Content-Type: application/json' -d '{\"data\": \"What\\'s the weather in New York?\"}'");
    System.out.println("");
    System.out.println("Note: Ensure the models are deployed in your Azure AI Foundry project.");
    System.out.println("Press Ctrl+C to stop.");

    // Start the server and block
    jetty.start();
  }
}
