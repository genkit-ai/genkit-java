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

package com.google.genkit.client;

import com.google.genkit.ai.agent.AgentChat;

/**
 * Factory for creating a remote agent chat client.
 *
 * <p>Use {@link #chat(RemoteAgentOptions)} to connect to an agent served by the Jetty plugin (or
 * any compatible server that speaks the Genkit agent wire format):
 *
 * <pre>{@code
 * AgentChat<Map<String,Object>> chat = RemoteAgent.<Map<String,Object>>chat(
 *     RemoteAgentOptions.builder()
 *         .url("http://localhost:8080/myAgent")
 *         .build());
 * AgentResponse<Map<String,Object>> resp = chat.send("hello");
 * }</pre>
 */
public final class RemoteAgent {

  private RemoteAgent() {}

  /**
   * Creates a new {@link AgentChat} backed by an {@link HttpAgentTransport} that speaks to the
   * agent at {@code opts.url()}.
   *
   * @param <S> the type of custom session state
   * @param opts the remote agent options (URL, headers, serverManaged flag)
   * @return a fresh {@link AgentChat} ready to send turns
   */
  public static <S> AgentChat<S> chat(RemoteAgentOptions opts) {
    return AgentChat.over(new HttpAgentTransport<S>(opts), null);
  }
}
