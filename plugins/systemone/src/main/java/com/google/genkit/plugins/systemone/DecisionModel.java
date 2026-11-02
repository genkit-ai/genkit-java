/*
 * Copyright 2026 Google LLC
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

package com.google.genkit.plugins.systemone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.OutputConfig;
import com.google.genkit.ai.OutputFormat;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Usage;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One decision model. It answers the questions the request's output schema encodes, and claims the
 * system role and context, so a system message is instructions and documents are context rather
 * than text mixed into the state.
 */
final class DecisionModel implements Model {

  private static final Logger logger = LoggerFactory.getLogger(DecisionModel.class);

  private final Client client;
  private final String name;
  private final String id;
  private final StateBuilder.Reads reads;
  private final ModelInfo info;

  DecisionModel(Client client, String name, String id, String label, StateBuilder.Reads reads) {
    this.client = client;
    this.name = name;
    this.id = id;
    this.reads = reads;
    this.info = new ModelInfo();
    info.setLabel(label);
    ModelInfo.ModelCapabilities supports = new ModelInfo.ModelCapabilities();
    supports.setMedia(reads.images() || reads.audio() || reads.video());
    supports.setMultiturn(true);
    supports.setTools(false);
    supports.setSystemRole(true);
    supports.setContext(true);
    supports.setOutput(Set.of("json", "enum"));
    info.setSupports(supports);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public ModelInfo getInfo() {
    return info;
  }

  /** The model ID the server knows the model by. */
  String id() {
    return id;
  }

  /** The media the model reads. */
  StateBuilder.Reads reads() {
    return reads;
  }

  @Override
  public ModelResponse run(ActionContext ctx, ModelRequest req) throws GenkitException {
    if (req.getTools() != null && !req.getTools().isEmpty()) {
      throw Wire.error(
          "INVALID_ARGUMENT",
          "systemone: a decision model calls no tools; it answers the questions of the output"
              + " type");
    }
    OutputConfig output = req.getOutput();
    boolean enumFormat = output != null && output.getFormat() == OutputFormat.ENUM;
    if (output == null || (output.getSchema() == null && !enumFormat)) {
      throw Wire.error(
          "INVALID_ARGUMENT",
          "systemone: the model answers questions encoded in an output type; generate with an"
              + " output class built from Choice, Score, and Noul fields, or pass"
              + " OutputConfig.json(SystemOne.schema(questions)) for questions built at run time");
    }
    Config config = Config.from(req.getConfig());
    for (String field : Client.RESERVED_FIELDS) {
      if (config.extra().containsKey(field)) {
        throw Wire.error(
            "INVALID_ARGUMENT",
            "systemone: extra field \"" + field + "\" is one the request builds itself");
      }
    }
    String preamble = StateBuilder.systemPreamble(req.getMessages());
    Map<String, WireQuestion> questions =
        enumFormat
            ? Questions.enumQuestion(output.getSchema(), preamble)
            : Questions.compile(output.getSchema(), preamble);
    StateBuilder.Built built =
        StateBuilder.build(req.getMessages(), req.getContext(), config.stateJson(), reads);

    Client.Answered resp =
        client.decide(
            id, new Client.Request(built.state(), built.media(), questions, config.extra()));
    logger.debug(
        "systemone: answered endpoint={} model={} questions={} inputTokens={} outputTokens={}",
        client.endpoint.name(),
        resp.info().model(),
        questions.size(),
        resp.inputTokens(),
        resp.outputTokens());

    String text = Questions.answersText(resp.info().answers(), questions, enumFormat);

    Usage usage =
        new Usage(
            resp.inputTokens(), resp.outputTokens(), resp.inputTokens() + resp.outputTokens());
    if (resp.cost() != null) {
      usage.setCustom(new LinkedHashMap<>(Map.of("cost", resp.cost())));
    }
    Message message = new Message(Role.MODEL, new ArrayList<>(List.of(Part.text(text))));
    ModelResponse response =
        new ModelResponse(new ArrayList<>(List.of(new Candidate(message, FinishReason.STOP))));
    response.setFinishReason(FinishReason.STOP);
    response.setUsage(usage);
    response.setCustom(
        Wire.MAPPER.convertValue(
            resp.info(), new TypeReference<LinkedHashMap<String, Object>>() {}));
    return response;
  }
}
