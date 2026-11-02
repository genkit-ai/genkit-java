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

import com.google.genkit.ai.Usage;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * AgentSessionContext binds a {@link Session} to the current thread so that prompts, middleware,
 * and tools can access the active session without passing it through every call frame.
 *
 * <p>A {@link ThreadLocal} is used for binding; the context is always cleared after {@link #run} or
 * {@link #call} returns (even if the body throws).
 */
public final class AgentSessionContext {

  private static final ThreadLocal<Session<?>> CURRENT = new ThreadLocal<>();

  private static final ThreadLocal<Supplier<Usage>> SESSION_USAGE = new ThreadLocal<>();

  private AgentSessionContext() {}

  /**
   * Executes {@code body} with {@code session} bound to the current thread context. Clears the
   * binding when the body returns or throws.
   *
   * @param session the session to bind (must not be null)
   * @param body the runnable to execute
   */
  public static void run(Session<?> session, Runnable body) {
    Session<?> prior = CURRENT.get();
    CURRENT.set(session);
    try {
      body.run();
    } finally {
      if (prior == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(prior);
      }
    }
  }

  /**
   * Executes {@code body} with {@code session} bound to the current thread context and returns the
   * result. Clears the binding when the body returns or throws.
   *
   * @param <T> the return type
   * @param session the session to bind (must not be null)
   * @param body the supplier to execute
   * @return the value returned by {@code body}
   */
  public static <T> T call(Session<?> session, Supplier<T> body) {
    Session<?> prior = CURRENT.get();
    CURRENT.set(session);
    try {
      return body.get();
    } finally {
      if (prior == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(prior);
      }
    }
  }

  /**
   * Returns the {@link Session} currently bound to this thread, or {@code null} if none.
   *
   * @return the current session, or null
   */
  public static Session<?> current() {
    return CURRENT.get();
  }

  /**
   * Returns the current session viewed as an {@link ArtifactStore}, or {@code null} if no session
   * is bound to this thread.
   *
   * @return the current session as an {@link ArtifactStore}, or null
   */
  public static ArtifactStore currentArtifactStore() {
    return CURRENT.get();
  }

  /**
   * Returns the usage that the agent session this thread runs under has recorded so far, over all
   * its turns and invocations, or empty when the thread runs under no agent session or under a
   * client-managed one, whose state, usage included, comes back from the caller.
   *
   * <p>The usage is live: each model call of the session adds to it as it ends. To track a run
   * against a session limit, read it once, when the run starts, and add the calls the run makes,
   * which a {@link com.google.genkit.ai.ModelObserver} reports.
   *
   * @return a copy of the session's usage (zero when nothing was recorded yet), or empty
   */
  public static Optional<Usage> sessionUsage() {
    Supplier<Usage> get = SESSION_USAGE.get();
    if (get == null) {
      return Optional.empty();
    }
    Usage u = Usage.sum(get.get());
    return Optional.of(u != null ? u : new Usage());
  }

  /**
   * Runs {@code body} with {@code usage} as the source of {@link #sessionUsage()}. The agent
   * runtime calls it for each turn, with a null source for a client-managed agent so that an
   * enclosing session's usage does not leak into it. Applications do not call it.
   *
   * @param <T> the return type
   * @param usage supplies the session's usage, or null when the session's usage is not tracked
   * @param body the code to run
   * @return the value returned by {@code body}
   */
  public static <T> T callWithSessionUsage(Supplier<Usage> usage, Supplier<T> body) {
    Supplier<Usage> prior = SESSION_USAGE.get();
    SESSION_USAGE.set(usage);
    try {
      return body.get();
    } finally {
      if (prior == null) {
        SESSION_USAGE.remove();
      } else {
        SESSION_USAGE.set(prior);
      }
    }
  }
}
