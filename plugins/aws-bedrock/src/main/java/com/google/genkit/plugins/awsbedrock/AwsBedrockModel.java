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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.ai.*;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;

/**
 * AWS Bedrock model implementation for Genkit.
 *
 * <p>Uses the AWS Bedrock Converse API via HTTP with AWS SigV4 signing.
 */
public class AwsBedrockModel implements Model {

  private static final Logger logger = LoggerFactory.getLogger(AwsBedrockModel.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final String modelId;
  private final AwsBedrockPluginOptions options;
  private final OkHttpClient client;
  private final ObjectMapper objectMapper;
  private final ModelInfo info;

  /**
   * Creates a new AwsBedrockModel.
   *
   * @param modelId the AWS Bedrock model ID (e.g., "anthropic.claude-3-5-sonnet-20241022-v2:0")
   * @param options the plugin options
   */
  public AwsBedrockModel(String modelId, AwsBedrockPluginOptions options) {
    this.modelId = modelId;
    this.options = options;
    this.objectMapper = new ObjectMapper();
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();
    this.info = createModelInfo();
  }

  private ModelInfo createModelInfo() {
    ModelInfo info = new ModelInfo();
    info.setLabel("AWS Bedrock " + modelId);

    ModelInfo.ModelCapabilities caps = new ModelInfo.ModelCapabilities();
    caps.setMultiturn(true);
    caps.setMedia(supportsVision(modelId));
    caps.setTools(supportsTools(modelId));
    caps.setSystemRole(true);
    caps.setOutput(Set.of("text", "json"));
    info.setSupports(caps);

    return info;
  }

  private boolean supportsVision(String modelId) {
    return modelId.contains("claude-3")
        || modelId.contains("nova")
        || modelId.contains("titan-text-premier");
  }

  private boolean supportsTools(String modelId) {
    return !modelId.contains("claude-v2")
        && !modelId.contains("titan-text-express")
        && !modelId.contains("titan-text-lite")
        && !modelId.contains("command-light");
  }

  @Override
  public String getName() {
    return "aws-bedrock/" + modelId;
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
      return callBedrock(request, false, null);
    } catch (IOException e) {
      throw new GenkitException("AWS Bedrock API call failed", e);
    }
  }

  @Override
  public ModelResponse run(
      ActionContext context, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
    if (streamCallback == null) {
      return run(context, request);
    }
    try {
      return callBedrock(request, true, streamCallback);
    } catch (Exception e) {
      throw new GenkitException("AWS Bedrock streaming API call failed", e);
    }
  }

  private ModelResponse callBedrock(
      ModelRequest request, boolean stream, Consumer<ModelResponseChunk> streamCallback)
      throws IOException {
    ObjectNode requestBody = buildRequestBody(request, stream);

    // Build path with raw model ID - AWS SDK will handle encoding
    String path = String.format("/model/%s/%s", modelId, stream ? "converse-stream" : "converse");
    String host = String.format("bedrock-runtime.%s.amazonaws.com", options.getRegion().id());

    Request httpRequest = signRequest(host, path, requestBody.toString());

    try (Response response = client.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No error body";
        throw new GenkitException("AWS Bedrock API error: " + response.code() + " - " + errorBody);
      }

      if (stream) {
        return handleStreamingResponse(response, streamCallback);
      } else {
        String responseBody = response.body().string();
        return parseResponse(responseBody);
      }
    }
  }

  private Request signRequest(String host, String path, String body) {
    try {
      AwsCredentials credentials = options.getCredentialsProvider().resolveCredentials();

      // Build URI and let it handle encoding properly
      java.net.URI uri = java.net.URI.create(String.format("https://%s%s", host, path));

      SdkHttpFullRequest httpRequest =
          SdkHttpFullRequest.builder()
              .uri(uri)
              .method(SdkHttpMethod.POST)
              .putHeader("Content-Type", "application/json; charset=utf-8")
              .contentStreamProvider(
                  () ->
                      new java.io.ByteArrayInputStream(
                          body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              .build();

      Aws4Signer signer = Aws4Signer.create();
      Aws4SignerParams signerParams =
          Aws4SignerParams.builder()
              .awsCredentials(credentials)
              .signingName("bedrock")
              .signingRegion(options.getRegion())
              .build();

      SdkHttpFullRequest signedRequest = signer.sign(httpRequest, signerParams);

      // Use the signed request's URI directly
      Request.Builder okHttpRequestBuilder =
          new Request.Builder()
              .url(signedRequest.getUri().toURL())
              .post(RequestBody.create(body, JSON_MEDIA_TYPE));

      signedRequest
          .headers()
          .forEach(
              (key, values) -> {
                values.forEach(value -> okHttpRequestBuilder.addHeader(key, value));
              });

      return okHttpRequestBuilder.build();
    } catch (Exception e) {
      throw new GenkitException("Failed to sign AWS request", e);
    }
  }

  private ObjectNode buildRequestBody(ModelRequest request, boolean stream) {
    ObjectNode body = objectMapper.createObjectNode();

    // Convert messages
    ArrayNode messages = objectMapper.createArrayNode();
    String systemMessage = null;

    if (request.getMessages() != null) {
      for (com.google.genkit.ai.Message msg : request.getMessages()) {
        if (msg.getRole() == Role.SYSTEM) {
          if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            Part firstPart = msg.getContent().get(0);
            if (firstPart.getText() != null) {
              systemMessage = firstPart.getText();
            }
          }
          continue;
        }

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", msg.getRole() == Role.USER ? "user" : "assistant");

        ArrayNode content = objectMapper.createArrayNode();
        if (msg.getContent() != null) {
          for (Part part : msg.getContent()) {
            if (part.getText() != null) {
              ObjectNode textContent = objectMapper.createObjectNode();
              textContent.put("text", part.getText());
              content.add(textContent);
            } else if (part.getMedia() != null) {
              ObjectNode imageContent = convertMediaToBedrock(part.getMedia());
              if (imageContent != null) {
                content.add(imageContent);
              }
            } else if (part.getToolRequest() != null) {
              ObjectNode toolUse = objectMapper.createObjectNode();
              toolUse.put("toolUseId", part.getToolRequest().getRef());
              toolUse.put("name", part.getToolRequest().getName());
              toolUse.set("input", objectMapper.valueToTree(part.getToolRequest().getInput()));
              ObjectNode toolUseContent = objectMapper.createObjectNode();
              toolUseContent.set("toolUse", toolUse);
              content.add(toolUseContent);
            } else if (part.getToolResponse() != null) {
              ObjectNode toolResult = objectMapper.createObjectNode();
              toolResult.put("toolUseId", part.getToolResponse().getRef());
              ArrayNode resultContent = objectMapper.createArrayNode();
              ObjectNode textNode = objectMapper.createObjectNode();
              Object output = part.getToolResponse().getOutput();
              textNode.put("text", output != null ? output.toString() : "");
              resultContent.add(textNode);
              toolResult.set("content", resultContent);
              ObjectNode toolResultContent = objectMapper.createObjectNode();
              toolResultContent.set("toolResult", toolResult);
              content.add(toolResultContent);
            }
          }
        }

        message.set("content", content);
        messages.add(message);
      }
    }

    body.set("messages", messages);

    if (systemMessage != null) {
      ArrayNode systemArray = objectMapper.createArrayNode();
      ObjectNode systemObj = objectMapper.createObjectNode();
      systemObj.put("text", systemMessage);
      systemArray.add(systemObj);
      body.set("system", systemArray);
    }

    // Add inference configuration
    ObjectNode inferenceConfig = objectMapper.createObjectNode();
    if (request.getConfig() != null) {
      Map<String, Object> config = request.getConfig();
      if (config.get("maxTokens") != null) {
        inferenceConfig.put("maxTokens", ((Number) config.get("maxTokens")).intValue());
      }
      if (config.get("temperature") != null) {
        inferenceConfig.put("temperature", ((Number) config.get("temperature")).floatValue());
      }
      if (config.get("topP") != null) {
        inferenceConfig.put("topP", ((Number) config.get("topP")).floatValue());
      }
      if (config.get("stopSequences") != null) {
        inferenceConfig.set("stopSequences", objectMapper.valueToTree(config.get("stopSequences")));
      }
    }
    body.set("inferenceConfig", inferenceConfig);

    // Add tools if present
    if (request.getTools() != null && !request.getTools().isEmpty()) {
      ObjectNode toolConfig = objectMapper.createObjectNode();
      ArrayNode tools = objectMapper.createArrayNode();

      for (ToolDefinition toolDef : request.getTools()) {
        ObjectNode tool = objectMapper.createObjectNode();
        ObjectNode toolSpec = objectMapper.createObjectNode();
        toolSpec.put("name", toolDef.getName());
        toolSpec.put("description", toolDef.getDescription());
        if (toolDef.getInputSchema() != null) {
          toolSpec.set(
              "inputSchema",
              objectMapper
                  .createObjectNode()
                  .set("json", objectMapper.valueToTree(toolDef.getInputSchema())));
        }
        tool.set("toolSpec", toolSpec);
        tools.add(tool);
      }

      toolConfig.set("tools", tools);
      body.set("toolConfig", toolConfig);
    }

    return body;
  }

  private ObjectNode convertMediaToBedrock(Media media) {
    if (media == null || media.getUrl() == null) {
      return null;
    }

    String url = media.getUrl();
    if (!url.startsWith("data:")) {
      return null;
    }

    String[] parts = url.split(",");
    if (parts.length != 2) {
      return null;
    }

    String format = "png";
    if (parts[0].contains("image/jpeg")) {
      format = "jpeg";
    } else if (parts[0].contains("image/gif")) {
      format = "gif";
    } else if (parts[0].contains("image/webp")) {
      format = "webp";
    }

    ObjectNode imageContent = objectMapper.createObjectNode();
    ObjectNode image = objectMapper.createObjectNode();
    image.put("format", format);
    ObjectNode source = objectMapper.createObjectNode();
    source.put("bytes", parts[1]);
    image.set("source", source);
    imageContent.set("image", image);

    return imageContent;
  }

  private ModelResponse parseResponse(String responseBody) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);

    ModelResponse modelResponse = new ModelResponse();
    List<Candidate> candidates = new ArrayList<>();
    Candidate candidate = new Candidate();

    com.google.genkit.ai.Message message = new com.google.genkit.ai.Message();
    message.setRole(Role.MODEL);
    List<Part> parts = new ArrayList<>();

    JsonNode output = root.path("output");
    JsonNode outputMessage = output.path("message");
    JsonNode content = outputMessage.path("content");

    if (content.isArray()) {
      for (JsonNode contentBlock : content) {
        if (contentBlock.has("text")) {
          Part textPart = new Part();
          textPart.setText(contentBlock.get("text").asText());
          parts.add(textPart);
        } else if (contentBlock.has("toolUse")) {
          JsonNode toolUse = contentBlock.get("toolUse");
          Part toolPart = new Part();
          ToolRequest toolRequest = new ToolRequest();
          toolRequest.setRef(toolUse.get("toolUseId").asText());
          toolRequest.setName(toolUse.get("name").asText());
          toolRequest.setInput(objectMapper.convertValue(toolUse.get("input"), Map.class));
          toolPart.setToolRequest(toolRequest);
          parts.add(toolPart);
        }
      }
    }

    message.setContent(parts);
    candidate.setMessage(message);

    if (root.has("stopReason")) {
      candidate.setFinishReason(convertStopReason(root.get("stopReason").asText()));
    }

    candidates.add(candidate);
    modelResponse.setCandidates(candidates);

    if (root.has("usage")) {
      JsonNode usage = root.get("usage");
      Usage usageObj = new Usage();
      usageObj.setInputTokens(usage.path("inputTokens").asInt());
      usageObj.setOutputTokens(usage.path("outputTokens").asInt());
      modelResponse.setUsage(usageObj);
    }

    return modelResponse;
  }

  private ModelResponse handleStreamingResponse(
      Response response, Consumer<ModelResponseChunk> streamCallback) throws IOException {
    StringBuilder fullContent = new StringBuilder();
    String finishReason = null;
    Usage usage = new Usage();
    List<Map<String, Object>> toolUses = new ArrayList<>();

    logger.debug(
        "Starting to read streaming response, Content-Type: {}", response.header("Content-Type"));

    // AWS Bedrock uses application/vnd.amazon.eventstream binary format
    // We need to parse the binary stream to extract JSON payloads
    byte[] responseBytes = response.body().bytes();
    String responseText = new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8);

    // Extract JSON objects from the event stream by finding patterns
    // Event format includes markers like contentBlockDelta, messageStop, metadata
    int pos = 0;
    int eventCount = 0;

    while (pos < responseText.length()) {
      // Look for JSON object start
      int jsonStart = responseText.indexOf("{\"", pos);
      if (jsonStart == -1) break;

      // Find matching closing brace
      int braceCount = 0;
      int jsonEnd = jsonStart;
      boolean inString = false;
      boolean escape = false;

      for (int i = jsonStart; i < responseText.length(); i++) {
        char c = responseText.charAt(i);

        if (escape) {
          escape = false;
          continue;
        }

        if (c == '\\') {
          escape = true;
          continue;
        }

        if (c == '"') {
          inString = !inString;
          continue;
        }

        if (!inString) {
          if (c == '{') braceCount++;
          else if (c == '}') {
            braceCount--;
            if (braceCount == 0) {
              jsonEnd = i + 1;
              break;
            }
          }
        }
      }

      if (jsonEnd > jsonStart) {
        String jsonStr = responseText.substring(jsonStart, jsonEnd);

        try {
          JsonNode event = objectMapper.readTree(jsonStr);
          eventCount++;
          logger.debug("Parsed event {}: {}", eventCount, jsonStr);
          logger.debug("Parsed event {}: {}", eventCount, jsonStr);

          if (event.has("contentBlockIndex") && event.has("delta")) {
            JsonNode delta = event.get("delta");
            if (delta.has("text")) {
              String text = delta.get("text").asText();
              fullContent.append(text);

              ModelResponseChunk chunk = ModelResponseChunk.text(text);
              chunk.setIndex(0);
              streamCallback.accept(chunk);
            }
          } else if (event.has("stopReason")) {
            finishReason = event.get("stopReason").asText();
          } else if (event.has("usage")) {
            JsonNode usageNode = event.get("usage");
            usage.setInputTokens(usageNode.path("inputTokens").asInt());
            usage.setOutputTokens(usageNode.path("outputTokens").asInt());
          }

          pos = jsonEnd;
        } catch (Exception e) {
          logger.warn("Failed to parse JSON at position {}: {}", jsonStart, jsonStr, e);
          pos = jsonEnd > jsonStart ? jsonEnd : jsonStart + 1;
        }
      } else {
        pos++;
      }
    }

    logger.debug(
        "Finished streaming. Parsed {} events. Content length: {}",
        eventCount,
        fullContent.length());
    return buildFinalStreamResponse(fullContent.toString(), finishReason, usage, toolUses);
  }

  private ModelResponse buildFinalStreamResponse(
      String content, String finishReason, Usage usage, List<Map<String, Object>> toolUses) {
    ModelResponse modelResponse = new ModelResponse();
    List<Candidate> candidates = new ArrayList<>();
    Candidate candidate = new Candidate();

    com.google.genkit.ai.Message message = new com.google.genkit.ai.Message();
    message.setRole(Role.MODEL);
    List<Part> parts = new ArrayList<>();

    if (content != null && !content.isEmpty()) {
      Part textPart = new Part();
      textPart.setText(content);
      parts.add(textPart);
    }

    for (Map<String, Object> toolUse : toolUses) {
      Part toolPart = new Part();
      ToolRequest toolRequest = new ToolRequest();
      toolRequest.setRef((String) toolUse.get("toolUseId"));
      toolRequest.setName((String) toolUse.get("name"));
      @SuppressWarnings("unchecked")
      Map<String, Object> input =
          (Map<String, Object>) toolUse.getOrDefault("input", new HashMap<>());
      toolRequest.setInput(input);
      toolPart.setToolRequest(toolRequest);
      parts.add(toolPart);
    }

    message.setContent(parts);
    candidate.setMessage(message);

    if (finishReason != null) {
      candidate.setFinishReason(convertStopReason(finishReason));
    }

    candidates.add(candidate);
    modelResponse.setCandidates(candidates);
    modelResponse.setUsage(usage);

    return modelResponse;
  }

  private FinishReason convertStopReason(String stopReason) {
    switch (stopReason.toLowerCase()) {
      case "end_turn":
      case "stop_sequence":
      case "tool_use":
        return FinishReason.STOP;
      case "max_tokens":
        return FinishReason.LENGTH;
      case "content_filtered":
        return FinishReason.OTHER; // No SAFETY enum value
      default:
        return FinishReason.OTHER;
    }
  }
}
