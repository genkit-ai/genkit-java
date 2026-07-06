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

package com.google.genkit.plugins.compatoai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.Document;
import com.google.genkit.ai.EmbedRequest;
import com.google.genkit.ai.EmbedResponse;
import com.google.genkit.ai.Embedder;
import com.google.genkit.ai.EmbedderInfo;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/**
 * Embedder implementation for any provider that exposes an OpenAI-compatible {@code /embeddings}
 * endpoint (e.g. Cohere, Mistral).
 *
 * <p>Sends a {@code POST {baseUrl}/embeddings} request with a JSON body of the form {@code
 * {"model": "...", "input": ["text", ...]}} and parses the {@code data[].embedding} arrays.
 */
public class CompatOAIEmbedder extends Embedder {

  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final String apiModelName;
  private final CompatOAIPluginOptions options;
  private final OkHttpClient client;
  private final ObjectMapper objectMapper;

  /**
   * Creates a new CompatOAIEmbedder.
   *
   * @param modelName the Genkit embedder name (e.g. "cohere/embed-v4.0")
   * @param apiModelName the model name sent to the API (e.g. "embed-v4.0")
   * @param label the display label (e.g. "Cohere embed-v4.0")
   * @param options the plugin options
   */
  public CompatOAIEmbedder(
      String modelName, String apiModelName, String label, CompatOAIPluginOptions options) {
    super(
        modelName,
        createEmbedderInfo(label),
        (ctx, req) -> {
          throw new GenkitException("Handler not initialized");
        });
    this.apiModelName = apiModelName;
    this.options = options;
    this.objectMapper = new ObjectMapper();
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(options.getTimeout(), TimeUnit.SECONDS)
            .readTimeout(options.getTimeout(), TimeUnit.SECONDS)
            .writeTimeout(options.getTimeout(), TimeUnit.SECONDS)
            .build();
  }

  private static EmbedderInfo createEmbedderInfo(String label) {
    EmbedderInfo info = new EmbedderInfo();
    info.setLabel(label);
    return info;
  }

  @Override
  public EmbedResponse run(ActionContext context, EmbedRequest request) {
    if (request == null) {
      throw new GenkitException(
          "Embed request is required. Please provide an input with documents to embed.");
    }
    if (request.getDocuments() == null || request.getDocuments().isEmpty()) {
      throw new GenkitException("Embed request must contain at least one document to embed.");
    }
    try {
      return callApi(request);
    } catch (IOException e) {
      throw new GenkitException("Embedding API call failed", e);
    }
  }

  private String buildUrl() {
    StringBuilder url = new StringBuilder(options.getBaseUrl());
    url.append("/embeddings");
    if (options.getQueryParams() != null && !options.getQueryParams().isEmpty()) {
      url.append("?");
      boolean first = true;
      for (java.util.Map.Entry<String, String> entry : options.getQueryParams().entrySet()) {
        if (!first) {
          url.append("&");
        }
        url.append(entry.getKey()).append("=").append(entry.getValue());
        first = false;
      }
    }
    return url.toString();
  }

  private EmbedResponse callApi(EmbedRequest request) throws IOException {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", apiModelName);

    ArrayNode input = requestBody.putArray("input");
    for (Document doc : request.getDocuments()) {
      String text = doc.text();
      if (text == null || text.isEmpty()) {
        // Throw rather than skip: skipping would make the returned embeddings list shorter than the
        // documents list, breaking the 1-to-1 index mapping the caller relies on.
        throw new GenkitException("Document text cannot be null or empty");
      }
      input.add(text);
    }

    Request.Builder requestBuilder =
        new Request.Builder()
            .url(buildUrl())
            .header("Authorization", "Bearer " + options.getApiKey())
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE));
    if (options.getOrganization() != null) {
      requestBuilder.header("OpenAI-Organization", options.getOrganization());
    }

    try (Response response = client.newCall(requestBuilder.build()).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No error body";
        throw new GenkitException("Embedding API error: " + response.code() + " - " + errorBody);
      }
      return parseResponse(response.body().string());
    }
  }

  private EmbedResponse parseResponse(String responseBody) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);
    List<EmbedResponse.Embedding> embeddings = new ArrayList<>();

    JsonNode dataNode = root.get("data");
    if (dataNode != null && dataNode.isArray()) {
      for (JsonNode item : dataNode) {
        JsonNode embeddingNode = item.get("embedding");
        if (embeddingNode != null && embeddingNode.isArray()) {
          float[] values = new float[embeddingNode.size()];
          for (int i = 0; i < embeddingNode.size(); i++) {
            values[i] = (float) embeddingNode.get(i).asDouble();
          }
          embeddings.add(new EmbedResponse.Embedding(values));
        }
      }
    }
    return new EmbedResponse(embeddings);
  }
}
