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
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.spring.SpringPlugin;
import com.google.genkit.plugins.spring.SpringPluginOptions;

/**
 * Sample application demonstrating Genkit with Spring Boot.
 *
 * <p>
 * This sample shows how to:
 * <ul>
 * <li>Set up Genkit with the Spring Boot plugin</li>
 * <li>Define simple flows</li>
 * <li>Expose flows as REST endpoints</li>
 * <li>Integrate with Google GenAI for AI-powered flows</li>
 * </ul>
 *
 * <p>
 * Run this sample with:
 * 
 * <pre>
 * cd samples/spring
 * ./run.sh
 * </pre>
 *
 * <p>
 * Then test the endpoints:
 * 
 * <pre>
 * # Health check
 * curl http://localhost:8080/health
 *
 * # List flows
 * curl http://localhost:8080/api/flows
 *
 * # Execute greet flow
 * curl -X POST http://localhost:8080/api/flows/greet \
 *   -H "Content-Type: application/json" \
 *   -d '"World"'
 *
 * # Execute reverse flow
 * curl -X POST http://localhost:8080/api/flows/reverse \
 *   -H "Content-Type: application/json" \
 *   -d '"Hello"'
 * </pre>
 */
public class SpringSample {

  public static void main(String[] args) throws Exception {
    // Create the Spring plugin with custom options
    SpringPlugin spring = new SpringPlugin(
        SpringPluginOptions.builder().port(8080).host("0.0.0.0").basePath("/api/flows").build());

    // Create Genkit instance with plugins
    Genkit genkit = Genkit.builder().plugin(spring).plugin(new GoogleGenAIPlugin()).build();

    // Define a simple greeting flow
    genkit.defineFlow("greet", String.class, String.class, (ctx, name) -> {
      return "Hello, " + name + "!";
    });

    // Define a flow that reverses a string
    genkit.defineFlow("reverse", String.class, String.class, (ctx, input) -> {
      return new StringBuilder(input).reverse().toString();
    });

    // Define a flow that returns object data
    genkit.defineFlow("info", String.class, java.util.Map.class, (ctx, topic) -> {
      java.util.Map<String, Object> result = new java.util.HashMap<>();
      result.put("topic", topic);
      result.put("timestamp", System.currentTimeMillis());
      result.put("version", "1.0.0");
      return result;
    });

    System.out.println("=".repeat(60));
    System.out.println("Genkit Spring Boot Sample");
    System.out.println("=".repeat(60));
    System.out.println();
    System.out.println("Starting server on http://localhost:8080");
    System.out.println();
    System.out.println("Available endpoints:");
    System.out.println("  GET  /health           - Health check");
    System.out.println("  GET  /api/flows        - List all flows");
    System.out.println("  POST /api/flows/greet  - Greeting flow");
    System.out.println("  POST /api/flows/reverse - Reverse string flow");
    System.out.println("  POST /api/flows/info   - Info flow");
    System.out.println();
    System.out.println("Example:");
    System.out.println("  curl -X POST http://localhost:8080/api/flows/greet \\");
    System.out.println("    -H \"Content-Type: application/json\" \\");
    System.out.println("    -d '\"World\"'");
    System.out.println();
    System.out.println("=".repeat(60));

    // Start the server (blocks until stopped)
    spring.start();
  }
}
