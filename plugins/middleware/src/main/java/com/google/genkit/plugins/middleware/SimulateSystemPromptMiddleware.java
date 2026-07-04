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

package com.google.genkit.plugins.middleware;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites a {@code system} message into a user/model exchange, for models that do not natively
 * support system prompts.
 *
 * <p>Wraps the {@code wrapGenerate} hook: each {@code system} message is replaced by a {@code user}
 * message ({@link Options#preface} followed by the original system content) and a {@code model}
 * message ({@link Options#acknowledgement}). Non-system messages are passed through unchanged.
 *
 * <p>Mirrors the JS {@code simulateSystemPrompt} model middleware.
 */
public class SimulateSystemPromptMiddleware extends BaseGenerationMiddleware {

  private static final Logger logger =
      LoggerFactory.getLogger(SimulateSystemPromptMiddleware.class);

  private final Options options;

  public SimulateSystemPromptMiddleware(Options options) {
    this.options = options != null ? options : new Options();
  }

  @Override
  public String name() {
    return "simulateSystemPrompt";
  }

  @Override
  public GenerationMiddleware newInstance() {
    return this;
  }

  @Override
  public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException {
    GenerateActionOptions req = params.getRequest();
    List<Message> messages = req.getMessages();
    if (messages == null || messages.isEmpty()) {
      return next.apply(ctx, params);
    }

    boolean hasSystem = messages.stream().anyMatch(m -> m != null && m.getRole() == Role.SYSTEM);
    if (!hasSystem) {
      return next.apply(ctx, params);
    }

    List<Message> rewritten = new ArrayList<>(messages.size() + 2);
    for (Message m : messages) {
      if (m != null && m.getRole() == Role.SYSTEM) {
        List<Part> userParts = new ArrayList<>();
        if (options.preface != null && !options.preface.isEmpty()) {
          userParts.add(Part.text(options.preface));
        }
        if (m.getContent() != null) {
          userParts.addAll(m.getContent());
        }
        rewritten.add(new Message(Role.USER, userParts));
        rewritten.add(new Message(Role.MODEL, List.of(Part.text(options.acknowledgement))));
      } else {
        rewritten.add(m);
      }
    }

    logger.debug("[simulateSystemPrompt] rewrote system message(s) into a user/model exchange");
    return next.apply(ctx, params.withRequest(req.withMessages(rewritten)));
  }

  /** Configuration parameters for {@link SimulateSystemPromptMiddleware}. */
  public static class Options {

    @JsonProperty("preface")
    @JsonPropertyDescription(
        "Text prepended to the system content when converting it into a user message.")
    public String preface = "System Instructions:\n";

    @JsonProperty("acknowledgement")
    @JsonPropertyDescription("Assistant reply inserted after the converted system message.")
    public String acknowledgement = "Understood.";

    public Options() {}
  }
}
