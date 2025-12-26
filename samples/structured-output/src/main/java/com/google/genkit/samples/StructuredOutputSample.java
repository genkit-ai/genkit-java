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
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Tool;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import com.google.genkit.prompt.ExecutablePrompt;

import java.util.List;

/**
 * Sample application demonstrating structured output with type-safe generation using flows.
 * 
 * This example shows:
 * - Using @JsonPropertyDescription for field descriptions
 * - Using @JsonProperty(required = true) for required fields
 * - Generating typed objects with genkit.generate()
 * - Using flows with typed inputs and outputs
 * - Using tools with typed inputs and outputs
 * - Loading prompts from dotprompt files
 * 
 * All examples are exposed as flows that can be called via HTTP when running with Jetty plugin.
 */
public class StructuredOutputSample {

  public static void main(String[] args) throws Exception {
    // Initialize Genkit with OpenAI and Jetty plugins
    Genkit genkit = Genkit.builder()
        .plugin(OpenAIPlugin.create())
        .plugin(new JettyPlugin(JettyPluginOptions.builder().port(8080).build()))
        .build();

    System.out.println("=== Structured Output Examples ===\n");

    // Define all flows
    defineFlows(genkit);

    System.out.println("Flows registered and available via HTTP on port 3400");
    System.out.println("Available flows:");
    System.out.println("  - POST http://localhost:8080/generateMenuItem");
    System.out.println("  - POST http://localhost:8080/generateDishFromCuisine");
    System.out.println("  - POST http://localhost:8080/generateRecipeWithTool");
    System.out.println("  - POST http://localhost:8080/generateProfile");
    System.out.println();
    System.out.println("Access the Genkit Developer UI to trigger flows interactively.");
    System.out.println("Server running. Press Ctrl+C to stop.");
  }

  private static void defineFlows(Genkit genkit) {
    // Flow 1: Simple structured output
    genkit.defineFlow(
        "generateMenuItem",
        MenuItemRequest.class,
        MenuItem.class,
        (ctx, request) -> {
          return genkit.generate(
              GenerateOptions.<MenuItem>builder()
                  .model("openai/gpt-4o-mini")
                  .prompt(request.getDescription())
                  .outputClass(MenuItem.class)
                  .build()
          );
        }
    );

    // Flow 2: Structured output with dotprompt
    genkit.defineFlow(
        "generateDishFromCuisine",
        DishRequest.class,
        MenuItem.class,
        (ctx, request) -> {
          ExecutablePrompt<DishRequest> prompt = genkit.prompt("italian-dish", DishRequest.class);
          return prompt.generate(request, MenuItem.class);
        }
    );

    // Flow 3: Structured output with tool
    Tool<RecipeRequest, MenuItem> recipeGenerator = genkit.defineTool(
        "generateRecipe",
        "Generates a recipe based on cuisine and dietary preferences",
        (ctx, recipeReq) -> {
          return new MenuItem(
              recipeReq.getCuisine() + " Special",
              "A delicious " + recipeReq.getCuisine() + " dish" +
                  (recipeReq.getDietaryRestrictions() != null && !recipeReq.getDietaryRestrictions().isEmpty() 
                      ? " (suitable for " + String.join(", ", recipeReq.getDietaryRestrictions()) + ")" : ""),
              25.99,
              recipeReq.getMaxPrepTime() != null ? recipeReq.getMaxPrepTime() : 30,
              recipeReq.getDietaryRestrictions()
          );
        },
        RecipeRequest.class,
        MenuItem.class
    );

    genkit.defineFlow(
        "generateRecipeWithTool",
        RecipeRequest.class,
        MenuItem.class,
        (ctx, request) -> {
          return genkit.generate(
              GenerateOptions.<MenuItem>builder()
                  .model("openai/gpt-4o-mini")
                  .prompt("I want a " + request.getCuisine() + " recipe" +
                      (request.getDietaryRestrictions() != null && !request.getDietaryRestrictions().isEmpty() 
                          ? " that's " + String.join(" and ", request.getDietaryRestrictions()) : "") +
                      (request.getMaxPrepTime() != null 
                          ? " and takes no more than " + request.getMaxPrepTime() + " minutes" : "") +
                      ". Use the generateRecipe tool to create it.")
                  .tools(List.of(recipeGenerator))
                  .outputClass(MenuItem.class)
                  .build()
          );
        }
    );

    // Flow 4: Complex nested structures
    genkit.defineFlow(
        "generateProfile",
        ProfileRequest.class,
        PersonProfile.class,
        (ctx, request) -> {
          return genkit.generate(
              GenerateOptions.<PersonProfile>builder()
                  .model("openai/gpt-4o-mini")
                  .prompt(request.getCharacterDescription())
                  .outputClass(PersonProfile.class)
                  .build()
          );
        }
    );
  }
}
