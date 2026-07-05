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

package com.google.genkit;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelInfo;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.Flow;
import com.google.genkit.core.tracing.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Regression test for #185: a generate call made from within a flow must be wrapped in its own
 * "generate" span, producing a flow -&gt; generate -&gt; model hierarchy (previously it was flow
 * -&gt; model).
 */
class GenerateSpanTest {

  /** Minimal model that returns a canned reply in a single turn (no tool calls). */
  private static Model fakeModel() {
    return new Model() {
      @Override
      public String getName() {
        return "test/fake";
      }

      @Override
      public ModelInfo getInfo() {
        return new ModelInfo();
      }

      @Override
      public ModelResponse run(ActionContext ctx, ModelRequest request) {
        return run(ctx, request, null);
      }

      @Override
      public ModelResponse run(
          ActionContext ctx, ModelRequest request, Consumer<ModelResponseChunk> streamCallback) {
        Candidate candidate = new Candidate(Message.model("hello"), FinishReason.STOP);
        ModelResponse response = new ModelResponse(List.of(candidate));
        response.setFinishReason(FinishReason.STOP);
        response.setRequest(request);
        return response;
      }
    };
  }

  @Test
  void generateWithinFlowNestsModelUnderGenerateSpan() {
    List<ReadableSpan> captured = Collections.synchronizedList(new ArrayList<>());
    SpanProcessor collector =
        new SpanProcessor() {
          @Override
          public void onStart(Context parentContext, ReadWriteSpan span) {}

          @Override
          public boolean isStartRequired() {
            return false;
          }

          @Override
          public void onEnd(ReadableSpan span) {
            captured.add(span);
          }

          @Override
          public boolean isEndRequired() {
            return true;
          }
        };
    Tracer.registerSpanProcessor(collector);

    Genkit genkit = Genkit.builder().build();
    genkit.registerModel(fakeModel());

    Flow<String, String, Void> flow =
        genkit.defineFlow(
            "generateSpanFlow",
            String.class,
            String.class,
            (ctx, input) -> {
              ModelResponse resp =
                  genkit.generate(
                      GenerateOptions.builder().model("test/fake").prompt(input).build());
              return resp.getText();
            });

    ActionContext ctx = ActionContext.builder().registry(genkit.getRegistry()).build();
    flow.run(ctx, "hi");

    // Isolate this run's spans by the flow span's trace id.
    ReadableSpan flowSpan =
        captured.stream()
            .filter(s -> "generateSpanFlow".equals(s.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("flow span not found"));
    String traceId = flowSpan.getSpanContext().getTraceId();

    Map<String, ReadableSpan> byId = new java.util.HashMap<>();
    for (ReadableSpan s : captured) {
      if (traceId.equals(s.getSpanContext().getTraceId())) {
        byId.put(s.getSpanContext().getSpanId(), s);
      }
    }

    ReadableSpan generateSpan =
        byId.values().stream()
            .filter(s -> "generate".equals(s.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("generate span not found — still flow -> model"));
    ReadableSpan modelSpan =
        byId.values().stream()
            .filter(s -> "test/fake".equals(s.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("model span not found"));

    // generate is a child of the flow span.
    assertEquals(
        flowSpan.getSpanContext().getSpanId(),
        generateSpan.getParentSpanContext().getSpanId(),
        "generate span should be a child of the flow span");

    // model is a child of the generate span (not directly under the flow).
    assertEquals(
        generateSpan.getSpanContext().getSpanId(),
        modelSpan.getParentSpanContext().getSpanId(),
        "model span should be a child of the generate span");
  }
}
