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

package com.google.genkit.plugins.googlegenai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.Media;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini Omni video generation and editing model using the Gemini Interactions API.
 *
 * <p>Gemini Omni ({@code gemini-omni-flash-preview}) natively processes text, image, audio, and
 * video and produces video (with audio). It is served by the Interactions API ({@code POST
 * /v1beta/interactions}) rather than {@code generateContent}, and supports <b>conversational
 * editing</b>: each turn can build on a previous result.
 *
 * <p><b>Conversational editing.</b> The Interactions API is stateful server-side. This model
 * bridges it to Genkit's stateless {@link Model} interface as follows:
 *
 * <ul>
 *   <li>On the first turn, send a prompt (and optional media). The response's {@code custom} map
 *       carries the returned {@code interactionId}.
 *   <li>To iteratively edit, pass that id back as the {@code previousInteractionId} config option
 *       on the next {@code generate} call, with the edit instruction as the prompt. The model
 *       applies the change while preserving elements you did not mention.
 * </ul>
 *
 * <p>Supported config options (via the request config map): {@code previousInteractionId}, {@code
 * aspectRatio} (e.g. "16:9"), {@code duration} (e.g. "10s"), {@code delivery} ("inline" (default) |
 * "uri"), {@code task} ("text_to_video" | "image_to_video"), {@code thinkingLevel}, {@code
 * maxOutputTokens}.
 *
 * <p>Only the Gemini Developer API (API key) is supported; Vertex AI is not.
 *
 * <p>Note: the Interactions API is in preview; request/response field shapes may evolve.
 */
public class OmniModel implements Model {

  private static final Logger logger = LoggerFactory.getLogger(OmniModel.class);

  private final String modelName;
  private final GoogleGenAIPluginOptions options;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ModelInfo info;

  /**
   * Creates a new OmniModel.
   *
   * @param modelName the model name (e.g., "gemini-omni-flash-preview")
   * @param options the plugin options
   */
  public OmniModel(String modelName, GoogleGenAIPluginOptions options) {
    this.modelName = modelName;
    this.options = options;
    this.objectMapper = new ObjectMapper();
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(options.getTimeout())).build();
    this.info = createModelInfo();
  }

  private ModelInfo createModelInfo() {
    ModelInfo info = new ModelInfo();
    info.setLabel("Google AI " + modelName);

    ModelInfo.ModelCapabilities caps = new ModelInfo.ModelCapabilities();
    caps.setMultiturn(true); // conversational editing
    caps.setMedia(true); // accepts text/image/audio/video input
    caps.setTools(false);
    caps.setSystemRole(false);
    caps.setOutput(Set.of("media"));
    info.setSupports(caps);

    return info;
  }

  @Override
  public String getName() {
    return "googleai/" + modelName;
  }

  @Override
  public ModelInfo getInfo() {
    return info;
  }

  @Override
  public boolean supportsStreaming() {
    return false;
  }

  @Override
  public ModelResponse run(ActionContext context, ModelRequest request) {
    if (options.isVertexAI()) {
      throw new GenkitException(
          "Gemini Omni (Interactions API) is not supported on Vertex AI. Use the Gemini Developer"
              + " API with an API key.");
    }
    try {
      return callInteractions(request);
    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("Gemini Omni API call failed: " + e.getMessage(), e);
    }
  }

  @Override
  public ModelResponse run(
      ActionContext context, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
    // The Interactions API is not streamed; return the full result.
    return run(context, request);
  }

  private ModelResponse callInteractions(ModelRequest request) throws Exception {
    Map<String, Object> config = request.getConfig();
    String previousInteractionId = configString(config, "previousInteractionId", null);
    String delivery = configString(config, "delivery", null); // "inline" (default) | "uri"
    String aspectRatio = configString(config, "aspectRatio", null); // e.g. "16:9"
    String duration = configString(config, "duration", null); // e.g. "10s"
    String thinkingLevel = configString(config, "thinkingLevel", "high");
    int maxOutputTokens = configInt(config, "maxOutputTokens", 65536);
    boolean isEdit = previousInteractionId != null && !previousInteractionId.isEmpty();

    // Collect the latest user prompt text and any media parts.
    String promptText = latestUserText(request);
    List<Part> mediaParts = latestUserMedia(request);
    String task =
        configString(config, "task", mediaParts.isEmpty() ? "text_to_video" : "image_to_video");

    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", "models/" + modelName);

    if (isEdit) {
      // Conversational editing turn: reference the prior interaction.
      body.put("previous_interaction_id", previousInteractionId);
      body.put("input", promptText);
    } else if (mediaParts.isEmpty()) {
      // Text-to-video: input is a plain prompt string.
      body.put("input", promptText);
    } else {
      // Media-conditioned generation: input is an array of typed objects.
      ArrayNode input = body.putArray("input");
      if (promptText != null && !promptText.isEmpty()) {
        ObjectNode textNode = input.addObject();
        textNode.put("type", "text");
        textNode.put("text", promptText);
      }
      for (Part part : mediaParts) {
        input.add(mediaToInput(part.getMedia()));
      }
    }

    // Every turn requests video output.
    body.putArray("response_modalities").add("video");
    ObjectNode responseFormat = body.putObject("response_format");
    responseFormat.put("type", "video");
    if (aspectRatio != null) {
      responseFormat.put("aspect_ratio", aspectRatio);
    }
    if (duration != null) {
      responseFormat.put("duration", duration);
    }
    if (delivery != null) {
      // Supported values: "inline" (base64, default) or "uri".
      responseFormat.put("delivery", delivery);
    }

    // Generation config applies to the initial generation turn.
    if (!isEdit) {
      ObjectNode generationConfig = body.putObject("generation_config");
      generationConfig.put("max_output_tokens", maxOutputTokens);
      if (thinkingLevel != null && !thinkingLevel.isEmpty()) {
        generationConfig.put("thinking_level", thinkingLevel);
      }
      ObjectNode videoConfig = generationConfig.putObject("video_config");
      videoConfig.put("task", task);
    }

    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/interactions"))
            .timeout(Duration.ofSeconds(options.getTimeout()))
            .header("x-goog-api-key", options.getApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

    HttpResponse<String> response =
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new GenkitException(
          "Gemini Omni API error: " + response.statusCode() + " - " + response.body());
    }

    return parseResponse(objectMapper.readTree(response.body()));
  }

  private ObjectNode mediaToInput(Media media) {
    // Best-effort mapping of inline media to an Interactions API input object.
    ObjectNode node = objectMapper.createObjectNode();
    String contentType = media.getContentType() != null ? media.getContentType() : "";
    String type = contentType.startsWith("video/") ? "video" : "image";
    node.put("type", type);
    String url = media.getUrl();
    ObjectNode source = node.putObject("source");
    if (url != null && url.startsWith("data:")) {
      String[] parts = url.split(",", 2);
      String mediaType = parts[0].substring(5, parts[0].indexOf(';'));
      source.put("type", "base64");
      source.put("media_type", mediaType);
      source.put("data", parts.length > 1 ? parts[1] : "");
    } else {
      source.put("type", "uri");
      source.put("uri", url);
    }
    return node;
  }

  private ModelResponse parseResponse(JsonNode root) {
    ModelResponse modelResponse = new ModelResponse();
    List<Candidate> candidates = new ArrayList<>();
    Candidate candidate = new Candidate();

    Message message = new Message();
    message.setRole(Role.MODEL);
    List<Part> parts = new ArrayList<>();

    // Walk the interaction steps and collect video output from the model_output step(s).
    JsonNode steps = root.get("steps");
    if (steps != null && steps.isArray()) {
      for (JsonNode step : steps) {
        if (!"model_output".equals(step.path("type").asText())) {
          continue;
        }
        JsonNode content = step.get("content");
        if (content == null || !content.isArray()) {
          continue;
        }
        for (JsonNode item : content) {
          if (!"video".equals(item.path("type").asText())) {
            continue;
          }
          String mimeType = item.path("mime_type").asText("video/mp4");
          String url;
          if (item.hasNonNull("data")) {
            url = "data:" + mimeType + ";base64," + item.get("data").asText();
          } else if (item.hasNonNull("uri")) {
            url = item.get("uri").asText();
          } else {
            continue;
          }
          Part videoPart = new Part();
          videoPart.setMedia(new Media(mimeType, url));
          parts.add(videoPart);
        }
      }
    }

    message.setContent(parts);
    candidate.setMessage(message);
    candidate.setFinishReason(FinishReason.STOP);

    // Expose the interaction id so the caller can continue editing (previousInteractionId).
    String interactionId = root.path("id").asText(null);
    String status = root.path("status").asText(null);
    if (interactionId != null) {
      Map<String, Object> custom = new HashMap<>();
      custom.put("interactionId", interactionId);
      if (status != null) {
        custom.put("status", status);
      }
      candidate.setCustom(custom);
      modelResponse.setCustom(custom);
    }

    if (parts.isEmpty()) {
      logger.warn("Gemini Omni response contained no video output (status: {})", status);
    }

    candidates.add(candidate);
    modelResponse.setCandidates(candidates);
    return modelResponse;
  }

  private static String configString(Map<String, Object> config, String key, String defaultValue) {
    if (config == null) {
      return defaultValue;
    }
    Object value = config.get(key);
    return value != null ? value.toString() : defaultValue;
  }

  private static int configInt(Map<String, Object> config, String key, int defaultValue) {
    if (config == null) {
      return defaultValue;
    }
    Object value = config.get(key);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value != null) {
      try {
        return Integer.parseInt(value.toString());
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  private String latestUserText(ModelRequest request) {
    if (request.getMessages() == null) {
      return "";
    }
    for (int i = request.getMessages().size() - 1; i >= 0; i--) {
      Message message = request.getMessages().get(i);
      if (message.getRole() != Role.USER) {
        continue;
      }
      StringBuilder sb = new StringBuilder();
      for (Part part : message.getContent()) {
        if (part.getText() != null) {
          if (sb.length() > 0) {
            sb.append("\n");
          }
          sb.append(part.getText());
        }
      }
      return sb.toString();
    }
    return "";
  }

  private List<Part> latestUserMedia(ModelRequest request) {
    List<Part> media = new ArrayList<>();
    if (request.getMessages() == null) {
      return media;
    }
    for (int i = request.getMessages().size() - 1; i >= 0; i--) {
      Message message = request.getMessages().get(i);
      if (message.getRole() != Role.USER) {
        continue;
      }
      for (Part part : message.getContent()) {
        if (part.getMedia() != null) {
          media.add(part);
        }
      }
      return media;
    }
    return media;
  }
}
