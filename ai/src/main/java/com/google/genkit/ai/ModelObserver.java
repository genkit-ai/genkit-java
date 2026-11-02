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

package com.google.genkit.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the model calls made while a block of code runs.
 *
 * <p>An observer added with {@link #observe(ModelObserver, Supplier)} sees every model call the
 * block makes on its thread, whoever makes it: a generate tool loop, a nested generate inside a
 * tool, a sub-agent the tool runs, or a middleware that calls a model directly. That is what sets
 * it apart from generation middleware, whose hooks wrap the calls of one generate and can change
 * them; an observer can only watch.
 *
 * <p>Observers run synchronously on the thread that made the call, after the call ends and before
 * the caller continues. They must not modify what they are given and should not throw: an exception
 * an observer throws is logged and ignored. Work that continues on another thread, such as a
 * detached agent run, is not observed.
 *
 * <p>Middleware that observes the calls of its generate adds the observer in the first iteration of
 * {@code wrapGenerate}, whose call every later iteration runs inside:
 *
 * <pre>{@code
 * public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next) {
 *   if (params.getIteration() > 0) {
 *     return next.apply(ctx, params);
 *   }
 *   return ModelObserver.observe(
 *       call -> spent.addAndGet(tokensOf(call.response())), () -> next.apply(ctx, params));
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ModelObserver {

  /**
   * Called after each model call ends, failed calls included.
   *
   * @param call the call that ended
   */
  void modelDone(ModelCall call);

  /**
   * Describes one model call.
   *
   * @param model the name of the model that ran
   * @param request the request the model received
   * @param response the model's response, or null when the call failed without one
   * @param error the error the call failed with, or null on success
   */
  record ModelCall(String model, ModelRequest request, ModelResponse response, Throwable error) {}

  /**
   * Runs {@code body} with {@code observer} watching every model call it makes on this thread.
   * Observers nest: a call made inside several scopes is reported to each of them.
   *
   * @param <T> the result type
   * @param observer the observer to add (must not be null)
   * @param body the code to run
   * @return the value {@code body} returns
   */
  static <T> T observe(ModelObserver observer, Supplier<T> body) {
    Deque<ModelObserver> stack = Scopes.STACK.get();
    stack.push(observer);
    try {
      return body.get();
    } finally {
      stack.pop();
      if (stack.isEmpty()) {
        Scopes.STACK.remove();
      }
    }
  }

  /**
   * Reports a finished model call to the observers in scope on this thread. Called by the code that
   * invokes models; applications do not call it.
   *
   * @param call the call that ended
   */
  static void notifyModelDone(ModelCall call) {
    Deque<ModelObserver> stack = Scopes.STACK.get();
    if (stack.isEmpty()) {
      Scopes.STACK.remove();
      return;
    }
    List<ModelObserver> observers = new ArrayList<>(stack);
    for (ModelObserver o : observers) {
      try {
        o.modelDone(call);
      } catch (RuntimeException e) {
        Scopes.LOGGER.warn("Model observer failed: {}", e.getMessage());
      }
    }
  }

  /** Holds the per-thread observer scopes. */
  final class Scopes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelObserver.class);
    private static final ThreadLocal<Deque<ModelObserver>> STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    private Scopes() {}
  }
}
