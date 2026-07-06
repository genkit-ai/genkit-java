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

package com.google.genkit.plugins.awsbedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * AWS Bedrock embedder implementation for Genkit.
 *
 * <p>Uses the AWS Bedrock {@code InvokeModel} API (via HTTP with AWS SigV4 signing) to generate
 * embeddings. Handles the differing request/response shapes of Amazon Titan Text Embeddings and
 * Cohere Embed models.
 */
public class AwsBedrockEmbedder extends Embedder {

  private final String modelId;
  private final AwsBedrockPluginOptions options;
  private final OkHttpClient client;
  private final ObjectMapper objectMapper;

  /**
   * Creates a new AwsBedrockEmbedder.
   *
   * @param modelId the Bedrock embedding model ID (e.g., "amazon.titan-embed-text-v2:0")
   * @param options the plugin options
   */
  public AwsBedrockEmbedder(String modelId, AwsBedrockPluginOptions options) {
    super(
        "aws-bedrock/" + modelId,
        createEmbedderInfo(modelId),
        (ctx, req) -> {
          throw new GenkitException("Handler not initialized");
        });
    this.modelId = modelId;
    this.options = options;
    this.objectMapper = new ObjectMapper();
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();
  }

  private static EmbedderInfo createEmbedderInfo(String modelId) {
    EmbedderInfo info = new EmbedderInfo();
    info.setLabel("AWS Bedrock " + modelId);
    if (modelId.contains("titan-embed-text-v2")) {
      info.setDimensions(1024);
    } else if (modelId.contains("titan-embed-text-v1")) {
      info.setDimensions(1536);
    } else if (modelId.startsWith("cohere.embed")) {
      info.setDimensions(1024);
    }
    return info;
  }

  private boolean isCohere() {
    return modelId.startsWith("cohere.embed") || modelId.contains("cohere.embed");
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
      List<EmbedResponse.Embedding> embeddings = new ArrayList<>();
      for (Document doc : request.getDocuments()) {
        String text = doc.text();
        if (text == null || text.isEmpty()) {
          // Throw rather than skip: skipping would make the embeddings list shorter than the
          // documents list, breaking the 1-to-1 index mapping the caller relies on.
          throw new GenkitException("Document text cannot be null or empty");
        }
        embeddings.add(embedOne(text));
      }
      return new EmbedResponse(embeddings);
    } catch (IOException e) {
      throw new GenkitException("AWS Bedrock Embedding API call failed", e);
    }
  }

  private EmbedResponse.Embedding embedOne(String text) throws IOException {
    ObjectNode body = objectMapper.createObjectNode();
    if (isCohere()) {
      body.putArray("texts").add(text);
      body.put("input_type", "search_document");
    } else {
      // Amazon Titan Text Embeddings
      body.put("inputText", text);
    }

    String path = String.format("/model/%s/invoke", modelId);
    String host = AwsBedrockSigner.runtimeHost(options);
    Request httpRequest = AwsBedrockSigner.signRequest(options, host, path, body.toString());

    try (Response response = client.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No error body";
        throw new GenkitException(
            "AWS Bedrock Embedding API error: " + response.code() + " - " + errorBody);
      }
      return parseEmbedding(objectMapper.readTree(response.body().string()));
    }
  }

  private EmbedResponse.Embedding parseEmbedding(JsonNode root) {
    // Titan: {"embedding": [...]}. Cohere: {"embeddings": [[...]]} or {"embeddings": {"float":
    // [[...]]}}.
    JsonNode vector = root.get("embedding");
    if (vector == null) {
      JsonNode embeddings = root.get("embeddings");
      if (embeddings != null && embeddings.isArray() && embeddings.size() > 0) {
        vector = embeddings.get(0);
      } else if (embeddings != null && embeddings.has("float")) {
        JsonNode floats = embeddings.get("float");
        if (floats.isArray() && floats.size() > 0) {
          vector = floats.get(0);
        }
      }
    }
    if (vector == null || !vector.isArray()) {
      throw new GenkitException(
          "AWS Bedrock embedding response did not contain an embedding vector");
    }
    float[] values = new float[vector.size()];
    for (int i = 0; i < vector.size(); i++) {
      values[i] = (float) vector.get(i).asDouble();
    }
    return new EmbedResponse.Embedding(values);
  }
}
