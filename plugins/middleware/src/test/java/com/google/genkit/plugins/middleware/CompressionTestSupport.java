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

import com.google.genkit.Genkit;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
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
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.ai.Usage;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.core.ActionContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Fixtures shared by the compression and budget tests. */
final class CompressionTestSupport {

  private CompressionTestSupport() {}

  /** Answers the call-th request (from 1) to a fixture model. */
  @FunctionalInterface
  interface Reply {
    ModelResponse reply(int call, ModelRequest req);
  }

  /** Returns a response carrying message, reporting inputTokens (none when null). */
  static ModelResponse response(Message message, Integer inputTokens) {
    ModelResponse resp = new ModelResponse(new ArrayList<>(List.of(new Candidate(message))));
    if (inputTokens != null) {
      Usage u = new Usage();
      u.setInputTokens(inputTokens);
      resp.setUsage(u);
    }
    return resp;
  }

  /** Answers every call with text, reporting inputTokens. */
  static Reply textReply(String text, int inputTokens) {
    return (call, req) -> response(Message.model(text), inputTokens);
  }

  /** Returns a model that answers with reply, recording each request. */
  static Model model(String name, Reply reply, List<ModelRequest> requests) {
    return new Model() {
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
        caps.setMedia(true);
        info.setSupports(caps);
        return info;
      }

      @Override
      public ModelResponse run(ActionContext ctx, ModelRequest request) {
        int call;
        synchronized (requests) {
          requests.add(request);
          call = requests.size();
        }
        ModelResponse resp = reply.reply(call, request);
        resp.setRequest(request);
        return resp;
      }

      @Override
      public ModelResponse run(
          ActionContext ctx, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
        return run(ctx, request);
      }
    };
  }

  /** A Genkit instance with a model that records the requests it receives. */
  static final class Fixture {
    final Genkit genkit;
    final List<ModelRequest> requests = Collections.synchronizedList(new ArrayList<>());

    Fixture(Reply reply) {
      this(new Genkit(), reply);
    }

    Fixture(Genkit genkit, Reply reply) {
      this.genkit = genkit;
      genkit.registerModel(model("test/main", reply, requests));
    }

    /** Defines a model that appends each request to requests. */
    void defineModel(String name, Reply reply, List<ModelRequest> requests) {
      genkit.registerModel(model(name, reply, requests));
    }

    /** Defines a summarizer that answers with text; returns the prompts it receives. */
    List<String> defineSummarizer(String name, String text) {
      List<String> prompts = Collections.synchronizedList(new ArrayList<>());
      defineModel(
          name,
          (call, req) -> {
            prompts.add(req.getMessages().get(0).getText());
            ModelResponse resp = response(Message.model(text), null);
            resp.setFinishReason(FinishReason.STOP);
            return resp;
          },
          new ArrayList<>());
      return prompts;
    }

    ModelResponse generate(GenerationMiddleware mw, List<Message> msgs) {
      return generate(mw, msgs, b -> {});
    }

    ModelResponse generate(
        GenerationMiddleware mw, List<Message> msgs, Consumer<GenerateOptions.Builder<Void>> opts) {
      GenerateOptions.Builder<Void> b =
          GenerateOptions.<Void>builder().model("test/main").messages(msgs).use(mw);
      opts.accept(b);
      return genkit.generate(b.build());
    }

    /** Returns the messages of the newest model request. */
    List<Message> sent() {
      if (requests.isEmpty()) {
        throw new AssertionError("the model received no request");
      }
      return requests.get(requests.size() - 1).getMessages();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Tool<Map<String, Object>, Object> tool(String name, Function<Map<String, Object>, Object> fn) {
      Class<Map<String, Object>> in = (Class) Map.class;
      BiFunction<ActionContext, Map<String, Object>, Object> handler = (ctx, i) -> fn.apply(i);
      return genkit.defineTool(name, name, handler, in, Object.class);
    }
  }

  static Message userMsg(String text) {
    return Message.user(text);
  }

  static Message modelMsg(String text) {
    return Message.model(text);
  }

  static Message systemMsg(String text) {
    return Message.system(text);
  }

  static Message message(Role role, Map<String, Object> metadata, Part... parts) {
    Message m = new Message(role, new ArrayList<>(List.of(parts)));
    m.setMetadata(metadata);
    return m;
  }

  static ToolRequest req(String name, String ref, Object input) {
    ToolRequest r = new ToolRequest(name, input);
    r.setRef(ref);
    return r;
  }

  static ToolResponse res(String name, String ref, Object output) {
    return new ToolResponse(ref, name, output);
  }

  static ToolResponse res(String name, String ref, Object output, Part... content) {
    ToolResponse r = new ToolResponse(ref, name, output);
    r.setContent(new ArrayList<>(java.util.Arrays.asList(content)));
    return r;
  }

  /** Returns a model message requesting the given tool calls. */
  static Message toolCallMsg(ToolRequest... reqs) {
    List<Part> parts = new ArrayList<>();
    for (ToolRequest r : reqs) {
      parts.add(Part.toolRequest(r));
    }
    return new Message(Role.MODEL, parts);
  }

  /** Returns a tool message carrying the given tool responses. */
  static Message toolResultMsg(ToolResponse... resps) {
    List<Part> parts = new ArrayList<>();
    for (ToolResponse r : resps) {
      parts.add(Part.toolResponse(r));
    }
    return new Message(Role.TOOL, parts);
  }

  static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  /** Returns the compression stats on resp, or null. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> stats(ModelResponse resp) {
    if (resp.getCustom() == null) {
      return null;
    }
    return (Map<String, Object>) resp.getCustom().get(CompressionHistory.KEY);
  }

  /** Returns stats(resp), or an empty map, for asserting on single fields. */
  static Map<String, Object> statsOrEmpty(ModelResponse resp) {
    Map<String, Object> s = stats(resp);
    return s != null ? s : Map.of();
  }

  static List<Message> toolMessages(List<Message> msgs) {
    List<Message> out = new ArrayList<>();
    for (Message m : msgs) {
      if (m != null && m.getRole() == Role.TOOL) {
        out.add(m);
      }
    }
    return out;
  }

  /** Returns the output of the i-th part of m as a string. */
  static String output(Message m, int i) {
    if (i >= m.getContent().size() || m.getContent().get(i).getToolResponse() == null) {
      throw new AssertionError("part " + i + " is not a tool response");
    }
    Object out = m.getContent().get(i).getToolResponse().getOutput();
    if (!(out instanceof String s)) {
      throw new AssertionError("output of part " + i + " is " + out + ", want a string");
    }
    return s;
  }

  /** Returns the first text of each message, for comparing message lists. */
  static List<String> texts(List<Message> msgs) {
    List<String> out = new ArrayList<>();
    for (Message m : msgs) {
      if (m == null || m.getContent() == null || m.getContent().isEmpty()) {
        out.add("");
        continue;
      }
      Part p = m.getContent().get(0);
      String role = m.getRole().getValue();
      if (p == null) {
        out.add(role + ":");
      } else if (p.getToolRequest() != null) {
        out.add(role + ":call " + p.getToolRequest().getName());
      } else if (p.getToolResponse() != null) {
        out.add(role + ":result " + p.getToolResponse().getName());
      } else {
        out.add(role + ":" + (p.getText() != null ? p.getText() : ""));
      }
    }
    return out;
  }

  /** Returns the concatenated text of m's text parts, skipping null parts. */
  static String text(Message m) {
    StringBuilder sb = new StringBuilder();
    if (m != null && m.getContent() != null) {
      for (Part p : m.getContent()) {
        if (p != null && p.getText() != null) {
          sb.append(p.getText());
        }
      }
    }
    return sb.toString();
  }

  static String repeat(String s, int n) {
    return s.repeat(n);
  }

  static Map<String, Object> cc(Map<String, Object> metadata) {
    return CompressionHistory.ccMeta(metadata);
  }

  /** Records the messages each model call receives; listed after compression, it sees the view. */
  static final class ViewRecorder extends BaseGenerationMiddleware {
    final List<List<Message>> views = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String name() {
      return "test/viewRecorder";
    }

    @Override
    public GenerationMiddleware newInstance() {
      return this;
    }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next) {
      views.add(params.getRequest().getMessages());
      return next.apply(ctx, params);
    }
  }
}
