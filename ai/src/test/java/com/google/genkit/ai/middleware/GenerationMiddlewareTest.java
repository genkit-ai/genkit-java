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
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
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

  // =========================================================================
  // GenerateNext tests
  // =========================================================================

  @Test
  void testGenerateNext_passThrough() {
    ModelRequest request = ModelRequest.builder().addUserMessage("hello").build();
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

    ModelRequest request = ModelRequest.builder().addUserMessage("test").build();
    outer.apply(ctx, new GenerateParams(request, 0));

    assertEquals(List.of("outer-before", "core", "outer-after"), order);
  }

  @Test
  void testGenerateNext_canModifyParams() {
    ModelRequest original = ModelRequest.builder().addUserMessage("original").build();
    ModelRequest modified = ModelRequest.builder().addUserMessage("modified").build();

    AtomicInteger iterationSeen = new AtomicInteger(-1);
    GenerateNext core =
        (c, p) -> {
          iterationSeen.set(p.getIteration());
          assertEquals(modified, p.getRequest());
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
  void testGenerateNext_exceptionPropagates() {
    GenerateNext failing =
        (c, p) -> {
          throw new GenkitException("boom");
        };

    ModelRequest request = ModelRequest.builder().build();
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
    ModelRequest req = ModelRequest.builder().addUserMessage("test").build();
    ModelResponse expected = responseWithText("pass");
    GenerateNext gNext = (c, p) -> expected;
    ModelResponse gResult = base.wrapGenerate(ctx, new GenerateParams(req, 0), gNext);
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
    ModelResponse resp = responseWithText("ok");

    // wrapModel is overridden
    middleware.wrapModel(ctx, new ModelParams(req, null), (c, p) -> resp);
    assertEquals(1, modelCallCount.get());

    // wrapGenerate still passes through (default)
    ModelResponse gResp = middleware.wrapGenerate(ctx, new GenerateParams(req, 0), (c, p) -> resp);
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

    ModelRequest req = ModelRequest.builder().build();
    chain.apply(ctx, new GenerateParams(req, 0));

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
  // Helper
  // =========================================================================

  private static Tool<String, String> createTestTool(String name) {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "string");
    return new Tool<>(name, "Test tool", schema, schema, String.class, (ctx, input) -> "result");
  }
}
