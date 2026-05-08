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

package com.google.genkit.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level options for the generate action, representing the generate request <em>before</em>
 * model and tool resolution. This is the Java equivalent of the JS {@code GenerateActionOptions}
 * type.
 *
 * <p>This type is used by:
 *
 * <ul>
 *   <li>The {@code /util/generate} action (Dev UI)
 *   <li>The {@link com.google.genkit.ai.middleware.GenerationMiddleware#wrapGenerate wrapGenerate}
 *       middleware hook
 * </ul>
 *
 * <p>Unlike {@link ModelRequest}, which is the low-level <em>resolved</em> request sent to a model,
 * this class retains high-level information such as the model name as a string and tool names as
 * string references. This allows {@code wrapGenerate} middleware to modify these values before
 * resolution occurs (e.g., swapping the model, adding/removing tools by name, changing output
 * format).
 *
 * @see com.google.genkit.ai.middleware.GenerateParams
 * @see com.google.genkit.ai.middleware.GenerationMiddleware#wrapGenerate
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerateActionOptions {

  @JsonProperty("model")
  private String model;

  @JsonProperty("messages")
  private List<Message> messages;

  @JsonProperty("tools")
  private List<String> tools;

  @JsonProperty("resources")
  private List<String> resources;

  @JsonProperty("toolChoice")
  private String toolChoice;

  @JsonProperty("config")
  private GenerationConfig config;

  @JsonProperty("output")
  private OutputConfig output;

  @JsonProperty("docs")
  private List<Document> docs;

  @JsonProperty("returnToolRequests")
  private Boolean returnToolRequests;

  @JsonProperty("maxTurns")
  private Integer maxTurns;

  @JsonProperty("stepName")
  private String stepName;

  /** Default constructor for JSON deserialization. */
  public GenerateActionOptions() {}

  // =========================================================================
  // Copy helpers
  // =========================================================================

  /**
   * Returns a new {@code GenerateActionOptions} with the given messages, preserving all other
   * fields. This is used in the tool loop to advance the conversation without mutating the
   * original.
   *
   * @param newMessages the updated message list
   * @return a new options instance
   */
  public GenerateActionOptions withMessages(List<Message> newMessages) {
    GenerateActionOptions copy = new GenerateActionOptions();
    copy.model = this.model;
    copy.messages = newMessages != null ? new ArrayList<>(newMessages) : null;
    copy.tools = this.tools;
    copy.resources = this.resources;
    copy.toolChoice = this.toolChoice;
    copy.config = this.config;
    copy.output = this.output;
    copy.docs = this.docs;
    copy.returnToolRequests = this.returnToolRequests;
    copy.maxTurns = this.maxTurns;
    copy.stepName = this.stepName;
    return copy;
  }

  /**
   * Returns a new {@code GenerateActionOptions} with the given model name, preserving all other
   * fields.
   *
   * @param newModel the model name
   * @return a new options instance
   */
  public GenerateActionOptions withModel(String newModel) {
    GenerateActionOptions copy = withMessages(this.messages);
    copy.model = newModel;
    return copy;
  }

  // =========================================================================
  // Getters and setters
  // =========================================================================

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public List<Message> getMessages() {
    return messages;
  }

  public void setMessages(List<Message> messages) {
    this.messages = messages;
  }

  public List<String> getTools() {
    return tools;
  }

  public void setTools(List<String> tools) {
    this.tools = tools;
  }

  public List<String> getResources() {
    return resources;
  }

  public void setResources(List<String> resources) {
    this.resources = resources;
  }

  public String getToolChoice() {
    return toolChoice;
  }

  public void setToolChoice(String toolChoice) {
    this.toolChoice = toolChoice;
  }

  public GenerationConfig getConfig() {
    return config;
  }

  public void setConfig(GenerationConfig config) {
    this.config = config;
  }

  public OutputConfig getOutput() {
    return output;
  }

  public void setOutput(OutputConfig output) {
    this.output = output;
  }

  public List<Document> getDocs() {
    return docs;
  }

  public void setDocs(List<Document> docs) {
    this.docs = docs;
  }

  public Boolean getReturnToolRequests() {
    return returnToolRequests;
  }

  public void setReturnToolRequests(Boolean returnToolRequests) {
    this.returnToolRequests = returnToolRequests;
  }

  public Integer getMaxTurns() {
    return maxTurns;
  }

  public void setMaxTurns(Integer maxTurns) {
    this.maxTurns = maxTurns;
  }

  public String getStepName() {
    return stepName;
  }

  public void setStepName(String stepName) {
    this.stepName = stepName;
  }
}
