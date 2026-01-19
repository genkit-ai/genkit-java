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

package com.google.genkit.samples.firebase.functions;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.genkit.Genkit;
import com.google.genkit.ai.*;
import com.google.genkit.plugins.firebase.FirebasePlugin;
import com.google.genkit.plugins.firebase.functions.OnCallGenkit;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import java.io.IOException;

/**
 * Firebase Cloud Function that generates poems using Genkit.
 *
 * <p>This is a simple example showing how to expose a Genkit flow as a Firebase Cloud Function.
 *
 * <p>Deploy with:
 *
 * <pre>
 * gcloud functions deploy generatePoem \
 *   --runtime java21 \
 *   --trigger-http \
 *   --entry-point com.google.genkit.samples.firebase.functions.GeneratePoemFunction \
 *   --set-env-vars GEMINI_API_KEY=$GEMINI_API_KEY
 * </pre>
 */
public class GeneratePoemFunction implements HttpFunction {

  private final OnCallGenkit genkitFunction;

  public GeneratePoemFunction() {
    String apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalStateException("GEMINI_API_KEY environment variable is required");
    }

    // Build Genkit with Google GenAI plugin
    Genkit genkit =
        Genkit.builder()
            .plugin(GoogleGenAIPlugin.create(apiKey))
            .plugin(FirebasePlugin.builder().build())
            .build();

    // Define the poem generation flow
    genkit.defineFlow(
        "generatePoem",
        String.class,
        String.class,
        (ctx, topic) -> {
          ModelResponse response =
              genkit.generate(
                  GenerateOptions.builder()
                      .model("googleai/gemini-2.0-flash")
                      .prompt("Write a short, creative poem about: " + topic)
                      .config(
                          GenerationConfig.builder().temperature(0.9).maxOutputTokens(500).build())
                      .build());

          return response.getText();
        });

    // Build the function - allow unauthenticated access for this example
    this.genkitFunction = OnCallGenkit.fromFlow(genkit, "generatePoem");
  }

  @Override
  public void service(HttpRequest request, HttpResponse response) throws IOException {
    try {
      genkitFunction.service(request, response);
    } catch (Exception e) {
      response.setStatusCode(500);
      response.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
    }
  }
}
