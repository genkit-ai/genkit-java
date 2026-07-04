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

package com.google.genkit.ai.middleware;

import com.google.genkit.core.Registry;
import java.util.List;

/**
 * Implemented by plugins that share reusable generation middleware.
 *
 * <p>This is how Middleware V2 is shared as plugins, mirroring the JS SDK's {@code
 * GenkitPluginV2.middleware()} and the Go SDK's {@code MiddlewarePlugin.Middlewares()}. A plugin
 * implements both {@link com.google.genkit.core.Plugin} (for its actions, if any) and this
 * interface; during {@code Genkit} initialization each returned descriptor is registered into the
 * registry's {@code "middleware"} value bucket, making it discoverable in the Genkit Dev UI and
 * resolvable by name at generate time.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public class MyMiddlewarePlugin implements Plugin, MiddlewarePlugin {
 *   public List<GenerationMiddlewareDesc> middlewares(Registry registry) {
 *     return List.of(
 *         GenerationMiddlewares.define("retry", "Retry failed model calls",
 *             RetryOptions.class, RetryMiddleware::new));
 *   }
 *   // ...Plugin methods...
 * }
 * }</pre>
 */
public interface MiddlewarePlugin {

  /**
   * Returns the generation middleware this plugin provides. Called once during {@code Genkit}
   * initialization.
   *
   * @param registry the Genkit registry, for middleware that needs to resolve dependencies (e.g. a
   *     fallback middleware that runs other models)
   * @return the middleware descriptors to register (may be empty)
   */
  List<GenerationMiddlewareDesc> middlewares(Registry registry);
}
