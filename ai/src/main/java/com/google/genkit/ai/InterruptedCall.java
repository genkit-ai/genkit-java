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

package com.google.genkit.ai;

import com.google.genkit.core.JsonUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An interrupted tool call held by a middleware, with the verbs that answer it.
 *
 * <p>A middleware's {@code wrapTool} hook can hold a call before the tool runs by throwing {@link
 * ToolInterruptException}. The hold is the middleware's own question: the answer reaches that hook
 * (through {@link com.google.genkit.core.ActionContext#getResumed()}), never the tool, and the tool
 * then runs as a fresh call that may interrupt with a question of its own.
 *
 * <p>Middleware authors wrap {@link #ofMiddleware(String, Part)} in a method of their own that
 * fixes the middleware name and the answer type {@code R}, so applications claim the middleware's
 * holds the way they handle a tool's interrupts:
 *
 * <pre>{@code
 * ResumeOptions.Builder resume = ResumeOptions.builder();
 * for (Part part : response.getInterrupts()) {
 *   ToolApproval.toolApprovalInterrupted(part)
 *       .ifPresent(call -> resume.part(call.restart(ToolCallDecision.approve())));
 * }
 * }</pre>
 *
 * @param <R> the answer type the hook reads; it must serialize to a JSON object
 */
public final class InterruptedCall<R> {

  /** Part metadata key that marks an interrupted tool request and holds the interrupt data. */
  public static final String INTERRUPT_KEY = "interrupt";

  /** Part metadata key that keeps the interrupt data once an interrupt has been resolved. */
  public static final String RESOLVED_INTERRUPT_KEY = "resolvedInterrupt";

  /**
   * Part metadata key naming the middleware whose {@code wrapTool} hook raised the interrupt;
   * absent when the tool itself did.
   */
  public static final String INTERRUPTED_BY_KEY = "interruptedBy";

  /**
   * Part metadata key listing, in chain order, the middleware whose {@code wrapTool} hooks let the
   * call through before it interrupted.
   */
  public static final String RELEASED_BY_KEY = "releasedBy";

  /** Part metadata key that marks a restart and holds the answer (or {@code true} when bare). */
  public static final String RESUMED_KEY = "resumed";

  /** Part metadata key that keeps the original input on a restart that replaced it. */
  public static final String REPLACED_INPUT_KEY = "replacedInput";

  private final Part part;

  private InterruptedCall(Part part) {
    this.part = part;
  }

  /**
   * Claims {@code part} for the middleware named {@code middlewareName}: returns the call when the
   * part is an unresolved hold that the middleware's {@code wrapTool} hook raised. A hold is
   * matched by its {@code interruptedBy} stage, the middleware's name or, when the name repeats in
   * the chain, the name with a {@code "#n"} suffix. A tool's own interrupt, another middleware's
   * hold, and a hold that records no stage all return empty.
   *
   * @param middlewareName the registered middleware name
   * @param part an interrupt from {@link ModelResponse#getInterrupts()}
   * @param <R> the answer type the hook reads
   * @return the held call, or empty
   */
  public static <R> Optional<InterruptedCall<R>> ofMiddleware(String middlewareName, Part part) {
    if (part == null || part.getToolRequest() == null || part.getMetadata() == null) {
      return Optional.empty();
    }
    Map<String, Object> metadata = part.getMetadata();
    Object interrupt = metadata.get(INTERRUPT_KEY);
    if (interrupt == null || Boolean.FALSE.equals(interrupt)) {
      return Optional.empty();
    }
    Object stage = metadata.get(INTERRUPTED_BY_KEY);
    if (!(stage instanceof String) || !isStageOf((String) stage, middlewareName)) {
      return Optional.empty();
    }
    return Optional.of(new InterruptedCall<>(part));
  }

  /**
   * Reports whether {@code stage}, an {@code interruptedBy} value, names the middleware called
   * {@code name}: the name itself, or the name with the {@code "#n"} suffix a repeat in the chain
   * gets.
   *
   * @param stage the recorded stage
   * @param name the middleware name
   * @return whether the stage is the middleware's
   */
  public static boolean isStageOf(String stage, String name) {
    if (stage == null || stage.isEmpty() || name == null || name.isEmpty()) {
      return false;
    }
    if (stage.equals(name)) {
      return true;
    }
    if (!stage.startsWith(name + "#")) {
      return false;
    }
    String n = stage.substring(name.length() + 1);
    if (n.isEmpty()) {
      return false;
    }
    for (int i = 0; i < n.length(); i++) {
      if (n.charAt(i) < '0' || n.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the interrupted tool request part, as received.
   *
   * @return the part
   */
  public Part getPart() {
    return part;
  }

  /**
   * Returns the tool's input, as the model provided it.
   *
   * @return the input
   */
  public Object getInput() {
    return part.getToolRequest().getInput();
  }

  /**
   * Returns the data the middleware sent when it held the call, or an empty map for a bare hold.
   *
   * @return the interrupt data
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> getInterruptData() {
    Object data = part.getMetadata().get(INTERRUPT_KEY);
    return data instanceof Map ? (Map<String, Object>) data : Map.of();
  }

  /**
   * Returns the part that re-executes the call with {@code resume} delivered to the hook that held
   * it. Pass it to {@link ResumeOptions.Builder#part(Part)}. A {@code null} resume is a bare
   * restart.
   *
   * @param resume the answer the hook reads
   * @return the restart part
   */
  public Part restart(R resume) {
    return buildRestart(part, resume, null, false);
  }

  /**
   * Like {@link #restart(Object)}, with the tool's input replaced by {@code input}, for a person
   * who revised the request before answering. The original input is kept under {@value
   * #REPLACED_INPUT_KEY}.
   *
   * @param input the new input
   * @param resume the answer the hook reads
   * @return the restart part
   */
  public Part restartWithInput(Object input, R resume) {
    return buildRestart(part, resume, input, true);
  }

  /**
   * Returns the part that answers the call with {@code output} without running the hook or the
   * tool: the model sees {@code output} as the tool's result, so it must match the tool's output
   * schema, and it cannot carry a refusal.
   *
   * @param output the tool output the model sees
   * @return the response part
   */
  public Part respond(Object output) {
    ToolRequest request = part.getToolRequest();
    Part response =
        Part.toolResponse(new ToolResponse(request.getRef(), request.getName(), output));
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("interruptResponse", true);
    response.setMetadata(metadata);
    return response;
  }

  private static Part buildRestart(
      Part interrupt, Object resume, Object newInput, boolean replace) {
    ToolRequest source = interrupt.getToolRequest();
    Object input = replace ? newInput : source.getInput();
    ToolRequest request = new ToolRequest(source.getName(), input);
    request.setRef(source.getRef());
    Map<String, Object> metadata = new HashMap<>();
    if (interrupt.getMetadata() != null) {
      metadata.putAll(interrupt.getMetadata());
    }
    for (String key :
        List.of(
            INTERRUPT_KEY,
            RESOLVED_INTERRUPT_KEY,
            INTERRUPTED_BY_KEY,
            RELEASED_BY_KEY,
            RESUMED_KEY,
            REPLACED_INPUT_KEY,
            "pendingOutput",
            "source")) {
      metadata.remove(key);
    }
    metadata.put(RESUMED_KEY, resume == null ? Boolean.TRUE : toObject(resume));
    if (replace) {
      metadata.put(REPLACED_INPUT_KEY, source.getInput());
    }
    Part restart = Part.toolRequest(request);
    restart.setMetadata(metadata);
    request.setMetadata(new HashMap<>(metadata));
    return restart;
  }

  @SuppressWarnings("unchecked")
  private static Object toObject(Object resume) {
    if (resume instanceof Map) {
      return resume;
    }
    Object converted = JsonUtils.convert(resume, Map.class);
    if (!(converted instanceof Map)) {
      throw new IllegalArgumentException(
          "resume data must serialize to a JSON object, got " + resume.getClass().getName());
    }
    return converted;
  }
}
