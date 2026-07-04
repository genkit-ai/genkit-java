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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.core.tracing.SpanMetadata;
import com.google.genkit.core.tracing.Tracer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concrete implementation of {@link BidiAction} built from a name, type parameters, optional
 * metadata, and a {@link BidiAction.BidiHandler}.
 *
 * <p>Key invariants:
 *
 * <ul>
 *   <li>{@link #getType()} always returns {@link ActionType#AGENT}.
 *   <li>{@link #getMetadata()} always contains {@code bidi=true}; the caller's metadata map is
 *       never mutated.
 *   <li>{@link #getDesc()} returns an {@link ActionDesc} with key {@code /agent/<name>}.
 *   <li>The inherited unary {@link Action#run} methods adapt to bidi by wrapping the single input
 *       in a {@link BufferedInputSource}.
 * </ul>
 *
 * @param <I> per-turn input type
 * @param <O> final output type
 * @param <S> stream chunk type
 * @param <Init> session-init type
 */
public final class BidiActionImpl<I, O, S, Init> implements BidiAction<I, O, S, Init> {

  private static final Logger logger = LoggerFactory.getLogger(BidiActionImpl.class);
  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  private final ActionDesc desc;
  private final BidiAction.BidiHandler<I, O, S, Init> handler;
  private final Class<I> inputClass;
  private final Class<O> outputClass;
  private final Class<S> streamClass;
  private final Class<Init> initClass;
  private final Map<String, Object> metadata; // the merged metadata (includes bidi=true)

  // -------------------------------------------------------------------------
  // Private constructor – use Builder
  // -------------------------------------------------------------------------

  private BidiActionImpl(
      String name,
      Class<I> inputClass,
      Class<O> outputClass,
      Class<S> streamClass,
      Class<Init> initClass,
      Map<String, Object> inputSchema,
      Map<String, Object> outputSchema,
      Map<String, Object> callerMetadata,
      BidiAction.BidiHandler<I, O, S, Init> handler) {

    this.inputClass = inputClass;
    this.outputClass = outputClass;
    this.streamClass = streamClass;
    this.initClass = initClass;
    this.handler = handler;

    // Merge caller metadata + bidi=true without mutating the caller's map
    Map<String, Object> merged = new HashMap<>();
    if (callerMetadata != null) {
      merged.putAll(callerMetadata);
    }
    merged.put("bidi", Boolean.TRUE);
    this.metadata = merged;

    // Extract description from metadata if present (mirrors ActionDef convention)
    String description = null;
    if (merged.get("description") instanceof String) {
      description = (String) merged.get("description");
    }

    // Generate schemas when not explicitly provided
    Map<String, Object> actualInputSchema = inputSchema;
    if (actualInputSchema == null && inputClass != null && inputClass != Void.class) {
      actualInputSchema = SchemaUtils.inferSchema(inputClass);
    }
    Map<String, Object> actualOutputSchema = outputSchema;
    if (actualOutputSchema == null && outputClass != null && outputClass != Void.class) {
      actualOutputSchema = SchemaUtils.inferSchema(outputClass);
    }

    this.desc =
        ActionDesc.builder()
            .type(ActionType.AGENT)
            .name(name)
            .description(description)
            .inputSchema(actualInputSchema)
            .outputSchema(actualOutputSchema)
            .metadata(merged)
            .build();
  }

  // -------------------------------------------------------------------------
  // Action interface
  // -------------------------------------------------------------------------

  @Override
  public String getName() {
    return desc.getName();
  }

  @Override
  public ActionType getType() {
    return ActionType.AGENT;
  }

  @Override
  public ActionDesc getDesc() {
    return desc;
  }

  @Override
  public Map<String, Object> getInputSchema() {
    return desc.getInputSchema();
  }

  @Override
  public Map<String, Object> getOutputSchema() {
    return desc.getOutputSchema();
  }

  @Override
  public Map<String, Object> getMetadata() {
    return metadata;
  }

  // -------------------------------------------------------------------------
  // Unary adaptation
  // -------------------------------------------------------------------------

  /**
   * Unary adaptation: wraps {@code input} in a {@link BufferedInputSource} (with {@code null} init)
   * and delegates to the bidi handler.
   */
  @Override
  public O run(ActionContext ctx, I input) throws GenkitException {
    return run(ctx, input, null);
  }

  /**
   * Unary adaptation with streaming: wraps {@code input} in a {@link BufferedInputSource} (with
   * {@code null} init) and delegates to the bidi handler.
   */
  @Override
  public O run(ActionContext ctx, I input, Consumer<S> streamCallback) throws GenkitException {
    logger.debug("BidiActionImpl.run (unary): name={}, input={}", getName(), input);

    SpanMetadata spanMetadata =
        SpanMetadata.builder()
            .name(desc.getName())
            .type(ActionType.AGENT.getValue())
            .subtype(ActionType.AGENT.getValue())
            .build();

    return Tracer.runInNewSpan(
        ctx,
        spanMetadata,
        input,
        (spanCtx, in) -> {
          BufferedInputSource<I> source = new BufferedInputSource<>();
          if (in != null) {
            source.offer(in);
          }
          source.end();
          try {
            return handler.handle(ctx.withSpanContext(spanCtx), null, source, streamCallback);
          } catch (GenkitException ge) {
            throw ge;
          } catch (Exception e) {
            throw new GenkitException("BidiAction execution failed: " + e.getMessage(), e);
          }
        });
  }

  @Override
  public JsonNode runJson(ActionContext ctx, JsonNode input, Consumer<JsonNode> streamCallback)
      throws GenkitException {
    try {
      I typedInput = null;
      if (inputClass != null && inputClass != Void.class && input != null) {
        typedInput = MAPPER.treeToValue(input, inputClass);
      }

      Consumer<S> typedCallback = buildTypedCallback(streamCallback);
      O result = run(ctx, typedInput, typedCallback);
      return result != null ? MAPPER.valueToTree(result) : null;
    } catch (GenkitException ge) {
      throw ge;
    } catch (Exception e) {
      throw new GenkitException("JSON BidiAction execution failed: " + e.getMessage(), e);
    }
  }

  @Override
  public ActionRunResult<JsonNode> runJsonWithTelemetry(
      ActionContext ctx, JsonNode input, Consumer<JsonNode> streamCallback) throws GenkitException {

    final String[] capturedTraceInfo = new String[2]; // [traceId, spanId]

    SpanMetadata spanMetadata =
        SpanMetadata.builder()
            .name(desc.getName())
            .type(ActionType.AGENT.getValue())
            .subtype(ActionType.AGENT.getValue())
            .build();

    try {
      I typedInput = null;
      if (inputClass != null && inputClass != Void.class && input != null) {
        typedInput = MAPPER.treeToValue(input, inputClass);
      }
      final I finalInput = typedInput;
      final Consumer<S> typedCallback = buildTypedCallback(streamCallback);

      O result =
          Tracer.runInNewSpan(
              ctx,
              spanMetadata,
              finalInput,
              (spanCtx, in) -> {
                capturedTraceInfo[0] = spanCtx.getTraceId();
                capturedTraceInfo[1] = spanCtx.getSpanId();

                BufferedInputSource<I> source = new BufferedInputSource<>();
                if (in != null) {
                  source.offer(in);
                }
                source.end();
                try {
                  return handler.handle(ctx.withSpanContext(spanCtx), null, source, typedCallback);
                } catch (GenkitException ge) {
                  throw ge;
                } catch (Exception e) {
                  throw new GenkitException("BidiAction execution failed: " + e.getMessage(), e);
                }
              });

      JsonNode jsonResult = result != null ? MAPPER.valueToTree(result) : null;
      return new ActionRunResult<>(jsonResult, capturedTraceInfo[0], capturedTraceInfo[1]);
    } catch (GenkitException ge) {
      throw ge;
    } catch (Exception e) {
      throw new GenkitException("JSON BidiAction execution failed: " + e.getMessage(), e);
    }
  }

  // -------------------------------------------------------------------------
  // BidiAction interface
  // -------------------------------------------------------------------------

  @Override
  public O runBidi(ActionContext ctx, Init init, InputSource<I> inputs, Consumer<S> streamCallback)
      throws GenkitException {
    logger.debug("BidiActionImpl.runBidi: name={}", getName());
    try {
      return handler.handle(ctx, init, inputs, streamCallback);
    } catch (GenkitException ge) {
      throw ge;
    } catch (Exception e) {
      throw new GenkitException("BidiAction execution failed: " + e.getMessage(), e);
    }
  }

  @Override
  public JsonNode runBidiJson(
      ActionContext ctx,
      JsonNode init,
      InputSource<JsonNode> inputs,
      Consumer<JsonNode> streamCallback)
      throws GenkitException {
    try {
      Init typedInit = deserializeInit(init);
      InputSource<I> typedInputs = adaptInputs(inputs);
      Consumer<S> typedCallback = buildTypedCallback(streamCallback);

      // Invoke handler
      O result = handler.handle(ctx, typedInit, typedInputs, typedCallback);
      return result != null ? MAPPER.valueToTree(result) : null;
    } catch (GenkitException ge) {
      throw ge;
    } catch (Exception e) {
      throw new GenkitException("JSON BidiAction execution failed: " + e.getMessage(), e);
    }
  }

  @Override
  public ActionRunResult<JsonNode> runBidiJsonWithTelemetry(
      ActionContext ctx,
      JsonNode init,
      InputSource<JsonNode> inputs,
      Consumer<JsonNode> streamCallback)
      throws GenkitException {

    final String[] capturedTraceInfo = new String[2]; // [traceId, spanId]

    SpanMetadata spanMetadata =
        SpanMetadata.builder()
            .name(desc.getName())
            .type(ActionType.AGENT.getValue())
            .subtype(ActionType.AGENT.getValue())
            .build();

    try {
      // Deserialize the real init and adapt the full input stream up front so the handler
      // receives the client-managed session state, not a null init / single-input adaptation.
      final Init typedInit = deserializeInit(init);
      final InputSource<I> typedInputs = adaptInputs(inputs);
      final Consumer<S> typedCallback = buildTypedCallback(streamCallback);

      O result =
          Tracer.runInNewSpan(
              ctx,
              spanMetadata,
              null,
              (spanCtx, in) -> {
                capturedTraceInfo[0] = spanCtx.getTraceId();
                capturedTraceInfo[1] = spanCtx.getSpanId();
                try {
                  return handler.handle(
                      ctx.withSpanContext(spanCtx), typedInit, typedInputs, typedCallback);
                } catch (GenkitException ge) {
                  throw ge;
                } catch (Exception e) {
                  throw new GenkitException("BidiAction execution failed: " + e.getMessage(), e);
                }
              });

      JsonNode jsonResult = result != null ? MAPPER.valueToTree(result) : null;
      return new ActionRunResult<>(jsonResult, capturedTraceInfo[0], capturedTraceInfo[1]);
    } catch (GenkitException ge) {
      throw ge;
    } catch (Exception e) {
      throw new GenkitException("JSON BidiAction execution failed: " + e.getMessage(), e);
    }
  }

  // -------------------------------------------------------------------------
  // Registerable
  // -------------------------------------------------------------------------

  @Override
  public void register(Registry registry) {
    registry.registerAction(desc.getKey(), this);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Deserializes a JSON {@code init} value to the typed {@code Init}, or returns null. */
  private Init deserializeInit(JsonNode init) throws Exception {
    if (initClass != null && initClass != Void.class && init != null) {
      return MAPPER.treeToValue(init, initClass);
    }
    return null;
  }

  /** Adapts an {@code InputSource<JsonNode>} to a typed {@code InputSource<I>}. */
  private InputSource<I> adaptInputs(InputSource<JsonNode> inputs) {
    final InputSource<JsonNode> jsonInputs = inputs;
    return new InputSource<I>() {
      @Override
      public Optional<I> next() throws InterruptedException {
        Optional<JsonNode> jsonNext = jsonInputs.next();
        if (!jsonNext.isPresent()) {
          return Optional.empty();
        }
        try {
          I typed = MAPPER.treeToValue(jsonNext.get(), inputClass);
          return Optional.of(typed);
        } catch (Exception e) {
          throw new RuntimeException("Failed to deserialize input JsonNode", e);
        }
      }

      @Override
      public void close() {
        jsonInputs.close();
      }
    };
  }

  /** Adapts a {@code Consumer<JsonNode>} to a typed {@code Consumer<S>}, or returns null. */
  private Consumer<S> buildTypedCallback(Consumer<JsonNode> streamCallback) {
    if (streamCallback == null) {
      return null;
    }
    return chunk -> {
      try {
        JsonNode jsonChunk = MAPPER.valueToTree(chunk);
        streamCallback.accept(jsonChunk);
      } catch (Exception e) {
        throw new RuntimeException("Failed to serialize stream chunk", e);
      }
    };
  }

  // -------------------------------------------------------------------------
  // Builder
  // -------------------------------------------------------------------------

  /**
   * Creates a new builder for {@code BidiActionImpl}.
   *
   * @param <I> per-turn input type
   * @param <O> final output type
   * @param <S> stream chunk type
   * @param <Init> session-init type
   * @return a new builder
   */
  public static <I, O, S, Init> Builder<I, O, S, Init> builder() {
    return new Builder<>();
  }

  /** Builder for {@link BidiActionImpl}. */
  public static final class Builder<I, O, S, Init> {

    private String name;
    private Class<I> inputClass;
    private Class<O> outputClass;
    private Class<S> streamClass;
    private Class<Init> initClass;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;
    private Map<String, Object> metadata;
    private BidiAction.BidiHandler<I, O, S, Init> handler;

    private Builder() {}

    public Builder<I, O, S, Init> name(String name) {
      this.name = name;
      return this;
    }

    public Builder<I, O, S, Init> inputClass(Class<I> inputClass) {
      this.inputClass = inputClass;
      return this;
    }

    public Builder<I, O, S, Init> outputClass(Class<O> outputClass) {
      this.outputClass = outputClass;
      return this;
    }

    public Builder<I, O, S, Init> streamClass(Class<S> streamClass) {
      this.streamClass = streamClass;
      return this;
    }

    public Builder<I, O, S, Init> initClass(Class<Init> initClass) {
      this.initClass = initClass;
      return this;
    }

    public Builder<I, O, S, Init> inputSchema(Map<String, Object> inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    public Builder<I, O, S, Init> outputSchema(Map<String, Object> outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    public Builder<I, O, S, Init> metadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    public Builder<I, O, S, Init> handler(BidiAction.BidiHandler<I, O, S, Init> handler) {
      this.handler = handler;
      return this;
    }

    /** Builds the {@link BidiActionImpl}. */
    public BidiActionImpl<I, O, S, Init> build() {
      if (name == null || name.isEmpty()) {
        throw new IllegalStateException("name is required");
      }
      if (handler == null) {
        throw new IllegalStateException("handler is required");
      }
      return new BidiActionImpl<>(
          name,
          inputClass,
          outputClass,
          streamClass,
          initClass,
          inputSchema,
          outputSchema,
          metadata,
          handler);
    }
  }
}
