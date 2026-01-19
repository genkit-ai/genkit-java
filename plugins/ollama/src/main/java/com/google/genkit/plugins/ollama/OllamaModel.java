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

package com.google.genkit.plugins.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.*;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ollama model implementation for Genkit.
 *
 * <p>Supports local Ollama models with both synchronous and streaming generation. Ollama must be
 * running locally (or at the configured host).
 */
public class OllamaModel implements Model {

  private static final Logger logger = LoggerFactory.getLogger(OllamaModel.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final String modelName;
  private final OllamaPluginOptions options;
  private final OkHttpClient client;
  private final ObjectMapper objectMapper;
  private final ModelInfo info;

  /**
   * Creates a new OllamaModel.
   *
   * @param modelName the model name (e.g., "llama3.2", "mistral")
   * @param options the plugin options
   */
  public OllamaModel(String modelName, OllamaPluginOptions options) {
    this.modelName = modelName;
    this.options = options;
    this.objectMapper = new ObjectMapper();
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(options.getTimeout(), TimeUnit.SECONDS)
            .readTimeout(options.getTimeout(), TimeUnit.SECONDS)
            .writeTimeout(options.getTimeout(), TimeUnit.SECONDS)
            .build();
    this.info = createModelInfo();
  }

  private ModelInfo createModelInfo() {
    ModelInfo info = new ModelInfo();
    info.setLabel("Ollama " + modelName);

    ModelInfo.ModelCapabilities caps = new ModelInfo.ModelCapabilities();
    caps.setMultiturn(true);
    // LLaVA and similar models support images
    caps.setMedia(modelName.contains("llava") || modelName.contains("bakllava"));
    caps.setTools(false); // Most Ollama models don't support function calling natively
    caps.setSystemRole(true);
    caps.setOutput(Set.of("text", "json"));
    info.setSupports(caps);

    return info;
  }

  @Override
  public String getName() {
    return "ollama/" + modelName;
  }

  @Override
  public ModelInfo getInfo() {
    return info;
  }

  @Override
  public boolean supportsStreaming() {
    return true;
  }

  @Override
  public ModelResponse run(ActionContext context, ModelRequest request) {
    try {
      return callOllama(request, false);
    } catch (IOException e) {
      throw new GenkitException("Ollama API call failed", e);
    }
  }

  @Override
  public ModelResponse run(
      ActionContext context, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
    if (streamCallback == null) {
      return run(context, request);
    }
    try {
      return callOllamaStreaming(request, streamCallback);
    } catch (Exception e) {
      throw new GenkitException("Ollama streaming API call failed", e);
    }
  }

  private ModelResponse callOllama(ModelRequest request, boolean stream) throws IOException {
    ObjectNode requestBody = buildRequestBody(request);
    requestBody.put("stream", false);

    Request httpRequest =
        new Request.Builder()
            .url(options.getBaseUrl() + "/api/chat")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
            .build();

    try (Response response = client.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No error body";
        throw new GenkitException("Ollama API error: " + response.code() + " - " + errorBody);
      }

      String responseBody = response.body().string();
      return parseResponse(responseBody);
    }
  }

  private ModelResponse callOllamaStreaming(
      ModelRequest request, Consumer<ModelResponseChunk> streamCallback) throws IOException {
    ObjectNode requestBody = buildRequestBody(request);
    requestBody.put("stream", true);

    Request httpRequest =
        new Request.Builder()
            .url(options.getBaseUrl() + "/api/chat")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
            .build();

    StringBuilder fullContent = new StringBuilder();
    boolean done = false;
    int promptTokens = 0;
    int completionTokens = 0;

    try (Response response = client.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No error body";
        throw new GenkitException(
            "Ollama streaming API error: " + response.code() + " - " + errorBody);
      }

      BufferedReader reader =
          new BufferedReader(new InputStreamReader(response.body().byteStream()));
      String line;

      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }

        try {
          JsonNode chunk = objectMapper.readTree(line);

          // Check if done
          if (chunk.has("done") && chunk.get("done").asBoolean()) {
            done = true;
            // Capture final usage stats
            if (chunk.has("prompt_eval_count")) {
              promptTokens = chunk.get("prompt_eval_count").asInt();
            }
            if (chunk.has("eval_count")) {
              completionTokens = chunk.get("eval_count").asInt();
            }
            break;
          }

          // Extract content from message
          JsonNode messageNode = chunk.get("message");
          if (messageNode != null && messageNode.has("content")) {
            String text = messageNode.get("content").asText();
            if (text != null && !text.isEmpty()) {
              fullContent.append(text);

              // Send chunk to callback
              ModelResponseChunk responseChunk = ModelResponseChunk.text(text);
              responseChunk.setIndex(0);
              streamCallback.accept(responseChunk);
            }
          }
        } catch (Exception e) {
          logger.warn("Error parsing streaming chunk: {}", line, e);
        }
      }
    }

    // Build the final response
    ModelResponse modelResponse = new ModelResponse();
    List<Candidate> candidates = new ArrayList<>();
    Candidate candidate = new Candidate();

    Message message = new Message();
    message.setRole(Role.MODEL);
    List<Part> parts = new ArrayList<>();

    if (fullContent.length() > 0) {
      Part textPart = new Part();
      textPart.setText(fullContent.toString());
      parts.add(textPart);
    }

    message.setContent(parts);
    candidate.setMessage(message);
    candidate.setFinishReason(done ? FinishReason.STOP : FinishReason.OTHER);

    candidates.add(candidate);
    modelResponse.setCandidates(candidates);

    // Set usage if available
    if (promptTokens > 0 || completionTokens > 0) {
      Usage usage = new Usage();
      usage.setInputTokens(promptTokens);
      usage.setOutputTokens(completionTokens);
      usage.setTotalTokens(promptTokens + completionTokens);
      modelResponse.setUsage(usage);
    }

    return modelResponse;
  }

  private ObjectNode buildRequestBody(ModelRequest request) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelName);

    // Build context prefix from documents if provided
    String contextPrefix = buildContextPrefix(request);
    boolean contextPrefixAdded = false;

    // Convert messages to Ollama format
    ArrayNode messages = body.putArray("messages");

    for (Message message : request.getMessages()) {
      ObjectNode msg = messages.addObject();
      String role = convertRole(message.getRole());
      msg.put("role", role);

      List<Part> content = message.getContent();
      StringBuilder textContent = new StringBuilder();
      List<String> images = new ArrayList<>();

      // Add context prefix to first user message
      if (!contextPrefixAdded && contextPrefix != null && message.getRole() == Role.USER) {
        contextPrefixAdded = true;
        textContent.append(contextPrefix);
      }

      for (Part part : content) {
        if (part.getText() != null) {
          textContent.append(part.getText());
        } else if (part.getMedia() != null) {
          // Handle images for multimodal models like LLaVA
          String url = part.getMedia().getUrl();
          if (url.startsWith("data:")) {
            // Extract base64 data
            String[] parts2 = url.split(",", 2);
            if (parts2.length == 2) {
              images.add(parts2[1]);
            }
          } else {
            // For URL-based images, we need to convert to base64
            // For now, just pass the URL (Ollama may not support this)
            images.add(url);
          }
        }
      }

      msg.put("content", textContent.toString());

      // Add images if present (for multimodal models)
      if (!images.isEmpty()) {
        ArrayNode imagesArray = msg.putArray("images");
        for (String image : images) {
          imagesArray.add(image);
        }
      }
    }

    // Add options (generation config)
    Map<String, Object> config = request.getConfig();
    if (config != null) {
      ObjectNode options = body.putObject("options");

      if (config.containsKey("temperature")) {
        options.put("temperature", ((Number) config.get("temperature")).doubleValue());
      }
      if (config.containsKey("maxOutputTokens")) {
        options.put("num_predict", ((Number) config.get("maxOutputTokens")).intValue());
      }
      if (config.containsKey("topP")) {
        options.put("top_p", ((Number) config.get("topP")).doubleValue());
      }
      if (config.containsKey("topK")) {
        options.put("top_k", ((Number) config.get("topK")).intValue());
      }
      if (config.containsKey("seed")) {
        options.put("seed", ((Number) config.get("seed")).intValue());
      }
      if (config.containsKey("stopSequences")) {
        ArrayNode stop = options.putArray("stop");
        @SuppressWarnings("unchecked")
        List<String> stopSequences = (List<String>) config.get("stopSequences");
        for (String seq : stopSequences) {
          stop.add(seq);
        }
      }
      if (config.containsKey("repeatPenalty")) {
        options.put("repeat_penalty", ((Number) config.get("repeatPenalty")).doubleValue());
      }
    }

    // Handle JSON output format
    OutputConfig output = request.getOutput();
    if (output != null && output.getFormat() == OutputFormat.JSON) {
      body.put("format", "json");
    }

    return body;
  }

  private String convertRole(Role role) {
    switch (role) {
      case SYSTEM:
        return "system";
      case USER:
        return "user";
      case MODEL:
        return "assistant";
      case TOOL:
        return "user"; // Ollama doesn't have a tool role
      default:
        return "user";
    }
  }

  private String buildContextPrefix(ModelRequest request) {
    List<Document> contextDocs = request.getContext();
    if (contextDocs == null || contextDocs.isEmpty()) {
      return null;
    }

    StringBuilder sb = new StringBuilder();
    sb.append("Context:\n\n");
    for (int i = 0; i < contextDocs.size(); i++) {
      Document doc = contextDocs.get(i);
      sb.append("[").append(i + 1).append("] ");
      String text = doc.text();
      if (text != null && !text.isEmpty()) {
        sb.append(text);
      }
      sb.append("\n\n");
    }
    return sb.toString();
  }

  private ModelResponse parseResponse(String responseBody) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);

    ModelResponse response = new ModelResponse();
    List<Candidate> candidates = new ArrayList<>();
    Candidate candidate = new Candidate();

    Message message = new Message();
    message.setRole(Role.MODEL);
    List<Part> parts = new ArrayList<>();

    // Parse message content
    JsonNode messageNode = root.get("message");
    if (messageNode != null) {
      JsonNode contentNode = messageNode.get("content");
      if (contentNode != null && !contentNode.isNull()) {
        Part part = new Part();
        part.setText(contentNode.asText());
        parts.add(part);
      }
    }

    message.setContent(parts);
    candidate.setMessage(message);

    // Check if generation is complete
    boolean done = root.has("done") && root.get("done").asBoolean();
    candidate.setFinishReason(done ? FinishReason.STOP : FinishReason.OTHER);

    candidates.add(candidate);
    response.setCandidates(candidates);

    // Parse usage
    Usage usage = new Usage();
    if (root.has("prompt_eval_count")) {
      usage.setInputTokens(root.get("prompt_eval_count").asInt());
    }
    if (root.has("eval_count")) {
      usage.setOutputTokens(root.get("eval_count").asInt());
    }
    if (usage.getInputTokens() > 0 || usage.getOutputTokens() > 0) {
      usage.setTotalTokens(usage.getInputTokens() + usage.getOutputTokens());
      response.setUsage(usage);
    }

    return response;
  }
}
