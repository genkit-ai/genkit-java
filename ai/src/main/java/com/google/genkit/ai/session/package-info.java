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
 * Provides session management for multi-turn agent conversations with persistence.
 *
 * <p>The session package provides a stateful layer on top of Genkit's generation capabilities,
 * enabling:
 *
 * <ul>
 *   <li>Persistent conversation history across multiple interactions
 *   <li>Custom session state management
 *   <li>Multiple named conversation threads within a session
 *   <li>Pluggable storage backends via {@link com.google.genkit.ai.session.SessionStore}
 * </ul>
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link com.google.genkit.ai.session.Session} - The main entry point for session management
 *   <li>{@link com.google.genkit.ai.session.Chat} - Manages conversations within a session thread
 *   <li>{@link com.google.genkit.ai.session.SessionStore} - Interface for session persistence
 *   <li>{@link com.google.genkit.ai.session.InMemorySessionStore} - Default in-memory
 *       implementation
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <p>Create a session with custom state:
 *
 * <pre>
 * Session session = genkit
 *     .createSession(SessionOptions.builder().initialState(new MyState("John")).build());
 *
 * Chat chat = session.chat(
 *     ChatOptions.builder().model("openai/gpt-4o").system("You are a helpful assistant.").build());
 *
 * // Multi-turn conversation (history is preserved automatically)
 * chat.send("What is the capital of France?");
 * chat.send("And what about Germany?");
 *
 * // Access session state
 * MyState state = session.getState();
 *
 * // Load an existing session
 * Session loadedSession = genkit.loadSession(sessionId, options).get();
 * </pre>
 *
 * <h2>Custom Session Stores</h2>
 *
 * <p>Implement {@link com.google.genkit.ai.session.SessionStore} to provide custom persistence
 * backends (e.g., database, Redis, file system).
 *
 * @see com.google.genkit.ai.session.Session
 * @see com.google.genkit.ai.session.Chat
 * @see com.google.genkit.ai.session.SessionStore
 */
package com.google.genkit.ai.session;
