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

import static org.junit.jupiter.api.Assertions.fail;

import com.google.genkit.Genkit;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateActionOptions;
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
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.JsonUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared fixtures for the Skills middleware tests. */
final class SkillsTestSupport {

  private SkillsTestSupport() {}

  /** Creates dir/name/SKILL.md with the given contents and returns the skill directory. */
  static Path writeSkill(Path dir, String name, String contents) throws IOException {
    Path skillDir = dir.resolve(name);
    Files.createDirectories(skillDir);
    Files.write(skillDir.resolve("SKILL.md"), contents.getBytes(StandardCharsets.UTF_8));
    return skillDir;
  }

  /**
   * Creates a skills directory with two skills: one with a YAML frontmatter description and one
   * without. Returns the absolute path to the skills directory.
   */
  static Path setupSkillsDir(Path tmp) throws IOException {
    Path skillsDir = tmp.resolve("skills");
    writeSkill(
        skillsDir,
        "python",
        "---\nname: python\ndescription: A python expert skill\n---\nPython prompt content");
    writeSkill(skillsDir, "javascript", "Just javascript content");
    return skillsDir.toAbsolutePath();
  }

  static Skills skills(Path... paths) {
    Skills.Options o = new Skills.Options();
    o.skillPaths = new ArrayList<>();
    for (Path p : paths) {
      o.skillPaths.add(p.toString());
    }
    return new Skills(o);
  }

  static Skills.Options opts(Path... paths) {
    Skills.Options o = new Skills.Options();
    o.skillPaths = new ArrayList<>();
    for (Path p : paths) {
      o.skillPaths.add(p.toString());
    }
    return o;
  }

  /** Registers a model whose reply is computed from the request. */
  static Model registerModel(Genkit genkit, String name, Function<ModelRequest, Message> reply) {
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
          public ModelResponse run(ActionContext ctx, ModelRequest request) {
            return run(ctx, request, null);
          }

          @Override
          public ModelResponse run(
              ActionContext ctx, ModelRequest request, Consumer<ModelResponseChunk> cb) {
            ModelResponse response =
                new ModelResponse(List.of(new Candidate(reply.apply(request), FinishReason.STOP)));
            response.setFinishReason(FinishReason.STOP);
            response.setRequest(request);
            return response;
          }
        };
    genkit.registerModel(m);
    return m;
  }

  /** Registers a model that records the messages it receives and replies with fixed text. */
  static AtomicReference<List<Message>> captureModel(Genkit genkit, String name) {
    AtomicReference<List<Message>> captured = new AtomicReference<>(List.of());
    registerModel(
        genkit,
        name,
        req -> {
          captured.set(new ArrayList<>(req.getMessages()));
          return Message.model("mock response");
        });
    return captured;
  }

  /**
   * Registers a model that issues a single tool request on its first call, then returns "done" once
   * the tool response is visible in the messages.
   */
  static void toolCallingModel(
      Genkit genkit, String name, String toolName, Map<String, Object> input) {
    registerModel(
        genkit,
        name,
        req -> {
          for (Message msg : req.getMessages()) {
            for (Part part : msg.getContent()) {
              if (part.isToolResponse()) {
                return Message.model("done");
              }
            }
          }
          return toolRequestMessage(toolName, input);
        });
  }

  static Message toolRequestMessage(String toolName, Map<String, Object> input) {
    List<Part> parts = new ArrayList<>();
    parts.add(Part.toolRequest(new ToolRequest(toolName, input)));
    return new Message(Role.MODEL, parts);
  }

  /** Wires s into a generate call against a capturing model and returns the messages it saw. */
  static List<Message> runSkills(Skills s, String prompt) {
    Genkit genkit = Genkit.builder().build();
    AtomicReference<List<Message>> captured = captureModel(genkit, "test/capture");
    genkit.generate(GenerateOptions.builder().model("test/capture").prompt(prompt).use(s).build());
    return captured.get();
  }

  /** Drives a single tool call through generate and returns the tool response output. */
  static String callSkillTool(Skills s, String toolName, Map<String, Object> input) {
    Genkit genkit = Genkit.builder().build();
    toolCallingModel(genkit, "test/toolcaller", toolName, input);
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.builder().model("test/toolcaller").prompt("go").use(s).build());
    Part part = findToolResponse(resp.getMessages(), toolName);
    if (part == null) {
      fail("no " + toolName + " tool response in history: " + resp.getMessages());
    }
    Object out = part.getToolResponse().getOutput();
    return out instanceof String ? (String) out : null;
  }

  static Part findToolResponse(List<Message> msgs, String toolName) {
    Part found = null;
    for (Message msg : msgs) {
      for (Part p : msg.getContent()) {
        if (p.isToolResponse() && toolName.equals(p.getToolResponse().getName())) {
          found = p;
        }
      }
    }
    return found;
  }

  static List<String> toolResponseOutputs(List<Message> msgs, String toolName) {
    List<String> outputs = new ArrayList<>();
    for (Message msg : msgs) {
      for (Part p : msg.getContent()) {
        if (p.isToolResponse() && toolName.equals(p.getToolResponse().getName())) {
          Object out = p.getToolResponse().getOutput();
          outputs.add(out instanceof String ? (String) out : String.valueOf(out));
        }
      }
    }
    return outputs;
  }

  static Message findSystem(List<Message> msgs) {
    for (Message msg : msgs) {
      if (msg.getRole() == Role.SYSTEM) {
        return msg;
      }
    }
    return null;
  }

  /** Concatenates every text part of the system message. */
  static String systemText(List<Message> msgs) {
    Message sys = findSystem(msgs);
    if (sys == null) {
      return "";
    }
    StringBuilder b = new StringBuilder();
    for (Part p : sys.getContent()) {
      if (p.getText() != null) {
        b.append(p.getText());
      }
    }
    return b.toString();
  }

  /** Builds the per-call middleware instance, which scans the skill paths. */
  static Skills mustHooks(Skills s) {
    return (Skills) s.newInstance();
  }

  static Tool<?, ?> findTool(Skills h, String name) {
    for (Tool<?, ?> t : h.tools()) {
      if (t.getName().equals(name)) {
        return t;
      }
    }
    return null;
  }

  static List<String> toolNames(Skills h) {
    List<String> names = new ArrayList<>();
    for (Tool<?, ?> t : h.tools()) {
      names.add(t.getName());
    }
    return names;
  }

  static ActionContext ctx() {
    return new ActionContext(Genkit.builder().build().getRegistry());
  }

  /** Runs a tool directly with a map input, returning its output. */
  @SuppressWarnings("unchecked")
  static Object runRaw(Tool<?, ?> tool, Map<String, Object> input) {
    Tool<Object, Object> t = (Tool<Object, Object>) tool;
    Object in = JsonUtils.convert(input, t.getInputClass());
    return t.run(ctx(), in);
  }

  /** The innermost tool step, as the generate loop runs it. */
  static ToolNext coreToolNext() {
    return (actx, tparams) -> {
      ToolRequest req = tparams.getRequest();
      @SuppressWarnings("unchecked")
      Tool<Object, Object> t = (Tool<Object, Object>) tparams.getTool();
      Object in = req.getInput();
      if (t.getInputClass() != null && in != null && !t.getInputClass().isInstance(in)) {
        in = JsonUtils.convert(in, t.getInputClass());
      }
      Object result = t.run(actx, in);
      return Part.toolResponse(new ToolResponse(req.getRef(), req.getName(), result));
    };
  }

  /**
   * Runs wrapGenerate once so the instance observes a turn with the given messages, which is what
   * rebuilds the activated-skill set. Returns the messages passed on to the next step.
   */
  static List<Message> seed(Skills h, Message... msgs) {
    GenerateActionOptions req = new GenerateActionOptions();
    req.setMessages(new ArrayList<>(List.of(msgs)));
    AtomicReference<List<Message>> seen = new AtomicReference<>();
    h.wrapGenerate(
        ctx(),
        new GenerateParams(req, 0),
        (c, p) -> {
          seen.set(p.getRequest().getMessages());
          return new ModelResponse();
        });
    return seen.get();
  }

  /** Calls the activation tool directly and returns its output, or the error text. */
  static String activate(Skills h, String name) {
    try {
      Object out = runRaw(h.tools().get(0), Map.of("skillName", name));
      return out instanceof String ? (String) out : String.valueOf(out);
    } catch (RuntimeException e) {
      return e.getMessage();
    }
  }
}
