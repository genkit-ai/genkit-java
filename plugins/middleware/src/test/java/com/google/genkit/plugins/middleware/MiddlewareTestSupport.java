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

package com.google.genkit.plugins.middleware;

import com.google.genkit.Genkit;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.core.ActionContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared fixtures for the middleware tests: fake models, tools, and part inspection. */
final class MiddlewareTestSupport {

  private MiddlewareTestSupport() {}

  /** A model function that may stream chunks through the callback (null when not streaming). */
  interface ModelFn extends BiFunction<ModelRequest, Consumer<ModelResponseChunk>, ModelResponse> {}

  /** Registers a model backed by {@code fn}. */
  static Model registerModel(Genkit genkit, String name, ModelFn fn) {
    Model m =
        new Model() {
          @Override
          public String getName() {
            return name;
          }

          @Override
          public ModelInfo getInfo() {
            ModelInfo info = new ModelInfo();
            ModelInfo.ModelCapabilities caps = new ModelInfo.ModelCapabilities();
            caps.setMultiturn(true);
            caps.setSystemRole(true);
            caps.setTools(true);
            info.setSupports(caps);
            return info;
          }

          @Override
          public boolean supportsStreaming() {
            return true;
          }

          @Override
          public ModelResponse run(ActionContext ctx, ModelRequest request) {
            return run(ctx, request, null);
          }

          @Override
          public ModelResponse run(
              ActionContext ctx, ModelRequest request, Consumer<ModelResponseChunk> cb) {
            ModelResponse resp = fn.apply(request, cb);
            if (resp.getRequest() == null) {
              resp.setRequest(request);
            }
            return resp;
          }
        };
    genkit.registerModel(m);
    return m;
  }

  /** Registers a model whose reply message is computed from the request. */
  static Model registerReplyModel(Genkit genkit, String name, Function<ModelRequest, Message> fn) {
    return registerModel(genkit, name, (req, cb) -> reply(req, fn.apply(req)));
  }

  /** Builds a response carrying {@code message}. */
  static ModelResponse reply(ModelRequest req, Message message) {
    return reply(req, message, FinishReason.STOP);
  }

  /** Builds a response carrying {@code message} with the given finish reason. */
  static ModelResponse reply(ModelRequest req, Message message, FinishReason finish) {
    ModelResponse resp = new ModelResponse(List.of(new Candidate(message, finish)));
    resp.setFinishReason(finish);
    resp.setRequest(req);
    return resp;
  }

  /** A model message of tool requests. */
  static Message toolRequests(ToolRequest... requests) {
    List<Part> parts = new ArrayList<>();
    for (ToolRequest r : requests) {
      parts.add(Part.toolRequest(r));
    }
    return new Message(Role.MODEL, parts);
  }

  /** A model message of the given parts. */
  static Message modelParts(Part... parts) {
    return new Message(Role.MODEL, new ArrayList<>(Arrays.asList(parts)));
  }

  /** A tool request. */
  static ToolRequest request(String name, Map<String, Object> input) {
    return new ToolRequest(name, input);
  }

  /** A tool request with a ref. */
  static ToolRequest request(String name, String ref, Map<String, Object> input) {
    ToolRequest r = new ToolRequest(name, input);
    r.setRef(ref);
    return r;
  }

  /** Reports whether any message in the request carries a tool response. */
  static boolean hasToolResponse(ModelRequest req) {
    for (Message m : req.getMessages()) {
      for (Part p : m.getContent()) {
        if (p.getToolResponse() != null) {
          return true;
        }
      }
    }
    return false;
  }

  /** Counts the tool messages in the request. */
  static long toolMessages(ModelRequest req) {
    return req.getMessages().stream().filter(m -> m.getRole() == Role.TOOL).count();
  }

  /** Input of the test tools: {"v": string}. */
  public static class VInput {
    public String v;
  }

  /** A tool returning "result:" + v. */
  static Tool<VInput, String> vTool(String name) {
    return vTool(name, "test tool", in -> "result:" + (in != null ? in.v : null));
  }

  /** A tool with the given description and handler over {"v": string}. */
  static Tool<VInput, String> vTool(String name, String description, Function<VInput, String> fn) {
    return Tool.<VInput, String>builder()
        .name(name)
        .description(description)
        .inputClass(VInput.class)
        .outputClass(String.class)
        .handler((ctx, in) -> fn.apply(in))
        .build();
  }

  /** Reports whether {@code p} answers a tool call with an error. */
  static boolean isToolError(Part p) {
    return p != null
        && p.getToolResponse() != null
        && p.getMetadata() != null
        && Boolean.TRUE.equals(p.getMetadata().get("isError"));
  }

  /** Returns the error a tool error response carries. */
  static String errorMessage(Part p) {
    if (!isToolError(p)) {
      throw new AssertionError("part is not a tool error response: " + p);
    }
    Object out = p.getToolResponse().getOutput();
    if (!(out instanceof Map)) {
      throw new AssertionError("tool error output is not a map: " + out);
    }
    Object msg = ((Map<?, ?>) out).get("error");
    return msg instanceof String ? (String) msg : "";
  }

  /** Returns every part of every message. */
  static List<Part> allParts(List<Message> messages) {
    List<Part> parts = new ArrayList<>();
    for (Message m : messages) {
      if (m.getContent() != null) {
        parts.addAll(m.getContent());
      }
    }
    return parts;
  }
}
