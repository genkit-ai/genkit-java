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

package com.google.genkit.plugins.anthropic;

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
 * Anthropic Claude model implementation for Genkit.
 *
 * <p>Supports Claude 3.5, Claude 3, and Claude 2 model families with both synchronous and streaming
 * generation.
 */
public class AnthropicModel implements Model {

  private static final Logger logger = LoggerFactory.getLogger(AnthropicModel.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final String modelName;
  private final AnthropicPluginOptions options;
  private final OkHttpClient client;
  private final ObjectMapper objectMapper;
  private final ModelInfo info;

  /**
   * Creates a new AnthropicModel.
   *
   * @param modelName the model name (e.g., "claude-3-5-sonnet-20241022")
   * @param options the plugin options
   */
  public AnthropicModel(String modelName, AnthropicPluginOptions options) {
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
    info.setLabel("Anthropic " + modelName);

    ModelInfo.ModelCapabilities caps = new ModelInfo.ModelCapabilities();
    caps.setMultiturn(true);
    // Claude 3+ models support vision
    caps.setMedia(modelName.contains("claude-3"));
    caps.setTools(modelName.contains("claude-3"));
    caps.setSystemRole(true);
    caps.setOutput(Set.of("text", "json"));
    info.setSupports(caps);

    return info;
  }

  @Override
  public String getName() {
    return "anthropic/" + modelName;
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
      return callAnthropic(request);
    } catch (IOException e) {
      throw new GenkitException("Anthropic API call failed", e);
    }
  }

  @Override
  public ModelResponse run(
      ActionContext context, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
    if (streamCallback == null) {
      return run(context, request);
    }
    try {
      return callAnthropicStreaming(request, streamCallback);
    } catch (Exception e) {
      throw new GenkitException("Anthropic streaming API call failed", e);
    }
  }

  private ModelResponse callAnthropic(ModelRequest request) throws IOException {
    ObjectNode requestBody = buildRequestBody(request);

    Request httpRequest =
        new Request.Builder()
            .url(options.getBaseUrl() + "/messages")
            .header("x-api-key", options.getApiKey())
            .header("anthropic-version", options.getAnthropicVersion())
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
            .build();

    try (Response response = client.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No error body";
        throw new GenkitException("Anthropic API error: " + response.code() + " - " + errorBody);
      }

      String responseBody = response.body().string();
      return parseResponse(responseBody);
    }
  }

  private ModelResponse callAnthropicStreaming(
      ModelRequest request, Consumer<ModelResponseChunk> streamCallback) throws IOException {
    ObjectNode requestBody = buildRequestBody(request);
    requestBody.put("stream", true);

    Request httpRequest =
        new Request.Builder()
            .url(options.getBaseUrl() + "/messages")
            .header("x-api-key", options.getApiKey())
            .header("anthropic-version", options.getAnthropicVersion())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
            .build();

    StringBuilder fullContent = new StringBuilder();
    String finishReason = null;
    Usage usage = new Usage();

    // Track tool use blocks being streamed
    List<Map<String, Object>> toolUseBlocks = new ArrayList<>();
    int currentToolIndex = -1;

    try (Response response = client.newCall(httpRequest).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "No error body";
        throw new GenkitException(
            "Anthropic streaming API error: " + response.code() + " - " + errorBody);
      }

      BufferedReader reader =
          new BufferedReader(new InputStreamReader(response.body().byteStream()));
      String line;

      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || !line.startsWith("data: ")) {
          continue;
        }

        String data = line.substring(6); // Remove "data: " prefix
        if ("[DONE]".equals(data)) {
          break;
        }

        try {
          JsonNode event = objectMapper.readTree(data);
          String eventType = event.has("type") ? event.get("type").asText() : "";

          switch (eventType) {
            case "content_block_start":
              JsonNode contentBlock = event.get("content_block");
              if (contentBlock != null && "tool_use".equals(contentBlock.path("type").asText())) {
                currentToolIndex++;
                Map<String, Object> toolUse = new HashMap<>();
                toolUse.put("id", contentBlock.path("id").asText());
                toolUse.put("name", contentBlock.path("name").asText());
                toolUse.put("input", new StringBuilder());
                toolUseBlocks.add(toolUse);
              }
              break;

            case "content_block_delta":
              JsonNode delta = event.get("delta");
              if (delta != null) {
                String deltaType = delta.path("type").asText();

                if ("text_delta".equals(deltaType)) {
                  String text = delta.path("text").asText();
                  fullContent.append(text);

                  // Send chunk to callback
                  ModelResponseChunk chunk = ModelResponseChunk.text(text);
                  chunk.setIndex(0);
                  streamCallback.accept(chunk);
                } else if ("input_json_delta".equals(deltaType) && currentToolIndex >= 0) {
                  String partialJson = delta.path("partial_json").asText();
                  Map<String, Object> currentTool = toolUseBlocks.get(currentToolIndex);
                  ((StringBuilder) currentTool.get("input")).append(partialJson);
                }
              }
              break;

            case "message_delta":
              JsonNode messageDelta = event.get("delta");
              if (messageDelta != null && messageDelta.has("stop_reason")) {
                finishReason = messageDelta.get("stop_reason").asText();
              }
              // Capture usage from message_delta
              JsonNode usageNode = event.get("usage");
              if (usageNode != null) {
                if (usageNode.has("output_tokens")) {
                  usage.setOutputTokens(usageNode.get("output_tokens").asInt());
                }
              }
              break;

            case "message_start":
              JsonNode messageNode = event.get("message");
              if (messageNode != null) {
                JsonNode msgUsage = messageNode.get("usage");
                if (msgUsage != null && msgUsage.has("input_tokens")) {
                  usage.setInputTokens(msgUsage.get("input_tokens").asInt());
                }
              }
              break;
          }
        } catch (Exception e) {
          logger.warn("Error parsing streaming event: {}", line, e);
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

    // Add text content if present
    if (fullContent.length() > 0) {
      Part textPart = new Part();
      textPart.setText(fullContent.toString());
      parts.add(textPart);
    }

    // Add tool use blocks if present
    for (Map<String, Object> toolUse : toolUseBlocks) {
      Part toolPart = new Part();
      ToolRequest toolRequest = new ToolRequest();
      toolRequest.setRef((String) toolUse.get("id"));
      toolRequest.setName((String) toolUse.get("name"));

      String inputJson = ((StringBuilder) toolUse.get("input")).toString();
      if (!inputJson.isEmpty()) {
        try {
          @SuppressWarnings("unchecked")
          Map<String, Object> input = objectMapper.readValue(inputJson, Map.class);
          toolRequest.setInput(input);
        } catch (Exception e) {
          logger.warn("Failed to parse tool input: {}", inputJson, e);
          toolRequest.setInput(new HashMap<>());
        }
      }

      toolPart.setToolRequest(toolRequest);
      parts.add(toolPart);
    }

    message.setContent(parts);
    candidate.setMessage(message);

    // Set finish reason
    if (finishReason != null) {
      switch (finishReason) {
        case "end_turn":
        case "stop_sequence":
          candidate.setFinishReason(FinishReason.STOP);
          break;
        case "max_tokens":
          candidate.setFinishReason(FinishReason.LENGTH);
          break;
        case "tool_use":
          candidate.setFinishReason(FinishReason.OTHER);
          break;
        default:
          candidate.setFinishReason(FinishReason.OTHER);
      }
    }

    candidates.add(candidate);
    modelResponse.setCandidates(candidates);

    // Set usage
    if (usage.getInputTokens() > 0 || usage.getOutputTokens() > 0) {
      usage.setTotalTokens(usage.getInputTokens() + usage.getOutputTokens());
      modelResponse.setUsage(usage);
    }

    return modelResponse;
  }

  private ObjectNode buildRequestBody(ModelRequest request) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", modelName);

    // Set max_tokens (required for Anthropic)
    int maxTokens = 4096;
    Map<String, Object> config = request.getConfig();
    if (config != null && config.containsKey("maxOutputTokens")) {
      maxTokens = ((Number) config.get("maxOutputTokens")).intValue();
    }
    body.put("max_tokens", maxTokens);

    // Check if structured output is requested
    boolean structuredOutputRequested =
        request.getOutput() != null && request.getOutput().getSchema() != null;

    // Check if any message already contains "json" keyword
    boolean hasJsonKeywordInMessages = false;
    if (structuredOutputRequested) {
      for (Message msg : request.getMessages()) {
        for (Part part : msg.getContent()) {
          if (part.getText() != null && part.getText().toLowerCase().contains("json")) {
            hasJsonKeywordInMessages = true;
            break;
          }
        }
        if (hasJsonKeywordInMessages) break;
      }
    }

    // Extract system message and build messages array
    String systemMessage = extractSystemMessage(request);
    if (systemMessage != null) {
      body.put("system", systemMessage);
    }

    // Build context prefix from documents if provided
    String contextPrefix = buildContextPrefix(request);

    // Convert messages (excluding system messages)
    ArrayNode messages = body.putArray("messages");
    boolean contextPrefixAdded = false;
    boolean jsonInstructionAdded = false;

    for (int messageIndex = 0; messageIndex < request.getMessages().size(); messageIndex++) {
      Message message = request.getMessages().get(messageIndex);
      if (message.getRole() == Role.SYSTEM) {
        continue; // Skip system messages, handled separately
      }

      ObjectNode msg = messages.addObject();
      String role = convertRole(message.getRole());
      msg.put("role", role);

      List<Part> content = message.getContent();

      // Check for tool results
      boolean hasToolResponses = content.stream().anyMatch(p -> p.getToolResponse() != null);

      if (hasToolResponses) {
        // Tool result message
        ArrayNode contentArray = msg.putArray("content");
        for (Part part : content) {
          if (part.getToolResponse() != null) {
            ToolResponse toolResp = part.getToolResponse();
            ObjectNode toolResult = contentArray.addObject();
            toolResult.put("type", "tool_result");
            toolResult.put("tool_use_id", toolResp.getRef());

            // Convert output to string
            String outputStr;
            if (toolResp.getOutput() instanceof String) {
              outputStr = (String) toolResp.getOutput();
            } else {
              try {
                outputStr = objectMapper.writeValueAsString(toolResp.getOutput());
              } catch (Exception e) {
                outputStr = String.valueOf(toolResp.getOutput());
              }
            }
            toolResult.put("content", outputStr);
          }
        }
      } else {
        // Regular message
        ArrayNode contentArray = msg.putArray("content");

        // Add context prefix to first user message
        if (!contextPrefixAdded && contextPrefix != null && message.getRole() == Role.USER) {
          contextPrefixAdded = true;
          ObjectNode textNode = contentArray.addObject();
          textNode.put("type", "text");
          textNode.put("text", contextPrefix);
        }

        for (Part part : content) {
          if (part.getText() != null) {
            ObjectNode textNode = contentArray.addObject();
            textNode.put("type", "text");
            textNode.put("text", part.getText());
          } else if (part.getMedia() != null) {
            // Image content for Claude 3+
            ObjectNode imageNode = contentArray.addObject();
            imageNode.put("type", "image");
            ObjectNode source = imageNode.putObject("source");

            String url = part.getMedia().getUrl();
            if (url.startsWith("data:")) {
              // Base64 encoded image
              source.put("type", "base64");
              String[] parts2 = url.split(",", 2);
              String mediaType = parts2[0].substring(5, parts2[0].indexOf(';'));
              source.put("media_type", mediaType);
              source.put("data", parts2[1]);
            } else {
              // URL-based image
              source.put("type", "url");
              source.put("url", url);
            }
          } else if (part.getToolRequest() != null) {
            // Tool use in assistant message
            ToolRequest toolReq = part.getToolRequest();
            ObjectNode toolUseNode = contentArray.addObject();
            toolUseNode.put("type", "tool_use");
            toolUseNode.put("id", toolReq.getRef());
            toolUseNode.put("name", toolReq.getName());
            if (toolReq.getInput() != null) {
              toolUseNode.set("input", objectMapper.valueToTree(toolReq.getInput()));
            } else {
              toolUseNode.putObject("input");
            }
          }
        }

        // For Anthropic: If structured output is requested and this is the first user
        // message
        // without tool responses, and no JSON keyword exists yet, add the instruction
        if (structuredOutputRequested
            && !hasJsonKeywordInMessages
            && !jsonInstructionAdded
            && message.getRole() == Role.USER
            && !hasToolResponses) {
          ObjectNode jsonInstruction = contentArray.addObject();
          jsonInstruction.put("type", "text");
          jsonInstruction.put("text", "Return the response in JSON format.");
          jsonInstructionAdded = true;
          logger.debug("Auto-injected JSON format instruction for structured output");
        }
      }
    }

    // Add tools if present
    if (request.getTools() != null && !request.getTools().isEmpty()) {
      ArrayNode tools = body.putArray("tools");
      for (ToolDefinition tool : request.getTools()) {
        ObjectNode toolNode = tools.addObject();
        toolNode.put("name", tool.getName());
        if (tool.getDescription() != null) {
          toolNode.put("description", tool.getDescription());
        }
        if (tool.getInputSchema() != null) {
          toolNode.set("input_schema", objectMapper.valueToTree(tool.getInputSchema()));
        } else {
          ObjectNode emptySchema = toolNode.putObject("input_schema");
          emptySchema.put("type", "object");
          emptySchema.putObject("properties");
        }
      }
    }

    // Add generation config
    if (config != null) {
      if (config.containsKey("temperature")) {
        body.put("temperature", ((Number) config.get("temperature")).doubleValue());
      }
      if (config.containsKey("topP")) {
        body.put("top_p", ((Number) config.get("topP")).doubleValue());
      }
      if (config.containsKey("topK")) {
        body.put("top_k", ((Number) config.get("topK")).intValue());
      }
      if (config.containsKey("stopSequences")) {
        ArrayNode stop = body.putArray("stop_sequences");
        @SuppressWarnings("unchecked")
        List<String> stopSequences = (List<String>) config.get("stopSequences");
        for (String seq : stopSequences) {
          stop.add(seq);
        }
      }
    }

    return body;
  }

  private String extractSystemMessage(ModelRequest request) {
    StringBuilder systemBuilder = new StringBuilder();

    for (Message message : request.getMessages()) {
      if (message.getRole() == Role.SYSTEM) {
        for (Part part : message.getContent()) {
          if (part.getText() != null) {
            if (systemBuilder.length() > 0) {
              systemBuilder.append("\n");
            }
            systemBuilder.append(part.getText());
          }
        }
      }
    }

    return systemBuilder.length() > 0 ? systemBuilder.toString() : null;
  }

  private String convertRole(Role role) {
    switch (role) {
      case USER:
      case TOOL:
        return "user";
      case MODEL:
        return "assistant";
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

    // Parse content array
    JsonNode contentArray = root.get("content");
    if (contentArray != null && contentArray.isArray()) {
      for (JsonNode contentNode : contentArray) {
        String type = contentNode.path("type").asText();

        if ("text".equals(type)) {
          Part part = new Part();
          part.setText(contentNode.path("text").asText());
          parts.add(part);
        } else if ("tool_use".equals(type)) {
          Part part = new Part();
          ToolRequest toolRequest = new ToolRequest();
          toolRequest.setRef(contentNode.path("id").asText());
          toolRequest.setName(contentNode.path("name").asText());

          JsonNode inputNode = contentNode.get("input");
          if (inputNode != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = objectMapper.treeToValue(inputNode, Map.class);
            toolRequest.setInput(input);
          }

          part.setToolRequest(toolRequest);
          parts.add(part);
        }
      }
    }

    message.setContent(parts);
    candidate.setMessage(message);

    // Parse stop reason
    String stopReason = root.path("stop_reason").asText(null);
    if (stopReason != null) {
      switch (stopReason) {
        case "end_turn":
        case "stop_sequence":
          candidate.setFinishReason(FinishReason.STOP);
          break;
        case "max_tokens":
          candidate.setFinishReason(FinishReason.LENGTH);
          break;
        case "tool_use":
          candidate.setFinishReason(FinishReason.STOP);
          break;
        default:
          candidate.setFinishReason(FinishReason.OTHER);
      }
    }

    candidates.add(candidate);
    response.setCandidates(candidates);

    // Parse usage
    JsonNode usageNode = root.get("usage");
    if (usageNode != null) {
      Usage usage = new Usage();
      if (usageNode.has("input_tokens")) {
        usage.setInputTokens(usageNode.get("input_tokens").asInt());
      }
      if (usageNode.has("output_tokens")) {
        usage.setOutputTokens(usageNode.get("output_tokens").asInt());
      }
      usage.setTotalTokens(usage.getInputTokens() + usage.getOutputTokens());
      response.setUsage(usage);
    }

    return response;
  }
}
