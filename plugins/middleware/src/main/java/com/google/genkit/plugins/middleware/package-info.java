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

/**
 * Middleware plugin for Genkit providing higher-level generation building blocks.
 *
 * <p>This module provides:
 *
 * <ul>
 *   <li>{@link com.google.genkit.plugins.middleware.GenerationMiddlewarePlugin} &mdash; a {@code
 *       MiddlewarePlugin} that ships ready-to-use, parameterized Generation Middleware V2 (the Java
 *       equivalent of the JS {@code @genkit-ai/middleware} package): {@link
 *       com.google.genkit.plugins.middleware.RetryMiddleware retry}, {@link
 *       com.google.genkit.plugins.middleware.FallbackMiddleware fallback}, and {@link
 *       com.google.genkit.plugins.middleware.SimulateSystemPromptMiddleware simulateSystemPrompt}.
 *       Each is registered into the {@code "middleware"} bucket and appears in the Genkit Dev UI
 *       Middleware panel with a parameters form.
 *   <li>{@link com.google.genkit.plugins.middleware.Agents} &mdash; sub-agent delegation, where
 *       each configured sub-agent is exposed to the model as a {@code delegate_to_<name>} tool that
 *       runs the sub-agent for a single turn and returns its text (plus optional artifacts).
 *   <li>{@link com.google.genkit.plugins.middleware.Artifacts} &mdash; {@code read_artifact} and
 *       {@code write_artifact} tools operating on the active agent session's artifact store.
 * </ul>
 *
 * <p>{@code Agents} and {@code Artifacts} are implemented as <em>tool factories</em>: they return
 * {@code List<Tool<?, ?>>} (and, for {@code agents()}, a system-prompt fragment) that callers wire
 * into an agent via {@code AgentConfig.tools(...)} and {@code AgentConfig.system(...)}.
 *
 * @see com.google.genkit.plugins.middleware.GenerationMiddlewarePlugin
 * @see com.google.genkit.plugins.middleware.Agents
 * @see com.google.genkit.plugins.middleware.Artifacts
 */
package com.google.genkit.plugins.middleware;
