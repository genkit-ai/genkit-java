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

package com.google.genkit.ai.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentOutput;
import com.google.genkit.ai.agent.AgentStreamChunk;
import com.google.genkit.ai.agent.AgentTransport;
import com.google.genkit.ai.agent.GetSnapshotRequest;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.util.function.Consumer;

/**
 * In-process {@link AgentTransport} that drives a locally-defined {@link Agent} via its bidi
 * action.
 *
 * <p>Each {@link #runTurn} call feeds exactly one input (offer + end) into a fresh {@link
 * BufferedInputSource} and invokes {@code agent.runBidiJson}, deserializing the returned node into
 * an {@link AgentOutput}. Snapshot retrieval and abort delegate to the agent's typed facades.
 *
 * @param <S> the type of custom session state
 */
public final class InProcessTransport<S> implements AgentTransport<S> {

  private final Agent<S> agent;
  private final ActionContext ctx;

  /**
   * Constructs an in-process transport.
   *
   * @param agent the agent to drive (must not be null)
   * @param ctx the action context used for each turn invocation (must not be null)
   */
  public InProcessTransport(Agent<S> agent, ActionContext ctx) {
    if (agent == null) {
      throw new IllegalArgumentException("agent must not be null");
    }
    if (ctx == null) {
      throw new IllegalArgumentException("ctx must not be null");
    }
    this.agent = agent;
    this.ctx = ctx;
  }

  @Override
  @SuppressWarnings("unchecked")
  public AgentOutput<S> runTurn(
      AgentInput input, AgentInit<S> init, Consumer<AgentStreamChunk> onChunk) {
    BufferedInputSource<JsonNode> inputs = new BufferedInputSource<>();
    inputs.offer(JsonUtils.toJsonNode(input != null ? input : new AgentInput()));
    inputs.end();

    JsonNode initNode = JsonUtils.toJsonNode(init != null ? init : new AgentInit<S>());

    Consumer<JsonNode> sink =
        chunkJson -> {
          if (onChunk == null) {
            return;
          }
          try {
            onChunk.accept(
                JsonUtils.getObjectMapper().treeToValue(chunkJson, AgentStreamChunk.class));
          } catch (Exception e) {
            throw new GenkitException("Failed to deserialize agent stream chunk", e);
          }
        };

    JsonNode outNode;
    try {
      outNode = agent.runBidiJson(ctx, initNode, inputs, sink);
    } catch (GenkitException e) {
      throw e;
    } catch (Exception e) {
      throw new GenkitException("Agent turn failed", e);
    }

    try {
      return (AgentOutput<S>) JsonUtils.getObjectMapper().treeToValue(outNode, AgentOutput.class);
    } catch (Exception e) {
      throw new GenkitException("Failed to deserialize agent output", e);
    }
  }

  @Override
  public SessionSnapshot<S> getSnapshot(GetSnapshotRequest req) {
    return agent.getSnapshotData(req);
  }

  @Override
  public SnapshotStatus abort(String snapshotId) {
    return agent.abort(snapshotId);
  }

  @Override
  public boolean serverManaged() {
    return agent.serverManaged();
  }
}
