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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.agent.internal.InProcessTransport;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionDesc;
import com.google.genkit.core.ActionRunResult;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.BidiAction;
import com.google.genkit.core.BidiActionImpl;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.InputSource;
import com.google.genkit.core.Registry;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent is a bidirectional streaming agent action that manages session state and provides typed
 * facades for snapshot retrieval and abort.
 *
 * <p>Instances are created via {@code AgentActions.defineCustomAgent}. Do not instantiate directly.
 *
 * @param <S> the type of custom session state
 */
public final class Agent<S>
    implements BidiAction<AgentInput, AgentOutput<S>, AgentStreamChunk, AgentInit<S>>, AgentApi<S> {

  private final BidiActionImpl<AgentInput, AgentOutput<S>, AgentStreamChunk, AgentInit<S>> impl;
  private final SessionStore<S> store;
  private final boolean serverManaged;

  /** Nullable: companion action for retrieving snapshots; only when store != null. */
  private final Action<?, ?, ?> snapshotAction;

  /** Nullable: companion action for aborting snapshots; only when store is SnapshotSubscriber. */
  private final Action<?, ?, ?> abortAction;

  private final ClientTransform<S> clientTransform;
  private final SessionStoreOptions opts;
  private final String name;
  private final String description;
  private final Registry registry;

  /**
   * Constructs an Agent. Called exclusively by {@code AgentActions}.
   *
   * @param impl the underlying BidiActionImpl
   * @param store the session store, or {@code null} for client-managed mode
   * @param serverManaged whether the agent is server-managed
   * @param snapshotAction companion snapshot action; nullable
   * @param abortAction companion abort action; nullable
   * @param clientTransform state transform for client-managed mode; nullable
   * @param opts store options; nullable
   * @param name the agent name
   * @param description the agent description; nullable
   * @param registry the registry the agent is defined in; used to build a default {@link
   *     ActionContext} for the no-arg {@link #chat()} convenience overloads
   */
  public Agent(
      BidiActionImpl<AgentInput, AgentOutput<S>, AgentStreamChunk, AgentInit<S>> impl,
      SessionStore<S> store,
      boolean serverManaged,
      Action<?, ?, ?> snapshotAction,
      Action<?, ?, ?> abortAction,
      ClientTransform<S> clientTransform,
      SessionStoreOptions opts,
      String name,
      String description,
      Registry registry) {
    this.impl = impl;
    this.store = store;
    this.serverManaged = serverManaged;
    this.snapshotAction = snapshotAction;
    this.abortAction = abortAction;
    this.clientTransform = clientTransform;
    this.opts = opts;
    this.name = name;
    this.description = description;
    this.registry = registry;
  }

  // ── BidiAction delegation ─────────────────────────────────────────────────────

  @Override
  public String getName() {
    return impl.getName();
  }

  @Override
  public ActionType getType() {
    return impl.getType();
  }

  @Override
  public ActionDesc getDesc() {
    return impl.getDesc();
  }

  @Override
  public Map<String, Object> getInputSchema() {
    return impl.getInputSchema();
  }

  @Override
  public Map<String, Object> getOutputSchema() {
    return impl.getOutputSchema();
  }

  @Override
  public Map<String, Object> getMetadata() {
    return impl.getMetadata();
  }

  @Override
  public AgentOutput<S> run(ActionContext ctx, AgentInput input) throws GenkitException {
    return impl.run(ctx, input);
  }

  @Override
  public AgentOutput<S> run(ActionContext ctx, AgentInput input, Consumer<AgentStreamChunk> cb)
      throws GenkitException {
    return impl.run(ctx, input, cb);
  }

  @Override
  public JsonNode runJson(ActionContext ctx, JsonNode input, Consumer<JsonNode> streamCallback)
      throws GenkitException {
    return impl.runJson(ctx, input, streamCallback);
  }

  @Override
  public ActionRunResult<JsonNode> runJsonWithTelemetry(
      ActionContext ctx, JsonNode input, Consumer<JsonNode> streamCallback) throws GenkitException {
    return impl.runJsonWithTelemetry(ctx, input, streamCallback);
  }

  @Override
  public AgentOutput<S> runBidi(
      ActionContext ctx,
      AgentInit<S> init,
      InputSource<AgentInput> inputs,
      Consumer<AgentStreamChunk> streamCallback)
      throws GenkitException {
    return impl.runBidi(ctx, init, inputs, streamCallback);
  }

  @Override
  public JsonNode runBidiJson(
      ActionContext ctx,
      JsonNode init,
      InputSource<JsonNode> inputs,
      Consumer<JsonNode> streamCallback)
      throws GenkitException {
    return impl.runBidiJson(ctx, init, inputs, streamCallback);
  }

  @Override
  public ActionRunResult<JsonNode> runBidiJsonWithTelemetry(
      ActionContext ctx,
      JsonNode init,
      InputSource<JsonNode> inputs,
      Consumer<JsonNode> streamCallback)
      throws GenkitException {
    return impl.runBidiJsonWithTelemetry(ctx, init, inputs, streamCallback);
  }

  @Override
  public void register(Registry registry) {
    impl.register(registry);
    if (snapshotAction != null) {
      snapshotAction.register(registry);
    }
    if (abortAction != null) {
      abortAction.register(registry);
    }
  }

  // ── AgentApi typed facades ────────────────────────────────────────────────────

  @Override
  public SessionSnapshot<S> getSnapshotData(GetSnapshotRequest req) {
    if (store == null) {
      return null;
    }
    GetSnapshotOptions.Builder optsBuilder = GetSnapshotOptions.builder();
    if (req.getSnapshotId() != null) {
      optsBuilder.snapshotId(req.getSnapshotId());
    }
    if (req.getSessionId() != null) {
      optsBuilder.sessionId(req.getSessionId());
    }
    return store.getSnapshot(optsBuilder.build());
  }

  @Override
  public SnapshotStatus abort(String snapshotId) {
    if (!(store instanceof SnapshotSubscriber)) {
      return null;
    }
    final SnapshotStatus[] resultStatus = {null};
    store.saveSnapshot(
        snapshotId,
        existing -> {
          if (existing == null) {
            resultStatus[0] = null;
            return null;
          }
          if (existing.getStatus() != SnapshotStatus.PENDING) {
            resultStatus[0] = existing.getStatus();
            return existing;
          }
          existing.setStatus(SnapshotStatus.ABORTED);
          resultStatus[0] = SnapshotStatus.ABORTED;
          return existing;
        },
        opts);
    // In addition to the store-level status mutation above (the durable record of the abort,
    // observable by anyone polling getSnapshot), also flip the live in-memory abort signal for a
    // still-running DETACHED turn, if one is currently registered under this snapshot id. This is
    // the only case where a turn's snapshot id is knowable while the turn is still running (see
    // DetachController); a foreground turn has no resolvable id until after it returns, so there is
    // no reachable window to signal it here.
    com.google.genkit.ai.agent.internal.PendingAbortRegistry.signal(snapshotId);
    return resultStatus[0];
  }

  @Override
  public AgentRef ref() {
    return new AgentRef(name, description);
  }

  // ── Chat client ───────────────────────────────────────────────────────────────

  @Override
  public AgentChat<S> chat() {
    return chat(new ActionContext(registry), null);
  }

  @Override
  public AgentChat<S> chat(AgentInit<S> init) {
    return chat(new ActionContext(registry), init);
  }

  @Override
  public AgentChat<S> loadChat(GetSnapshotRequest lookup) {
    return loadChat(new ActionContext(registry), lookup);
  }

  @Override
  public AgentChat<S> chat(ActionContext ctx) {
    return chat(ctx, null);
  }

  @Override
  public AgentChat<S> chat(ActionContext ctx, AgentInit<S> init) {
    return new AgentChat<>(new InProcessTransport<>(this, ctx), init);
  }

  @Override
  public AgentChat<S> loadChat(ActionContext ctx, GetSnapshotRequest lookup) {
    InProcessTransport<S> transport = new InProcessTransport<>(this, ctx);
    AgentChat<S> chat = new AgentChat<>(transport, null);
    SessionSnapshot<S> snap = getSnapshotData(lookup);
    chat.loadSnapshot(snap);
    return chat;
  }

  // ── Accessor methods ──────────────────────────────────────────────────────────

  /**
   * Returns whether this agent uses server-managed session state.
   *
   * @return true if server-managed, false if client-managed
   */
  public boolean serverManaged() {
    return serverManaged;
  }

  /**
   * Returns the session store, or {@code null} for client-managed agents.
   *
   * @return the session store, or null
   */
  public SessionStore<S> store() {
    return store;
  }

  /**
   * Returns the companion snapshot action, or {@code null} if not applicable.
   *
   * @return the snapshot action, or null
   */
  public Action<?, ?, ?> getSnapshotDataAction() {
    return snapshotAction;
  }

  /**
   * Returns the companion abort action, or {@code null} if not applicable.
   *
   * @return the abort action, or null
   */
  public Action<?, ?, ?> abortAgentAction() {
    return abortAction;
  }
}
