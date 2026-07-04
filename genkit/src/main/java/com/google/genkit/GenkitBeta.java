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

package com.google.genkit;

import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.AgentStreamChunk;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.SessionRunner;
import com.google.genkit.ai.agent.ToolResume;
import com.google.genkit.core.GenkitException;
import com.google.genkit.prompt.ExecutablePrompt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Beta (experimental) API surface for Genkit.
 *
 * <p>This is the ergonomic, user-facing API for defining agents that run as bidi {@code agent}
 * actions (so they appear and run in the Dev UI). It mirrors the JavaScript {@code GenkitBeta}
 * (defineAgent / definePromptAgent / defineCustomAgent) and the Go {@code WithExperimental} gating.
 *
 * <p>All methods are gated behind the {@code experimental} flag (see {@link
 * GenkitOptions.Builder#experimental} or the {@code GENKIT_EXPERIMENTAL} environment variable).
 * When the flag is not enabled they throw a {@link GenkitException}.
 *
 * <p>Obtain an instance via {@link Genkit#beta()}.
 */
public final class GenkitBeta {

  private final Genkit genkit;

  /**
   * Constructs a GenkitBeta for the given Genkit instance. Package-private; use {@link
   * Genkit#beta()}.
   *
   * @param genkit the owning Genkit instance
   */
  GenkitBeta(Genkit genkit) {
    this.genkit = genkit;
  }

  /**
   * Throws if experimental features are not enabled on the owning Genkit instance.
   *
   * @throws GenkitException if the {@code experimental} flag is not set
   */
  private void requireExperimental() {
    if (!genkit.isExperimental()) {
      throw new GenkitException(
          "This is an experimental API. Enable experimental features via "
              + "GenkitOptions.builder().experimental(true) (or set the GENKIT_EXPERIMENTAL "
              + "environment variable to 'true') to use it.");
    }
  }

  /**
   * Defines a custom agent from an explicit {@link CustomAgentConfig} and {@link AgentFn}.
   *
   * <p>This is the lowest-level beta agent factory: the caller supplies the per-turn logic
   * directly. The agent is registered as a bidi {@code /agent/<name>} action (plus companion
   * snapshot/abort actions when a store is configured).
   *
   * @param <S> the custom session state type
   * @param config the custom agent configuration
   * @param fn the per-turn agent function
   * @return the registered agent
   * @throws GenkitException if experimental features are not enabled
   */
  public <S> Agent<S> defineCustomAgent(CustomAgentConfig<S> config, AgentFn<S> fn) {
    requireExperimental();
    return com.google.genkit.ai.agent.internal.AgentActions.defineCustomAgent(
        genkit.getRegistry(), config, fn);
  }

  /**
   * Defines an ergonomic, prompt-backed agent.
   *
   * <p>This is the {@code ai.defineAgent({name, system, tools, model, store})} entry point. It
   * builds a {@link CustomAgentConfig} from the facade config and synthesizes an {@link AgentFn}
   * that, for each turn, calls {@code generateStream} with the configured system prompt + tools and
   * the session history (which already includes the just-added user message), streams model chunks
   * back to the caller, and returns the model's response message as the turn result.
   *
   * @param <S> the custom session state type
   * @param config the agent configuration ({@code name} required)
   * @return the registered agent
   * @throws GenkitException if experimental features are not enabled
   */
  public <S> Agent<S> defineAgent(com.google.genkit.agent.AgentConfig<S> config) {
    requireExperimental();
    // Plain-system path: the system instructions are fixed for the life of the agent, so the
    // per-turn resolver simply returns the configured (static) system text on every turn.
    String system = config.getSystem();
    return defineGenerateBackedAgent(config, runner -> system);
  }

  /**
   * Defines an agent backed by a registered prompt.
   *
   * <p>The agent's system instructions are produced by rendering the named prompt's Handlebars
   * template <em>on every turn</em>: the prompt named by {@code config.getPromptName()} (falling
   * back to {@code config.getName()}) is loaded once via {@link Genkit#prompt(String)}, and each
   * turn its template is rendered with {@code config.getPromptInput()} merged with the current
   * session state (see {@link #renderPromptSystem}). This means template variables such as {@code
   * {{topic}}} interpolate the live {@code promptInput}/state values for that turn rather than
   * leaking through as raw {@code {{...}}} placeholders.
   *
   * <p>If no matching prompt can be loaded (or its template is blank), this falls back to {@code
   * config.getSystem()}, behaving like {@link #defineAgent(com.google.genkit.agent.AgentConfig)}.
   *
   * @param <S> the custom session state type
   * @param config the agent configuration ({@code name} required)
   * @return the registered agent
   * @throws GenkitException if experimental features are not enabled
   */
  public <S> Agent<S> definePromptAgent(com.google.genkit.agent.AgentConfig<S> config) {
    requireExperimental();
    // Resolve the backing prompt (its static template) once, at definition time; the actual
    // Handlebars render with promptInput/session-state happens per turn inside the SystemResolver.
    ExecutablePrompt<Map<String, Object>> prompt = resolvePrompt(config);
    SystemResolver<S> resolver =
        runner -> {
          if (prompt != null) {
            String rendered = renderPromptSystem(prompt, config, runner);
            if (rendered != null && !rendered.isBlank()) {
              return rendered;
            }
          }
          return config.getSystem();
        };
    return defineGenerateBackedAgent(config, resolver);
  }

  /**
   * Resolves the system instructions for a single turn of a generate-backed agent.
   *
   * <p>For {@link #defineAgent} this returns a fixed string; for {@link #definePromptAgent} it
   * renders the backing prompt's Handlebars template against {@code promptInput}/session state.
   *
   * @param <S> the custom session state type
   */
  @FunctionalInterface
  private interface SystemResolver<S> {
    /**
     * Produces the system prompt for the current turn.
     *
     * @param runner the session runner for this turn (carries current session state)
     * @return the system text to feed this turn's generate call, or {@code null} for none
     */
    String resolve(SessionRunner<S> runner);
  }

  /**
   * Builds a {@link CustomAgentConfig} from the facade config and registers a generate-backed agent
   * whose {@link AgentFn} runs one generate (streaming) call per turn, resolving the system prompt
   * for that turn via {@code systemResolver}.
   */
  private <S> Agent<S> defineGenerateBackedAgent(
      com.google.genkit.agent.AgentConfig<S> config, SystemResolver<S> systemResolver) {

    CustomAgentConfig<S> customConfig =
        CustomAgentConfig.<S>builder()
            .name(config.getName())
            .description(config.getDescription())
            .stateType(config.getStateType())
            .store(config.getStore())
            .clientTransform(config.getClientTransform())
            .build();

    AgentFn<S> fn = buildAgentFn(config, systemResolver);

    return com.google.genkit.ai.agent.internal.AgentActions.defineCustomAgent(
        genkit.getRegistry(), customConfig, fn);
  }

  /**
   * Builds the per-turn agent function: it reads the session history (which already includes the
   * user message added by {@code SessionRunner.runTurn}), generates a streaming response, forwards
   * model chunks to the caller, and returns the model's reply message.
   *
   * <p>When the turn is a resume turn ({@code ctx.resume() != null} — i.e. {@code
   * AgentChat.resume(...)} was called after a tool interrupt), this resumes the SAME generate call
   * that was interrupted rather than starting a fresh one: it appends the resume's tool-response
   * message to the session directly (so it is durably threaded into history — see {@code
   * genkit.agent.yaml}'s "interrupt resume state accumulation" conformance case) and passes {@link
   * ResumeOptions} built from {@link ToolResume} through to {@code generate}, using the session's
   * last message (the previously-interrupted model message, already in {@code runner.getMessages()}
   * from the interrupted turn) as the tail of the message history {@code generate} resumes from.
   */
  private <S> AgentFn<S> buildAgentFn(
      com.google.genkit.agent.AgentConfig<S> config, SystemResolver<S> systemResolver) {
    return (SessionRunner<S> runner, com.google.genkit.ai.agent.AgentFnContext ctx) -> {
      ToolResume resume = ctx.resume();

      // Resolve the system instructions for THIS turn. For defineAgent this is the fixed
      // config.getSystem(); for definePromptAgent this renders the backing prompt's Handlebars
      // template against the current promptInput/session state, so template variables interpolate
      // per turn rather than reaching the model as raw {{...}} placeholders.
      String system = systemResolver.resolve(runner);

      // Validate a resume directive against the resumed session's pending interrupts BEFORE running
      // generate. A respond/restart whose (name, ref) does not match a tool request in the last
      // model message — or a restart whose input differs from the original — is rejected with
      // INVALID_ARGUMENT, which SessionRunner turns into a graceful FAILED output (not a thrown
      // error). Mirrors JS resolve-tool-requests.ts:244-266 / Go generate.go:1142-1187.
      if (resume != null) {
        validateResumeDirectives(resume, runner.getMessages());
      }

      // Forward the run's request-scoped user context (e.g. {"auth": {...}}) into the generate
      // call so that tools executed during this turn observe it via ctx.getContext().
      Map<String, Object> userContext = ctx.context() != null ? ctx.context().getContext() : null;

      GenerateOptions.Builder<Object> optsBuilder =
          GenerateOptions.builder()
              .model(config.getModel())
              .system(system)
              .tools(config.getTools())
              .config(config.getConfig())
              .context(userContext)
              .maxTurns(config.getMaxTurns());

      // Snapshot the messages we hand to generate (the session history, including the just-added
      // user message, plus — on a resume — the previously-interrupted model message that is still
      // the session tail). generate resumes from this list; on the resume path,
      // Genkit.handleResumeOption requires the LAST message here to still be the interrupted MODEL
      // message (it builds its own tool-response message from ResumeOptions and appends it before
      // calling the model). So we do NOT pre-append any tool-response message to the runner.
      List<Message> sentMessages = new ArrayList<>(runner.getMessages());
      optsBuilder.messages(sentMessages);
      if (resume != null) {
        optsBuilder.resume(toResumeOptions(resume));
      }

      GenerateOptions<?> opts = optsBuilder.build();

      ModelResponse resp =
          genkit.generateStream(
              opts,
              chunk ->
                  ctx.sendChunk().accept(AgentStreamChunk.builder().modelChunk(chunk).build()));

      // Thread the FULL post-turn message history into session state (matches JS action.ts:427-431
      // and Go generate.go:876-879, which carry the intermediate model-tool-request and
      // tool-response messages alongside the final model message). ModelResponse.getMessages()
      // returns the request messages (which the tool loop grows in place) followed by the final
      // model message; everything past the messages we sent is newly produced this turn. The
      // generate call may prepend a synthesized system message, so account for that offset.
      int systemOffset = system != null ? 1 : 0;
      List<Message> allMessages = resp.getMessages();
      int newStart = systemOffset + sentMessages.size();
      Message finalMessage = resp.getMessage();
      if (allMessages != null && newStart >= 0 && newStart < allMessages.size()) {
        // Fold every newly-produced message except the terminal one directly into history; the
        // terminal message is returned as the AgentResult so AgentActions appends it (and surfaces
        // it as the output message).
        for (int i = newStart; i < allMessages.size() - 1; i++) {
          runner.addMessages(allMessages.get(i));
        }
        finalMessage = allMessages.get(allMessages.size() - 1);
      }

      return AgentResult.builder()
          .message(finalMessage)
          .finishReason(mapFinishReason(resp))
          .build();
    };
  }

  /**
   * Converts an agent-level {@link ToolResume} (parts) into generate-level {@link ResumeOptions}
   * (typed tool responses/requests), matching the shapes {@code Genkit.generate(...).resume(...)}
   * expects (see {@code samples/interrupts}).
   */
  private static ResumeOptions toResumeOptions(ToolResume resume) {
    ResumeOptions.Builder builder = ResumeOptions.builder();
    if (resume.getRespond() != null) {
      List<ToolResponse> responses = new ArrayList<>();
      for (Part part : resume.getRespond()) {
        if (part != null && part.getToolResponse() != null) {
          responses.add(part.getToolResponse());
        }
      }
      if (!responses.isEmpty()) {
        builder.respond(responses);
      }
    }
    if (resume.getRestart() != null) {
      List<ToolRequest> requests = new ArrayList<>();
      for (Part part : resume.getRestart()) {
        if (part != null && part.getToolRequest() != null) {
          ToolRequest req = part.getToolRequest();
          // Carry the restart directive's Part-level metadata (resumed / replacedInput) onto the
          // ToolRequest so it survives into ResumeOptions.restart (a List<ToolRequest> that drops
          // the Part wrapper). The generate loop re-attaches it to the restart Part so the tool
          // observes its resumed status. Without this, restart metadata would be silently lost.
          if (part.getMetadata() != null && !part.getMetadata().isEmpty()) {
            Map<String, Object> merged =
                req.getMetadata() != null ? new HashMap<>(req.getMetadata()) : new HashMap<>();
            merged.putAll(part.getMetadata());
            req.setMetadata(merged);
          }
          requests.add(req);
        }
      }
      if (!requests.isEmpty()) {
        builder.restart(requests);
      }
    }
    return builder.build();
  }

  /**
   * Validates a resume directive against the pending interrupted tool requests in the resumed
   * session history. Throws {@link GenkitException} with {@code INVALID_ARGUMENT} — which {@code
   * SessionRunner} turns into a graceful {@code finishReason: failed} output — when:
   *
   * <ul>
   *   <li>a {@code respond} directive's (name, ref) matches no tool request in the last model
   *       message ("not found in session history"), or
   *   <li>a {@code restart} directive's (name, ref) matches none ("not found in session history"),
   *       or its input differs from the original tool request's input ("modified inputs").
   * </ul>
   *
   * <p>Matching key is (name, ref), against the tool requests in the last model message of the
   * session (the interrupted turn). Mirrors JS {@code resolve-tool-requests.ts:244-266} and Go
   * {@code generate.go:1142-1187}.
   */
  private static void validateResumeDirectives(ToolResume resume, List<Message> history) {
    List<ToolRequest> pending = lastModelToolRequests(history);

    if (resume.getRespond() != null) {
      for (Part part : resume.getRespond()) {
        if (part == null || part.getToolResponse() == null) {
          continue;
        }
        ToolResponse tr = part.getToolResponse();
        if (findToolRequest(pending, tr.getName(), tr.getRef()) == null) {
          throw GenkitException.builder()
              .message(
                  "tool response for '"
                      + tr.getName()
                      + "' (ref="
                      + tr.getRef()
                      + ") not found in session history")
              .errorCode("INVALID_ARGUMENT")
              .build();
        }
      }
    }

    if (resume.getRestart() != null) {
      for (Part part : resume.getRestart()) {
        if (part == null || part.getToolRequest() == null) {
          continue;
        }
        ToolRequest req = part.getToolRequest();
        ToolRequest original = findToolRequest(pending, req.getName(), req.getRef());
        if (original == null) {
          throw GenkitException.builder()
              .message(
                  "restart for tool '"
                      + req.getName()
                      + "' (ref="
                      + req.getRef()
                      + ") not found in session history")
              .errorCode("INVALID_ARGUMENT")
              .build();
        }
        if (!inputsEqual(original.getInput(), req.getInput())) {
          throw GenkitException.builder()
              .message(
                  "restart for tool '"
                      + req.getName()
                      + "' has modified inputs (does not match the original tool request)")
              .errorCode("INVALID_ARGUMENT")
              .build();
        }
      }
    }
  }

  /**
   * Returns the tool requests in the last {@code model}-role message of {@code history} (the
   * interrupted turn), or an empty list if there is no such message.
   */
  private static List<ToolRequest> lastModelToolRequests(List<Message> history) {
    List<ToolRequest> out = new ArrayList<>();
    if (history == null) {
      return out;
    }
    for (int i = history.size() - 1; i >= 0; i--) {
      Message m = history.get(i);
      if (m == null || m.getRole() != Role.MODEL) {
        continue;
      }
      if (m.getContent() != null) {
        for (Part p : m.getContent()) {
          if (p.getToolRequest() != null) {
            out.add(p.getToolRequest());
          }
        }
      }
      break; // only the most recent model message
    }
    return out;
  }

  /** Finds a tool request in {@code pending} matching {@code name} and {@code ref}, or null. */
  private static ToolRequest findToolRequest(List<ToolRequest> pending, String name, String ref) {
    for (ToolRequest tr : pending) {
      if (java.util.Objects.equals(tr.getName(), name)
          && java.util.Objects.equals(tr.getRef(), ref)) {
        return tr;
      }
    }
    return null;
  }

  /**
   * Compares two tool inputs for equality by canonical JSON (tolerates map/POJO representations).
   */
  private static boolean inputsEqual(Object a, Object b) {
    try {
      var mapper = com.google.genkit.core.JsonUtils.getObjectMapper();
      return mapper.valueToTree(a).equals(mapper.valueToTree(b));
    } catch (Exception e) {
      return java.util.Objects.equals(a, b);
    }
  }

  /**
   * Loads the backing {@link ExecutablePrompt} for a prompt-backed agent, once, at definition time.
   * Attempts the prompt named by {@code config.getPromptName()} (falling back to {@code
   * config.getName()}). Returns {@code null} when no matching prompt can be loaded (in which case
   * the agent falls back to {@code config.getSystem()} per turn). The prompt's template is static;
   * only the per-turn {@linkplain #renderPromptSystem render} varies with {@code
   * promptInput}/state.
   */
  private ExecutablePrompt<Map<String, Object>> resolvePrompt(
      com.google.genkit.agent.AgentConfig<?> config) {
    String promptName = config.getPromptName() != null ? config.getPromptName() : config.getName();
    if (promptName == null) {
      return null;
    }
    try {
      return genkit.prompt(promptName);
    } catch (Exception e) {
      // No matching prompt available — the caller falls back to the explicit system text.
      return null;
    }
  }

  /**
   * Renders a prompt-backed agent's system instructions for the current turn by applying the
   * prompt's Handlebars template to a rendering context built from {@code config.getPromptInput()}
   * merged with the current session state. This reuses the existing dotprompt/Handlebars engine
   * ({@link ExecutablePrompt#render}) rather than introducing a separate templating layer.
   *
   * <p>Context precedence (later wins): the session's custom state (when it is a {@code Map}), then
   * the configured {@code promptInput}. When {@code promptInput} is a non-{@code Map} POJO it is
   * used directly as the render context (its bean properties are visible to Handlebars) and session
   * state is not merged. On any render failure this returns {@code null} so the caller falls back
   * to {@code config.getSystem()}.
   */
  private String renderPromptSystem(
      ExecutablePrompt<Map<String, Object>> prompt,
      com.google.genkit.agent.AgentConfig<?> config,
      SessionRunner<?> runner) {
    try {
      Object promptInput = config.getPromptInput();
      if (promptInput != null && !(promptInput instanceof Map)) {
        // POJO promptInput: render directly against the bean so its fields interpolate. The backing
        // DotPrompt is typed to Map<String,Object>, but Handlebars renders any bean, so we render
        // through a raw reference to feed the POJO context.
        @SuppressWarnings({"unchecked", "rawtypes"})
        String rendered =
            ((com.google.genkit.prompt.DotPrompt) prompt.getDotPrompt()).render(promptInput);
        return rendered;
      }
      Map<String, Object> context = new HashMap<>();
      Object custom = runner != null ? runner.getCustom() : null;
      if (custom instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> customMap = (Map<String, Object>) custom;
        context.putAll(customMap);
      }
      if (promptInput instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputMap = (Map<String, Object>) promptInput;
        context.putAll(inputMap);
      }
      return prompt.render(context);
    } catch (Exception e) {
      // Rendering failed (e.g. missing helper) — fall back to the explicit system text.
      return null;
    }
  }

  /**
   * Maps a model {@link FinishReason} to the agent-level {@link AgentFinishReason}. Defaults to
   * {@link AgentFinishReason#STOP} when the response has no finish reason.
   */
  private static AgentFinishReason mapFinishReason(ModelResponse resp) {
    FinishReason fr = resp != null ? resp.getFinishReason() : null;
    if (fr == null) {
      return AgentFinishReason.STOP;
    }
    switch (fr) {
      case LENGTH:
        return AgentFinishReason.LENGTH;
      case BLOCKED:
        return AgentFinishReason.BLOCKED;
      case INTERRUPTED:
        return AgentFinishReason.INTERRUPTED;
      case OTHER:
        return AgentFinishReason.OTHER;
      case UNKNOWN:
        return AgentFinishReason.UNKNOWN;
      case STOP:
      default:
        return AgentFinishReason.STOP;
    }
  }
}
