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

package com.google.genkit.ai.agent;

import com.google.genkit.core.ActionContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Context handed to an {@link AgentFn} for one invocation.
 *
 * <p>Provides access to a stream-chunk emitter and an abort signal that allows the agent to detect
 * cancellation. Stream emission of custom-patch / artifacts is wired in Task 4.3; here the {@code
 * sendChunk} consumer is available for the agent to emit model chunks.
 *
 * <p>It also carries the run's {@link ActionContext} (via {@link #context()}) so the agent function
 * can read the request-scoped user context (e.g. {@code {"auth": {...}}}) and forward it into the
 * generate call so tools observe it.
 */
public final class AgentFnContext {

  private final Consumer<AgentStreamChunk> sendChunk;
  private final AtomicBoolean abortSignal;
  private final ActionContext context;
  private final ToolResume resume;

  /**
   * Constructs an AgentFnContext without an ActionContext.
   *
   * @param sendChunk consumer that emits a streaming chunk to the caller; may be a no-op
   * @param abortSignal shared flag; set to {@code true} by the runtime to request cancellation
   */
  public AgentFnContext(Consumer<AgentStreamChunk> sendChunk, AtomicBoolean abortSignal) {
    this(sendChunk, abortSignal, null);
  }

  /**
   * Constructs an AgentFnContext.
   *
   * @param sendChunk consumer that emits a streaming chunk to the caller; may be a no-op
   * @param abortSignal shared flag; set to {@code true} by the runtime to request cancellation
   * @param context the run's ActionContext (carries the user context); may be null
   */
  public AgentFnContext(
      Consumer<AgentStreamChunk> sendChunk, AtomicBoolean abortSignal, ActionContext context) {
    this(sendChunk, abortSignal, context, null);
  }

  /**
   * Constructs an AgentFnContext with resume data.
   *
   * @param sendChunk consumer that emits a streaming chunk to the caller; may be a no-op
   * @param abortSignal shared flag; set to {@code true} by the runtime to request cancellation
   * @param context the run's ActionContext (carries the user context); may be null
   * @param resume the current turn's tool-resume data, or {@code null} if this is not a resume turn
   */
  public AgentFnContext(
      Consumer<AgentStreamChunk> sendChunk,
      AtomicBoolean abortSignal,
      ActionContext context,
      ToolResume resume) {
    this.sendChunk = sendChunk != null ? sendChunk : chunk -> {};
    this.abortSignal = abortSignal != null ? abortSignal : new AtomicBoolean(false);
    this.context = context;
    this.resume = resume;
  }

  /**
   * Returns the stream-chunk emitter for this invocation.
   *
   * @return the {@link AgentStreamChunk} consumer (never null)
   */
  public Consumer<AgentStreamChunk> sendChunk() {
    return sendChunk;
  }

  /**
   * Returns the abort signal for this invocation.
   *
   * @return an {@link AtomicBoolean} that is {@code true} when the caller has requested
   *     cancellation
   */
  public AtomicBoolean abortSignal() {
    return abortSignal;
  }

  /**
   * Convenience method to check whether the abort signal has been set.
   *
   * @return {@code true} if the caller has requested cancellation
   */
  public boolean isAborted() {
    return abortSignal.get();
  }

  /**
   * Returns the run's {@link ActionContext}, which carries the request-scoped user context.
   *
   * @return the ActionContext for this invocation, or null if none was provided
   */
  public ActionContext context() {
    return context;
  }

  /**
   * Returns this turn's tool-resume data, i.e. the {@code respond}/{@code restart} parts passed to
   * {@code AgentChat.resume(...)} (or the raw {@code AgentInput.resume} field for lower-level
   * callers).
   *
   * @return the {@link ToolResume} for this turn, or {@code null} if this is not a resume turn
   */
  public ToolResume resume() {
    return resume;
  }
}
