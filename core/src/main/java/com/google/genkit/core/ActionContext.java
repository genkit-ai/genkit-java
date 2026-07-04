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

package com.google.genkit.core;

import com.google.genkit.core.tracing.SpanContext;
import java.util.Map;

/**
 * ActionContext provides context for action execution including tracing and flow information. It is
 * passed to all action executions and carries request-scoped state.
 */
public class ActionContext {

  private final SpanContext spanContext;
  private final String flowName;
  private final String spanPath;
  private final Registry registry;
  private final String sessionId;
  private final String threadName;
  private final Map<String, Object> context;
  private final Object resumed;
  private final Object originalInput;

  /**
   * Creates a new ActionContext.
   *
   * @param spanContext the tracing span context, may be null
   * @param flowName the name of the enclosing flow, may be null
   * @param spanPath the current span path for tracing
   * @param registry the Genkit registry
   * @param sessionId the session ID for multi-turn conversations
   * @param threadName the thread name for grouping related requests
   * @param context the request-scoped user context (e.g. {@code {"auth": {...}}}), may be null
   */
  public ActionContext(
      SpanContext spanContext,
      String flowName,
      String spanPath,
      Registry registry,
      String sessionId,
      String threadName,
      Map<String, Object> context) {
    this(spanContext, flowName, spanPath, registry, sessionId, threadName, context, null, null);
  }

  /**
   * Creates a new ActionContext including resume-awareness fields.
   *
   * @param spanContext the tracing span context, may be null
   * @param flowName the name of the enclosing flow, may be null
   * @param spanPath the current span path for tracing
   * @param registry the Genkit registry
   * @param sessionId the session ID for multi-turn conversations
   * @param threadName the thread name for grouping related requests
   * @param context the request-scoped user context (e.g. {@code {"auth": {...}}}), may be null
   * @param resumed the resume metadata attached when this action is being re-invoked after an
   *     interrupt/restart (mirrors JS {@code ToolRunOptions.resumed} / Go {@code
   *     ToolContext.Resumed}); {@code null} on a normal (non-resumed) invocation
   * @param originalInput the tool request's original input (before any restart-replaced input);
   *     mirrors Go {@code ToolContext.OriginalInput}; {@code null} when not resuming
   */
  public ActionContext(
      SpanContext spanContext,
      String flowName,
      String spanPath,
      Registry registry,
      String sessionId,
      String threadName,
      Map<String, Object> context,
      Object resumed,
      Object originalInput) {
    this.spanContext = spanContext;
    this.flowName = flowName;
    this.spanPath = spanPath;
    this.registry = registry;
    this.sessionId = sessionId;
    this.threadName = threadName;
    this.context = context;
    this.resumed = resumed;
    this.originalInput = originalInput;
  }

  /**
   * Creates a new ActionContext.
   *
   * @param spanContext the tracing span context, may be null
   * @param flowName the name of the enclosing flow, may be null
   * @param spanPath the current span path for tracing
   * @param registry the Genkit registry
   * @param sessionId the session ID for multi-turn conversations
   * @param threadName the thread name for grouping related requests
   */
  public ActionContext(
      SpanContext spanContext,
      String flowName,
      String spanPath,
      Registry registry,
      String sessionId,
      String threadName) {
    this(spanContext, flowName, spanPath, registry, sessionId, threadName, null);
  }

  /**
   * Creates a new ActionContext.
   *
   * @param spanContext the tracing span context, may be null
   * @param flowName the name of the enclosing flow, may be null
   * @param spanPath the current span path for tracing
   * @param registry the Genkit registry
   */
  public ActionContext(
      SpanContext spanContext, String flowName, String spanPath, Registry registry) {
    this(spanContext, flowName, spanPath, registry, null, null);
  }

  /**
   * Creates a new ActionContext.
   *
   * @param spanContext the tracing span context, may be null
   * @param flowName the name of the enclosing flow, may be null
   * @param registry the Genkit registry
   */
  public ActionContext(SpanContext spanContext, String flowName, Registry registry) {
    this(spanContext, flowName, null, registry);
  }

  /**
   * Creates a new ActionContext with default values.
   *
   * @param registry the Genkit registry
   */
  public ActionContext(Registry registry) {
    this(null, null, null, registry);
  }

  /**
   * Returns the tracing span context.
   *
   * @return the span context, or null if tracing is not active
   */
  public SpanContext getSpanContext() {
    return spanContext;
  }

  /**
   * Returns the name of the enclosing flow, if any.
   *
   * @return the flow name, or null if not in a flow context
   */
  public String getFlowName() {
    return flowName;
  }

  /**
   * Returns the current span path for tracing.
   *
   * @return the span path, or null if not in a traced context
   */
  public String getSpanPath() {
    return spanPath;
  }

  /**
   * Returns the Genkit registry.
   *
   * @return the registry
   */
  public Registry getRegistry() {
    return registry;
  }

  /**
   * Returns the session ID for multi-turn conversations.
   *
   * @return the session ID, or null if not set
   */
  public String getSessionId() {
    return sessionId;
  }

  /**
   * Returns the thread name for grouping related requests.
   *
   * @return the thread name, or null if not set
   */
  public String getThreadName() {
    return threadName;
  }

  /**
   * Returns the request-scoped user context.
   *
   * <p>This is the {@code context} object injected by callers (such as the Dev UI "Execution
   * context" panel or the reflection/serving layers), e.g. {@code {"auth": {"user": "alice"}}}. It
   * is threaded through the run so tools and flows can read it via {@link #getContext()}.
   *
   * @return the user context map, or null if not set
   */
  public Map<String, Object> getContext() {
    return context;
  }

  /**
   * Returns the resume metadata attached when this action is being re-invoked after an
   * interrupt/restart, or {@code null} on a normal invocation.
   *
   * <p>Mirrors JS {@code ToolRunOptions.resumed} and Go {@code ToolContext.Resumed}: a
   * restart-aware tool can inspect this to distinguish a fresh call from a resumed one and read the
   * client-supplied approval/confirmation payload.
   *
   * @return the resumed metadata value, or {@code null} if this is not a resumed invocation
   */
  public Object getResumed() {
    return resumed;
  }

  /**
   * Returns {@code true} if this action is being re-invoked after an interrupt/restart (i.e. {@link
   * #getResumed()} is non-null).
   *
   * @return whether this is a resumed invocation
   */
  public boolean isResumed() {
    return resumed != null;
  }

  /**
   * Returns the tool request's original input (before any restart-replaced input), mirroring Go
   * {@code ToolContext.OriginalInput}. {@code null} when not resuming or when the original input
   * was not preserved.
   *
   * @return the original input, or {@code null}
   */
  public Object getOriginalInput() {
    return originalInput;
  }

  /**
   * Creates a new ActionContext with a different flow name.
   *
   * @param flowName the new flow name
   * @return a new ActionContext with the updated flow name
   */
  public ActionContext withFlowName(String flowName) {
    return new ActionContext(
        this.spanContext,
        flowName,
        this.spanPath,
        this.registry,
        this.sessionId,
        this.threadName,
        this.context,
        this.resumed,
        this.originalInput);
  }

  /**
   * Creates a new ActionContext with a different span context.
   *
   * @param spanContext the new span context
   * @return a new ActionContext with the updated span context
   */
  public ActionContext withSpanContext(SpanContext spanContext) {
    return new ActionContext(
        spanContext,
        this.flowName,
        this.spanPath,
        this.registry,
        this.sessionId,
        this.threadName,
        this.context,
        this.resumed,
        this.originalInput);
  }

  /**
   * Creates a new ActionContext with a different span path.
   *
   * @param spanPath the new span path
   * @return a new ActionContext with the updated span path
   */
  public ActionContext withSpanPath(String spanPath) {
    return new ActionContext(
        this.spanContext,
        this.flowName,
        spanPath,
        this.registry,
        this.sessionId,
        this.threadName,
        this.context,
        this.resumed,
        this.originalInput);
  }

  /**
   * Creates a new ActionContext with a session ID.
   *
   * @param sessionId the session ID
   * @return a new ActionContext with the session ID
   */
  public ActionContext withSessionId(String sessionId) {
    return new ActionContext(
        this.spanContext,
        this.flowName,
        this.spanPath,
        this.registry,
        sessionId,
        this.threadName,
        this.context,
        this.resumed,
        this.originalInput);
  }

  /**
   * Creates a new ActionContext with a thread name.
   *
   * @param threadName the thread name
   * @return a new ActionContext with the thread name
   */
  public ActionContext withThreadName(String threadName) {
    return new ActionContext(
        this.spanContext,
        this.flowName,
        this.spanPath,
        this.registry,
        this.sessionId,
        threadName,
        this.context,
        this.resumed,
        this.originalInput);
  }

  /**
   * Creates a new ActionContext with the given request-scoped user context.
   *
   * @param context the user context map (e.g. {@code {"auth": {...}}}), may be null
   * @return a new ActionContext with the updated user context
   */
  public ActionContext withContext(Map<String, Object> context) {
    return new ActionContext(
        this.spanContext,
        this.flowName,
        this.spanPath,
        this.registry,
        this.sessionId,
        this.threadName,
        context,
        this.resumed,
        this.originalInput);
  }

  /**
   * Creates a new ActionContext carrying resume-awareness for a restarted tool call.
   *
   * @param resumed the resume metadata value (from the restart part's {@code metadata.resumed});
   *     may be {@code null}
   * @param originalInput the tool request's original input; may be {@code null}
   * @return a new ActionContext with the resume-awareness fields set
   */
  public ActionContext withResumed(Object resumed, Object originalInput) {
    return new ActionContext(
        this.spanContext,
        this.flowName,
        this.spanPath,
        this.registry,
        this.sessionId,
        this.threadName,
        this.context,
        resumed,
        originalInput);
  }

  /**
   * Creates a builder for ActionContext.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for ActionContext. */
  public static class Builder {
    private SpanContext spanContext;
    private String flowName;
    private String spanPath;
    private Registry registry;
    private String sessionId;
    private String threadName;
    private Map<String, Object> context;

    public Builder spanContext(SpanContext spanContext) {
      this.spanContext = spanContext;
      return this;
    }

    public Builder flowName(String flowName) {
      this.flowName = flowName;
      return this;
    }

    public Builder spanPath(String spanPath) {
      this.spanPath = spanPath;
      return this;
    }

    public Builder registry(Registry registry) {
      this.registry = registry;
      return this;
    }

    public Builder sessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder threadName(String threadName) {
      this.threadName = threadName;
      return this;
    }

    public Builder context(Map<String, Object> context) {
      this.context = context;
      return this;
    }

    public ActionContext build() {
      if (registry == null) {
        throw new IllegalStateException("registry is required");
      }
      return new ActionContext(
          spanContext, flowName, spanPath, registry, sessionId, threadName, context);
    }
  }
}
