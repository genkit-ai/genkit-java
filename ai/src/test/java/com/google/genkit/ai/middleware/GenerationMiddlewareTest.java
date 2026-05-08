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

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for V2 GenerationMiddleware hooks: GenerateNext, ModelNext, ToolNext. */
class GenerationMiddlewareTest {

  private ActionContext ctx;

  @BeforeEach
  void setUp() {
    ctx = new ActionContext(new DefaultRegistry());
  }

  // =========================================================================
  // Helper: build a simple ModelResponse with text
  // =========================================================================

  private static ModelResponse responseWithText(String text) {
    Message msg = Message.model(text);
    Candidate candidate = new Candidate(msg);
    return ModelResponse.builder().addCandidate(candidate).build();
  }

  /** Helper: build a GenerateActionOptions with a single user message. */
  private static GenerateActionOptions actionOpts(String text) {
    GenerateActionOptions opts = new GenerateActionOptions();
    opts.setModel("test-model");
    opts.setMessages(List.of(Message.user(text)));
    return opts;
  }

  /** Helper: build a minimal GenerateActionOptions. */
  private static GenerateActionOptions actionOpts() {
    GenerateActionOptions opts = new GenerateActionOptions();
    opts.setModel("test-model");
    opts.setMessages(List.of());
    return opts;
  }

  // =========================================================================
  // GenerateNext tests
  // =========================================================================

  @Test
  void testGenerateNext_passThrough() {
    GenerateActionOptions request = actionOpts("hello");
    GenerateParams params = new GenerateParams(request, 0);
    ModelResponse expected = responseWithText("world");

    GenerateNext next = (c, p) -> expected;

    ModelResponse result = next.apply(ctx, params);
    assertSame(expected, result);
  }

  @Test
  void testGenerateNext_chainOrder() {
    List<String> order = new ArrayList<>();

    // Core function
    GenerateNext core =
        (c, p) -> {
          order.add("core");
          return responseWithText("response");
        };

    // Outer middleware wrapping core
    GenerateNext outer =
        (c, p) -> {
          order.add("outer-before");
          ModelResponse resp = core.apply(c, p);
          order.add("outer-after");
          return resp;
        };

    GenerateActionOptions request = actionOpts("test");
    outer.apply(ctx, new GenerateParams(request, 0));

    assertEquals(List.of("outer-before", "core", "outer-after"), order);
  }

  @Test
  void testGenerateNext_canModifyParams() {
    GenerateActionOptions original = actionOpts("original");
    GenerateActionOptions modified = actionOpts("modified");

    AtomicInteger iterationSeen = new AtomicInteger(-1);
    GenerateNext core =
        (c, p) -> {
          iterationSeen.set(p.getIteration());
          assertSame(modified, p.getRequest());
          return responseWithText("ok");
        };

    // Middleware that replaces the request
    GenerateNext wrapper =
        (c, p) -> {
          GenerateParams newParams = p.withRequest(modified);
          return core.apply(c, newParams);
        };

    wrapper.apply(ctx, new GenerateParams(original, 5));
    assertEquals(5, iterationSeen.get()); // iteration preserved by withRequest
  }

  @Test
  void testGenerateParams_messageIndex() {
    // 3-arg constructor sets messageIndex explicitly
    GenerateActionOptions opts = actionOpts("hello");
    GenerateParams params = new GenerateParams(opts, 2, 5);
    assertEquals(5, params.getMessageIndex());
    assertEquals(2, params.getIteration());

    // 2-arg constructor defaults messageIndex to message count
    GenerateParams defaulted = new GenerateParams(opts, 0);
    assertEquals(1, defaulted.getMessageIndex()); // 1 message ("hello")

    // withMessageIndex creates a copy with new index
    GenerateParams modified = params.withMessageIndex(10);
    assertEquals(10, modified.getMessageIndex());
    assertEquals(2, modified.getIteration()); // preserved
    assertSame(opts, modified.getRequest()); // preserved

    // withRequest preserves messageIndex
    GenerateActionOptions newOpts = actionOpts("world");
    GenerateParams swapped = params.withRequest(newOpts);
    assertEquals(5, swapped.getMessageIndex()); // preserved
    assertSame(newOpts, swapped.getRequest());
  }

  @Test
  void testGenerateNext_exceptionPropagates() {
    GenerateNext failing =
        (c, p) -> {
          throw new GenkitException("boom");
        };

    GenerateActionOptions request = actionOpts();
    assertThrows(GenkitException.class, () -> failing.apply(ctx, new GenerateParams(request, 0)));
  }

  // =========================================================================
  // ModelNext tests
  // =========================================================================

  @Test
  void testModelNext_passThrough() {
    ModelRequest request = ModelRequest.builder().addUserMessage("hello").build();
    ModelParams params = new ModelParams(request, null);
    ModelResponse expected = responseWithText("model output");

    ModelNext next = (c, p) -> expected;

    ModelResponse result = next.apply(ctx, params);
    assertSame(expected, result);
  }

  @Test
  void testModelNext_chainOrder() {
    List<String> order = new ArrayList<>();

    ModelNext core =
        (c, p) -> {
          order.add("model");
          return responseWithText("result");
        };

    ModelNext wrapper =
        (c, p) -> {
          order.add("before-model");
          ModelResponse resp = core.apply(c, p);
          order.add("after-model");
          return resp;
        };

    ModelRequest request = ModelRequest.builder().build();
    wrapper.apply(ctx, new ModelParams(request, null));

    assertEquals(List.of("before-model", "model", "after-model"), order);
  }

  @Test
  void testModelNext_canModifyRequest() {
    ModelRequest original = ModelRequest.builder().addUserMessage("original").build();
    ModelRequest modified = ModelRequest.builder().addUserMessage("injected").build();

    ModelNext core =
        (c, p) -> {
          assertEquals(modified, p.getRequest());
          return responseWithText("ok");
        };

    ModelNext wrapper =
        (c, p) -> {
          ModelParams newParams = p.withRequest(modified);
          return core.apply(c, newParams);
        };

    wrapper.apply(ctx, new ModelParams(original, null));
  }

  @Test
  void testModelNext_preservesStreamCallback() {
    List<String> streamed = new ArrayList<>();
    ModelParams params =
        new ModelParams(ModelRequest.builder().build(), chunk -> streamed.add("chunk"));

    ModelNext next =
        (c, p) -> {
          assertNotNull(p.getStreamCallback());
          return responseWithText("ok");
        };

    next.apply(ctx, params);
    assertNotNull(params.getStreamCallback());
  }

  @Test
  void testModelNext_exceptionPropagates() {
    ModelNext failing =
        (c, p) -> {
          throw new GenkitException("model failed");
        };

    assertThrows(
        GenkitException.class,
        () -> failing.apply(ctx, new ModelParams(ModelRequest.builder().build(), null)));
  }

  // =========================================================================
  // ToolNext tests
  // =========================================================================

  @Test
  void testToolNext_passThrough() {
    ToolRequest toolReq = new ToolRequest("myTool", Map.of("key", "value"));
    Tool<String, String> tool = createTestTool("myTool");
    ToolParams params = new ToolParams(Part.toolRequest(toolReq), tool);
    Part expected = Part.toolResponse(new ToolResponse("myTool", "tool output"));

    ToolNext next = (c, p) -> expected;

    Part result = next.apply(ctx, params);
    assertSame(expected, result);
  }

  @Test
  void testToolNext_chainOrder() {
    List<String> order = new ArrayList<>();

    ToolNext core =
        (c, p) -> {
          order.add("tool-exec");
          return Part.toolResponse(new ToolResponse(p.getRequest().getName(), "result"));
        };

    ToolNext wrapper =
        (c, p) -> {
          order.add("before-tool");
          Part resp = core.apply(c, p);
          order.add("after-tool");
          return resp;
        };

    ToolRequest toolReq = new ToolRequest("test", Map.of());
    wrapper.apply(ctx, new ToolParams(Part.toolRequest(toolReq), createTestTool("test")));

    assertEquals(List.of("before-tool", "tool-exec", "after-tool"), order);
  }

  @Test
  void testToolNext_accessesToolInfo() {
    Tool<String, String> tool = createTestTool("weatherTool");
    ToolRequest toolReq = new ToolRequest("weatherTool", Map.of("city", "Paris"));

    ToolNext next =
        (c, p) -> {
          assertEquals("weatherTool", p.getRequest().getName());
          assertEquals("weatherTool", p.getTool().getName());
          return Part.toolResponse(new ToolResponse("weatherTool", "sunny"));
        };

    Part resp = next.apply(ctx, new ToolParams(Part.toolRequest(toolReq), tool));
    assertEquals("weatherTool", resp.getToolResponse().getName());
  }

  @Test
  void testToolNext_exceptionPropagates() {
    ToolNext failing =
        (c, p) -> {
          throw new GenkitException("tool failed");
        };

    ToolRequest toolReq = new ToolRequest("t", Map.of());
    assertThrows(
        GenkitException.class,
        () -> failing.apply(ctx, new ToolParams(Part.toolRequest(toolReq), createTestTool("t"))));
  }

  // =========================================================================
  // BaseGenerationMiddleware tests
  // =========================================================================

  @Test
  void testBaseMiddleware_defaultsPassThrough() {
    BaseGenerationMiddleware base =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "noop";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }
        };

    // wrapGenerate passes through
    GenerateActionOptions gOpts = actionOpts("test");
    ModelRequest req = ModelRequest.builder().addUserMessage("test").build();
    ModelResponse expected = responseWithText("pass");
    GenerateNext gNext = (c, p) -> expected;
    ModelResponse gResult = base.wrapGenerate(ctx, new GenerateParams(gOpts, 0), gNext);
    assertSame(expected, gResult);

    // wrapModel passes through
    ModelNext mNext = (c, p) -> expected;
    ModelResponse mResult = base.wrapModel(ctx, new ModelParams(req, null), mNext);
    assertSame(expected, mResult);

    // wrapTool passes through
    Part toolExpected = Part.toolResponse(new ToolResponse("t", "data"));
    ToolNext tNext = (c, p) -> toolExpected;
    Part tResult =
        base.wrapTool(
            ctx,
            new ToolParams(Part.toolRequest(new ToolRequest("t", Map.of())), createTestTool("t")),
            tNext);
    assertSame(toolExpected, tResult);

    // tools returns empty
    assertTrue(base.tools().isEmpty());
  }

  @Test
  void testCustomMiddleware_overridesSelectedHooks() {
    AtomicInteger modelCallCount = new AtomicInteger(0);

    BaseGenerationMiddleware middleware =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "model-counter";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            modelCallCount.incrementAndGet();
            return next.apply(ctx, params);
          }
        };

    ModelRequest req = ModelRequest.builder().build();
    GenerateActionOptions gOpts = actionOpts();
    ModelResponse resp = responseWithText("ok");

    // wrapModel is overridden
    middleware.wrapModel(ctx, new ModelParams(req, null), (c, p) -> resp);
    assertEquals(1, modelCallCount.get());

    // wrapGenerate still passes through (default)
    ModelResponse gResp =
        middleware.wrapGenerate(ctx, new GenerateParams(gOpts, 0), (c, p) -> resp);
    assertSame(resp, gResp);
    assertEquals(1, modelCallCount.get()); // not incremented
  }

  // =========================================================================
  // Chaining multiple middleware
  // =========================================================================

  @Test
  void testChainGenerateHooks_nestedOrder() {
    List<String> order = new ArrayList<>();

    GenerationMiddleware outer =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "outer";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapGenerate(
              ActionContext ctx, GenerateParams params, GenerateNext next) throws GenkitException {
            order.add("outer-before");
            ModelResponse resp = next.apply(ctx, params);
            order.add("outer-after");
            return resp;
          }
        };

    GenerationMiddleware inner =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "inner";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapGenerate(
              ActionContext ctx, GenerateParams params, GenerateNext next) throws GenkitException {
            order.add("inner-before");
            ModelResponse resp = next.apply(ctx, params);
            order.add("inner-after");
            return resp;
          }
        };

    // Chain: outer wraps inner wraps core
    // This mirrors the chaining in Genkit.chainGenerateHooks()
    List<GenerationMiddleware> middlewares = List.of(outer, inner);
    GenerateNext core =
        (c, p) -> {
          order.add("core");
          return responseWithText("done");
        };

    // Build chain by reverse iteration (first middleware = outermost)
    GenerateNext chain = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      GenerationMiddleware mw = middlewares.get(i);
      GenerateNext wrapped = chain;
      chain = (c, p) -> mw.wrapGenerate(c, p, wrapped);
    }

    GenerateActionOptions gOpts = actionOpts();
    chain.apply(ctx, new GenerateParams(gOpts, 0));

    assertEquals(
        List.of("outer-before", "inner-before", "core", "inner-after", "outer-after"), order);
  }

  @Test
  void testChainModelHooks_nestedOrder() {
    List<String> order = new ArrayList<>();

    GenerationMiddleware first =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "first";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            order.add("first-before");
            ModelResponse resp = next.apply(ctx, params);
            order.add("first-after");
            return resp;
          }
        };

    GenerationMiddleware second =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "second";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            order.add("second-before");
            ModelResponse resp = next.apply(ctx, params);
            order.add("second-after");
            return resp;
          }
        };

    List<GenerationMiddleware> middlewares = List.of(first, second);
    ModelNext core =
        (c, p) -> {
          order.add("model");
          return responseWithText("result");
        };

    ModelNext chain = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      GenerationMiddleware mw = middlewares.get(i);
      ModelNext wrapped = chain;
      chain = (c, p) -> mw.wrapModel(c, p, wrapped);
    }

    chain.apply(ctx, new ModelParams(ModelRequest.builder().build(), null));

    assertEquals(
        List.of("first-before", "second-before", "model", "second-after", "first-after"), order);
  }

  @Test
  void testChainToolHooks_nestedOrder() {
    List<String> order = new ArrayList<>();

    GenerationMiddleware first =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "first";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next)
              throws GenkitException {
            order.add("first-before");
            Part resp = next.apply(ctx, params);
            order.add("first-after");
            return resp;
          }
        };

    GenerationMiddleware second =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "second";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next)
              throws GenkitException {
            order.add("second-before");
            Part resp = next.apply(ctx, params);
            order.add("second-after");
            return resp;
          }
        };

    List<GenerationMiddleware> middlewares = List.of(first, second);
    ToolNext core =
        (c, p) -> {
          order.add("tool");
          return Part.toolResponse(new ToolResponse(p.getRequest().getName(), "output"));
        };

    ToolNext chain = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      GenerationMiddleware mw = middlewares.get(i);
      ToolNext wrapped = chain;
      chain = (c, p) -> mw.wrapTool(c, p, wrapped);
    }

    ToolRequest toolReq = new ToolRequest("myTool", Map.of());
    chain.apply(ctx, new ToolParams(Part.toolRequest(toolReq), createTestTool("myTool")));

    assertEquals(
        List.of("first-before", "second-before", "tool", "second-after", "first-after"), order);
  }

  // =========================================================================
  // newInstance() isolation
  // =========================================================================

  @Test
  void testNewInstance_isolatesState() {
    AtomicInteger sharedCounter = new AtomicInteger(0);

    GenerationMiddleware template =
        new BaseGenerationMiddleware() {
          private final AtomicInteger calls = new AtomicInteger(0);

          @Override
          public String name() {
            return "stateful";
          }

          @Override
          public GenerationMiddleware newInstance() {
            // Each instance gets its own counter
            return new BaseGenerationMiddleware() {
              private final AtomicInteger instanceCalls = new AtomicInteger(0);

              @Override
              public String name() {
                return "stateful";
              }

              @Override
              public GenerationMiddleware newInstance() {
                return this;
              }

              @Override
              public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
                  throws GenkitException {
                instanceCalls.incrementAndGet();
                sharedCounter.incrementAndGet();
                return next.apply(ctx, params);
              }
            };
          }
        };

    // Simulate two generate() calls creating separate instances
    GenerationMiddleware instance1 = template.newInstance();
    GenerationMiddleware instance2 = template.newInstance();

    ModelResponse resp = responseWithText("ok");
    ModelNext passThrough = (c, p) -> resp;
    ModelParams params = new ModelParams(ModelRequest.builder().build(), null);

    // Call instance1 three times
    instance1.wrapModel(ctx, params, passThrough);
    instance1.wrapModel(ctx, params, passThrough);
    instance1.wrapModel(ctx, params, passThrough);

    // Call instance2 once
    instance2.wrapModel(ctx, params, passThrough);

    // Shared counter sees all 4 calls
    assertEquals(4, sharedCounter.get());

    // But instances are independent (verified by the fact that both ran without error)
  }

  // =========================================================================
  // Streaming through middleware tests
  // =========================================================================

  @Test
  void testGenerateParams_onChunk_nullByDefault() {
    GenerateActionOptions opts = actionOpts("hello");

    // 2-arg constructor: onChunk is null
    GenerateParams params2 = new GenerateParams(opts, 0);
    assertNull(params2.getOnChunk());

    // 3-arg constructor: onChunk is null
    GenerateParams params3 = new GenerateParams(opts, 0, 0);
    assertNull(params3.getOnChunk());
  }

  @Test
  void testGenerateParams_onChunk_preserved() {
    GenerateActionOptions opts = actionOpts("hello");
    Consumer<ModelResponseChunk> callback = chunk -> {};

    // 4-arg constructor sets onChunk
    GenerateParams params = new GenerateParams(opts, 1, 3, callback);
    assertSame(callback, params.getOnChunk());
    assertEquals(1, params.getIteration());
    assertEquals(3, params.getMessageIndex());
  }

  @Test
  void testGenerateParams_withRequest_preservesOnChunk() {
    Consumer<ModelResponseChunk> callback = chunk -> {};
    GenerateActionOptions opts1 = actionOpts("first");
    GenerateActionOptions opts2 = actionOpts("second");

    GenerateParams params = new GenerateParams(opts1, 0, 0, callback);
    GenerateParams modified = params.withRequest(opts2);

    assertSame(opts2, modified.getRequest());
    assertSame(callback, modified.getOnChunk()); // preserved
  }

  @Test
  void testGenerateParams_withMessageIndex_preservesOnChunk() {
    Consumer<ModelResponseChunk> callback = chunk -> {};
    GenerateActionOptions opts = actionOpts("hello");

    GenerateParams params = new GenerateParams(opts, 0, 0, callback);
    GenerateParams modified = params.withMessageIndex(5);

    assertEquals(5, modified.getMessageIndex());
    assertSame(callback, modified.getOnChunk()); // preserved
  }

  @Test
  void testGenerateParams_withOnChunk() {
    GenerateActionOptions opts = actionOpts("hello");
    Consumer<ModelResponseChunk> cb1 = chunk -> {};
    Consumer<ModelResponseChunk> cb2 = chunk -> {};

    GenerateParams params = new GenerateParams(opts, 1, 2, cb1);
    GenerateParams modified = params.withOnChunk(cb2);

    assertSame(cb2, modified.getOnChunk());
    assertEquals(1, modified.getIteration()); // preserved
    assertEquals(2, modified.getMessageIndex()); // preserved
    assertSame(opts, modified.getRequest()); // preserved
  }

  @Test
  void testGenerateParams_onChunk_propagatedThroughChain() {
    // Verify that when streaming callback flows through wrapGenerate chain,
    // it's visible to middleware via GenerateParams.getOnChunk()
    List<String> events = new ArrayList<>();
    Consumer<ModelResponseChunk> callback = chunk -> events.add("chunk");

    GenerationMiddleware middleware =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "stream-observer";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapGenerate(
              ActionContext ctx, GenerateParams params, GenerateNext next) throws GenkitException {
            // Middleware can see the streaming callback
            if (params.getOnChunk() != null) {
              events.add("streaming");
            } else {
              events.add("non-streaming");
            }
            return next.apply(ctx, params);
          }
        };

    GenerateNext core =
        (c, p) -> {
          // Core also sees the callback
          assertSame(callback, p.getOnChunk());
          return responseWithText("ok");
        };

    GenerateNext chain = (c, p) -> middleware.wrapGenerate(c, p, core);

    GenerateActionOptions opts = actionOpts("test");
    chain.apply(ctx, new GenerateParams(opts, 0, 0, callback));

    assertEquals(List.of("streaming"), events);
  }

  @Test
  void testModelParams_streamCallback_propagatedFromGenerateParams() {
    // Simulates the flow: GenerateParams.onChunk → ModelParams.streamCallback
    // This is what happens inside rawGenerate in Genkit.java
    Consumer<ModelResponseChunk> callback = chunk -> {};

    GenerateActionOptions opts = actionOpts("test");
    GenerateParams generateParams = new GenerateParams(opts, 0, 0, callback);

    // rawGenerate creates ModelParams with the onChunk from GenerateParams
    ModelParams modelParams =
        new ModelParams(ModelRequest.builder().build(), generateParams.getOnChunk());

    assertSame(callback, modelParams.getStreamCallback());
  }

  @Test
  void testWrapModel_seesStreamCallback_whenStreaming() {
    // Verify that wrapModel middleware can see and interact with the stream callback
    List<String> events = new ArrayList<>();
    Consumer<ModelResponseChunk> originalCallback = chunk -> events.add("original:" + chunk);

    GenerationMiddleware middleware =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "stream-interceptor";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            Consumer<ModelResponseChunk> sc = params.getStreamCallback();
            if (sc != null) {
              events.add("has-callback");
            }
            return next.apply(ctx, params);
          }
        };

    ModelNext core =
        (c, p) -> {
          assertNotNull(p.getStreamCallback());
          return responseWithText("ok");
        };

    ModelNext chain = (c, p) -> middleware.wrapModel(c, p, core);
    chain.apply(ctx, new ModelParams(ModelRequest.builder().build(), originalCallback));

    assertEquals(List.of("has-callback"), events);
  }

  @Test
  void testWrapModel_noStreamCallback_whenNonStreaming() {
    // Verify non-streaming calls have null callback in ModelParams
    List<String> events = new ArrayList<>();

    GenerationMiddleware middleware =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "stream-checker";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            events.add(params.getStreamCallback() == null ? "non-streaming" : "streaming");
            return next.apply(ctx, params);
          }
        };

    ModelNext core = (c, p) -> responseWithText("ok");
    ModelNext chain = (c, p) -> middleware.wrapModel(c, p, core);

    // Call with null callback (non-streaming)
    chain.apply(ctx, new ModelParams(ModelRequest.builder().build(), null));
    assertEquals(List.of("non-streaming"), events);
  }

  @Test
  void testWrapModel_smoothStream_middlewareCanSplitChunks() {
    // Demonstrates a "smooth stream" middleware that intercepts chunks from the model
    // and splits large chunks into smaller, more uniform pieces.
    // This is the key use case: e.g. Gemini streams in huge chunks, and middleware
    // can re-chunk them into smooth, small pieces for the client.

    List<String> receivedByClient = new ArrayList<>();
    Consumer<ModelResponseChunk> clientCallback = chunk -> receivedByClient.add(chunk.getText());

    // "Smooth stream" middleware: splits any chunk with text > 5 chars into 5-char pieces
    GenerationMiddleware smoothStream =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "smooth-stream";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            Consumer<ModelResponseChunk> original = params.getStreamCallback();
            if (original == null) {
              return next.apply(ctx, params);
            }

            // Replace callback: split large chunks into 5-char pieces
            Consumer<ModelResponseChunk> smoothed =
                chunk -> {
                  String text = chunk.getText();
                  if (text != null && text.length() > 5) {
                    for (int i = 0; i < text.length(); i += 5) {
                      String piece = text.substring(i, Math.min(i + 5, text.length()));
                      original.accept(ModelResponseChunk.text(piece));
                    }
                  } else {
                    original.accept(chunk);
                  }
                };

            // Pass modified callback to model via withStreamCallback
            return next.apply(ctx, params.withStreamCallback(smoothed));
          }
        };

    // Core "model" that streams in big, irregular chunks (simulates Gemini behavior)
    ModelNext core =
        (c, p) -> {
          Consumer<ModelResponseChunk> cb = p.getStreamCallback();
          cb.accept(ModelResponseChunk.text("Hello, this is a big chunk!"));
          cb.accept(ModelResponseChunk.text("OK"));
          return responseWithText("final");
        };

    // Chain: smoothStream middleware wraps core
    ModelNext chain = (c, p) -> smoothStream.wrapModel(c, p, core);

    chain.apply(ctx, new ModelParams(ModelRequest.builder().build(), clientCallback));

    // The big chunk "Hello, this is a big chunk!" (27 chars) is split into 5-char pieces:
    // "Hello"  ", thi"  "s is "  "a big"  " chun"  "k!"
    // The small chunk "OK" (2 chars) passes through unchanged
    assertEquals(
        List.of("Hello", ", thi", "s is ", "a big", " chun", "k!", "OK"), receivedByClient);
  }

  @Test
  void testWrapModel_middlewareCanCombineChunks() {
    // Demonstrates the inverse: middleware that buffers small chunks and emits
    // them combined into larger pieces. Useful for reducing UI update overhead.

    List<String> receivedByClient = new ArrayList<>();
    Consumer<ModelResponseChunk> clientCallback = chunk -> receivedByClient.add(chunk.getText());

    // "Buffering" middleware: collects chunks and emits them in groups of 3
    GenerationMiddleware bufferingMiddleware =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "buffer-stream";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            Consumer<ModelResponseChunk> original = params.getStreamCallback();
            if (original == null) {
              return next.apply(ctx, params);
            }

            // Buffer that accumulates text and flushes every 3 chunks
            List<String> buffer = new ArrayList<>();
            Consumer<ModelResponseChunk> buffered =
                chunk -> {
                  buffer.add(chunk.getText());
                  if (buffer.size() >= 3) {
                    original.accept(ModelResponseChunk.text(String.join("", buffer)));
                    buffer.clear();
                  }
                };

            ModelResponse response = next.apply(ctx, params.withStreamCallback(buffered));

            // Flush remaining buffer after model completes
            if (!buffer.isEmpty()) {
              original.accept(ModelResponseChunk.text(String.join("", buffer)));
              buffer.clear();
            }

            return response;
          }
        };

    // Core "model" that streams many small chunks
    ModelNext core =
        (c, p) -> {
          Consumer<ModelResponseChunk> cb = p.getStreamCallback();
          cb.accept(ModelResponseChunk.text("A"));
          cb.accept(ModelResponseChunk.text("B"));
          cb.accept(ModelResponseChunk.text("C"));
          cb.accept(ModelResponseChunk.text("D"));
          cb.accept(ModelResponseChunk.text("E"));
          return responseWithText("final");
        };

    ModelNext chain = (c, p) -> bufferingMiddleware.wrapModel(c, p, core);

    chain.apply(ctx, new ModelParams(ModelRequest.builder().build(), clientCallback));

    // 5 small chunks → 2 combined chunks: "ABC" (3 buffered) + "DE" (2 flushed)
    assertEquals(List.of("ABC", "DE"), receivedByClient);
  }

  @Test
  void testWrapModel_middlewareCanFilterChunks() {
    // Demonstrates middleware that filters/censors streaming content —
    // a guardrail that removes chunks containing forbidden words.

    List<String> receivedByClient = new ArrayList<>();
    Consumer<ModelResponseChunk> clientCallback = chunk -> receivedByClient.add(chunk.getText());

    GenerationMiddleware guardrail =
        new BaseGenerationMiddleware() {
          @Override
          public String name() {
            return "content-filter";
          }

          @Override
          public GenerationMiddleware newInstance() {
            return this;
          }

          @Override
          public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
              throws GenkitException {
            Consumer<ModelResponseChunk> original = params.getStreamCallback();
            if (original == null) {
              return next.apply(ctx, params);
            }

            // Filter: drop chunks containing "SECRET"
            Consumer<ModelResponseChunk> filtered =
                chunk -> {
                  String text = chunk.getText();
                  if (text != null && !text.contains("SECRET")) {
                    original.accept(chunk);
                  }
                  // else: silently drop the chunk
                };

            return next.apply(ctx, params.withStreamCallback(filtered));
          }
        };

    ModelNext core =
        (c, p) -> {
          Consumer<ModelResponseChunk> cb = p.getStreamCallback();
          cb.accept(ModelResponseChunk.text("Hello"));
          cb.accept(ModelResponseChunk.text("The SECRET code"));
          cb.accept(ModelResponseChunk.text("World"));
          return responseWithText("final");
        };

    ModelNext chain = (c, p) -> guardrail.wrapModel(c, p, core);
    chain.apply(ctx, new ModelParams(ModelRequest.builder().build(), clientCallback));

    // The "SECRET" chunk was filtered out
    assertEquals(List.of("Hello", "World"), receivedByClient);
  }

  // =========================================================================
  // Helper
  // =========================================================================

  private static Tool<String, String> createTestTool(String name) {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "string");
    return new Tool<>(name, "Test tool", schema, schema, String.class, (ctx, input) -> "result");
  }
}
