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

import com.google.genkit.ai.Message;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentAbortRequest;
import com.google.genkit.ai.agent.AgentAbortResponse;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentFnContext;
import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentOutput;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.AgentSessionContext;
import com.google.genkit.ai.agent.AgentStreamChunk;
import com.google.genkit.ai.agent.Artifact;
import com.google.genkit.ai.agent.ClientTransform;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.GetSnapshotRequest;
import com.google.genkit.ai.agent.SessionRunner;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.ai.agent.SessionStoreOptions;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.ai.agent.SnapshotSubscriber;
import com.google.genkit.ai.agent.TurnEnd;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionDef;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.BidiAction;
import com.google.genkit.core.BidiActionImpl;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.Registry;
import com.google.genkit.core.SchemaUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Factory for defining custom agents.
 *
 * <p>The main entry point is {@link #defineCustomAgent(Registry, CustomAgentConfig, AgentFn)}.
 */
public final class AgentActions {

  private AgentActions() {}

  /**
   * Defines a custom agent, registers it (and any companion actions) with the registry, and returns
   * the agent instance.
   *
   * @param <S> the type of custom session state
   * @param registry the registry to register the agent and companion actions into
   * @param config the agent configuration
   * @param fn the agent function implementing per-turn logic
   * @return the constructed and registered {@link Agent}
   */
  public static <S> Agent<S> defineCustomAgent(
      Registry registry, CustomAgentConfig<S> config, AgentFn<S> fn) {

    SessionStore<S> store = config.getStore();
    boolean serverManaged = (store != null);
    ClientTransform<S> clientTransform = config.getClientTransform();
    SessionStoreOptions opts = config.getStoreOptions();

    // Build agent sub-metadata as a plain Map so callers can cast to Map<String,Object>.
    // Keys match AgentMetadata's JSON property names so wire-format is identical.
    Map<String, Object> agentMetaMap = new HashMap<>();
    agentMetaMap.put("stateManagement", serverManaged ? "server" : "client");
    agentMetaMap.put("abortable", store instanceof SnapshotSubscriber);

    // Generate a JSON schema for the custom state type, reusing the same schema-generation
    // mechanism as Tool input/output types (com.google.genkit.core.SchemaUtils, backed by the
    // victools SchemaGenerator). Skipped for types with no useful shape to describe: null (not
    // specified), Object.class (no fields), and Map (and subtypes) — a dynamic/untyped bag of
    // properties that inferSchema can only describe as a bare "type: object" with no properties.
    Map<String, Object> stateSchema = inferStateSchema(config.getStateType());
    if (stateSchema != null) {
      agentMetaMap.put("stateSchema", stateSchema);
    }

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("agent", agentMetaMap);
    if (config.getDescription() != null) {
      metadata.put("description", config.getDescription());
    }

    // Build the bidi handler
    BidiAction.BidiHandler<AgentInput, AgentOutput<S>, AgentStreamChunk, AgentInit<S>> handler =
        (ctx, init, inputs, sink) -> {
          // Step 1: resolve session. API-misuse (wrong init for the state-management mode,
          // ownership mismatch, unresolvable snapshot) throws and propagates to the transport.
          // Other (non-misuse) pre-turn failures resolve gracefully as a FAILED output (design
          // spec §6.4) — guard against a null session before constructing the runner.
          SessionResolver.Resolution<S> resolution =
              SessionResolver.resolve(store, serverManaged, init, opts);
          if (!resolution.isOk()) {
            return AgentOutput.<S>builder()
                .finishReason(AgentFinishReason.FAILED)
                .error(resolution.error())
                .build();
          }

          // Step 2: create session runner, seeded with the id of the snapshot the session was
          // resumed from (if any) so the first post-resume turn chains its parentId to it (Gap 4).
          SessionRunner<S> runner =
              new SessionRunner<>(resolution.session(), store, opts, resolution.sourceSnapshotId());

          // Step 3: create stream emitter
          StreamEmitter<S> emitter =
              new StreamEmitter<>(sink != null ? sink : chunk -> {}, JsonUtils.getObjectMapper());
          emitter.attach(runner.session());

          // Step 4: run the turn loop
          Optional<AgentInput> next;
          while ((next = inputs.next()).isPresent()) {
            AgentInput input = next.get();

            // Detach handling (server-managed only). A client-managed agent has no store to write a
            // pending snapshot to, so detach is not applicable there — fall through and process the
            // turn normally (graceful no-op for the detach flag; documented in DetachController).
            if (input != null && input.getDetach() && serverManaged) {
              Consumer<AgentStreamChunk> detachSink = sink != null ? sink : chunk -> {};
              AtomicBoolean detachAbortSignal = new AtomicBoolean(false);
              AgentFnContext detachCtx =
                  new AgentFnContext(detachSink, detachAbortSignal, ctx, input.getResume());
              final AgentInput detachInput = input;

              // Run the turn in the background with streaming suppressed; finalize the pending
              // snapshot on completion. The handler returns DETACHED immediately below.
              String pendingId =
                  DetachController.detach(
                      runner,
                      store,
                      opts,
                      emitter,
                      detachAbortSignal,
                      () -> {
                        if (detachInput.getMessage() != null) {
                          runner.addMessages(detachInput.getMessage());
                        }
                        AgentResult res =
                            AgentSessionContext.call(
                                runner.session(), () -> callFn(fn, runner, detachCtx));
                        if (res != null) {
                          if (res.getMessage() != null) {
                            runner.addMessages(res.getMessage());
                          }
                          if (res.getArtifacts() != null) {
                            runner.addArtifacts(res.getArtifacts().toArray(new Artifact[0]));
                          }
                        }
                        return res != null && res.getFinishReason() != null
                            ? res.getFinishReason()
                            : AgentFinishReason.STOP;
                      });

              // Return immediately: detached run does not wait for or stream the background work.
              return AgentOutput.<S>builder()
                  .sessionId(runner.sessionId())
                  .snapshotId(pendingId)
                  .finishReason(AgentFinishReason.DETACHED)
                  .build();
            }

            emitter.beginTurn();

            Consumer<AgentStreamChunk> chunkSink = sink != null ? sink : chunk -> {};
            AgentFnContext fnCtx =
                new AgentFnContext(
                    chunkSink,
                    new AtomicBoolean(false),
                    ctx,
                    input != null ? input.getResume() : null);

            runner.runTurn(
                input,
                (in, turnCtx) -> {
                  AgentResult res =
                      AgentSessionContext.call(runner.session(), () -> callFn(fn, runner, fnCtx));
                  if (res != null) {
                    if (res.getMessage() != null) {
                      runner.addMessages(res.getMessage());
                    }
                    if (res.getArtifacts() != null) {
                      runner.addArtifacts(res.getArtifacts().toArray(new Artifact[0]));
                    }
                  }
                  return res != null && res.getFinishReason() != null
                      ? res.getFinishReason()
                      : AgentFinishReason.STOP;
                });

            // Emit TurnEnd chunk
            if (sink != null) {
              sink.accept(
                  AgentStreamChunk.builder()
                      .turnEnd(
                          TurnEnd.builder()
                              .snapshotId(runner.lastSnapshotId())
                              .finishReason(runner.lastTurnFinishReason())
                              .build())
                      .build());
            }
          }

          // Step 5: build AgentOutput
          AgentOutput.Builder<S> outBuilder =
              AgentOutput.<S>builder()
                  .sessionId(runner.sessionId())
                  .finishReason(runner.lastTurnFinishReason())
                  .error(runner.lastTurnError());

          List<Message> msgs = runner.getMessages();
          if (!msgs.isEmpty()) {
            outBuilder.message(msgs.get(msgs.size() - 1));
          }

          List<Artifact> artifacts = runner.getArtifacts();
          if (!artifacts.isEmpty()) {
            outBuilder.artifacts(artifacts);
          }

          if (serverManaged) {
            String lastSnapshotId = runner.lastSnapshotId();
            if (lastSnapshotId != null && !lastSnapshotId.isEmpty()) {
              outBuilder.snapshotId(lastSnapshotId);
            }
          } else {
            SessionState<S> state = runner.getState();
            if (clientTransform != null) {
              state = clientTransform.transformState(state);
            }
            outBuilder.state(state);
          }

          return outBuilder.build();
        };

    // Build the BidiActionImpl
    @SuppressWarnings("unchecked")
    BidiActionImpl<AgentInput, AgentOutput<S>, AgentStreamChunk, AgentInit<S>> impl =
        BidiActionImpl.<AgentInput, AgentOutput<S>, AgentStreamChunk, AgentInit<S>>builder()
            .name(config.getName())
            .inputClass(AgentInput.class)
            .outputClass((Class<AgentOutput<S>>) (Class<?>) AgentOutput.class)
            .streamClass(AgentStreamChunk.class)
            .initClass((Class<AgentInit<S>>) (Class<?>) AgentInit.class)
            .metadata(metadata)
            .handler(handler)
            .build();

    // Build companion actions
    Action<?, ?, ?> snapshotAction = null;
    Action<?, ?, ?> abortAction = null;

    if (store != null) {
      snapshotAction = buildSnapshotAction(config.getName(), store, opts);
    }

    if (store instanceof SnapshotSubscriber) {
      abortAction = buildAbortAction(config.getName(), store, opts);
    }

    // Construct the agent
    Agent<S> agent =
        new Agent<>(
            impl,
            store,
            serverManaged,
            snapshotAction,
            abortAction,
            clientTransform,
            opts,
            config.getName(),
            config.getDescription(),
            registry);

    // Register the agent and companion actions
    agent.register(registry);

    return agent;
  }

  // ── Private helpers ───────────────────────────────────────────────────────────

  /**
   * Generates a JSON schema map for {@code stateType}, reusing {@link SchemaUtils#inferSchema} (the
   * same mechanism {@code Tool}/{@code genkit.defineTool} use for input/output schemas).
   *
   * @param <S> the type of custom session state
   * @param stateType the configured state type; may be {@code null}
   * @return the generated schema, or {@code null} if {@code stateType} is {@code null}, {@code
   *     Object.class}, or a {@link Map} (or subtype) — types with no useful shape to describe
   */
  private static <S> Map<String, Object> inferStateSchema(Class<S> stateType) {
    if (stateType == null || stateType == Object.class || Map.class.isAssignableFrom(stateType)) {
      return null;
    }
    return SchemaUtils.inferSchema(stateType);
  }

  /**
   * Invokes {@code fn.run(runner, ctx)}, rethrowing any checked exception wrapped in an unchecked
   * {@link AgentFnExecutionException} that preserves the original exception's identity (via {@link
   * Throwable#getCause()}) and message.
   *
   * <p>{@link AgentFn#run} declares {@code throws Exception}, but this is called from inside a
   * {@link java.util.function.Supplier} (via {@link AgentSessionContext#call}), which does not
   * declare any checked exception. The turn-body lambdas that call this helper already run inside a
   * try/catch in {@link SessionRunner#runTurn} (foreground) or the detached-turn background
   * runnable (which itself catches {@code Throwable}), so unwrapping is not required there — both
   * callers only care that the original exception's message/type is observable, which {@link
   * AgentFnExecutionException#getMessage()} and {@link AgentFnExecutionException#getCause()}
   * preserve.
   *
   * @param <S> the type of custom session state
   * @param fn the agent function to invoke
   * @param runner the session runner for this turn
   * @param ctx the per-invocation context for this turn
   * @return the agent result returned by {@code fn.run}
   */
  private static <S> AgentResult callFn(
      AgentFn<S> fn, SessionRunner<S> runner, AgentFnContext ctx) {
    try {
      return fn.run(runner, ctx);
    } catch (RuntimeException e) {
      // Unchecked already — propagate as-is so callers see the original type/message unchanged.
      throw e;
    } catch (Exception e) {
      throw new AgentFnExecutionException(e);
    }
  }

  /**
   * Unchecked wrapper used solely to carry a checked exception thrown by {@link AgentFn#run}
   * through a {@link java.util.function.Supplier} boundary ({@link AgentSessionContext#call}).
   * Callers that catch this should prefer {@link #getCause()} / {@link #getMessage()} (which
   * delegates to the cause's message) over the wrapper itself.
   */
  static final class AgentFnExecutionException extends RuntimeException {
    AgentFnExecutionException(Exception cause) {
      super(cause.getMessage(), cause);
    }
  }

  private static <S> Action<?, ?, ?> buildSnapshotAction(
      String agentName, SessionStore<S> store, SessionStoreOptions opts) {
    return ActionDef.<GetSnapshotRequest, SessionSnapshot>create(
        agentName,
        ActionType.AGENT_SNAPSHOT,
        null,
        null,
        GetSnapshotRequest.class,
        SessionSnapshot.class,
        (ctx, req) -> {
          GetSnapshotOptions.Builder optsBuilder = GetSnapshotOptions.builder();
          if (req.getSnapshotId() != null) {
            optsBuilder.snapshotId(req.getSnapshotId());
          }
          if (req.getSessionId() != null) {
            optsBuilder.sessionId(req.getSessionId());
          }
          return store.getSnapshot(optsBuilder.build());
        });
  }

  private static <S> Action<?, ?, ?> buildAbortAction(
      String agentName, SessionStore<S> store, SessionStoreOptions opts) {
    return ActionDef.<AgentAbortRequest, AgentAbortResponse>create(
        agentName,
        ActionType.AGENT_ABORT,
        null,
        null,
        AgentAbortRequest.class,
        AgentAbortResponse.class,
        (ctx, req) -> {
          String snapshotId = req.getSnapshotId();
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
          // Also flip the live in-memory signal for a still-running DETACHED turn registered under
          // this snapshot id, mirroring Agent.abort(String) (see PendingAbortRegistry javadoc).
          PendingAbortRegistry.signal(snapshotId);
          return AgentAbortResponse.builder()
              .snapshotId(snapshotId)
              .status(resultStatus[0])
              .build();
        });
  }
}
