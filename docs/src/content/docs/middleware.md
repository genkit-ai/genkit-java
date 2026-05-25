---
title: Middleware
description: Add cross-cutting concerns to your AI workflows with flow middleware and generation middleware.
---

Middleware lets you intercept and modify the behavior of flow executions and AI generation. Genkit provides two middleware systems:

- **Flow Middleware** — wraps the entire flow function. Use for logging, caching, rate limiting, retries, and input validation.
- **Generation Middleware (V2)** — hooks into the `generate()` pipeline at three levels: model calls, tool executions, and loop iterations. Use for metering, observability, and tool interception.

## Flow Middleware

Flow middleware follows the chain-of-responsibility pattern — each middleware can modify the request, call the next handler, and modify the response.

### Defining middleware

A middleware is a function that receives the request, an `ActionContext`, and a `next` function to call the next handler in the chain:

```java
import com.google.genkit.core.middleware.Middleware;

Middleware<String, String> loggingMiddleware = (request, context, next) -> {
    System.out.println("Request: " + request);
    String result = next.apply(request, context);
    System.out.println("Response: " + result);
    return result;
};
```

### Attaching middleware to flows

Pass middleware as a list when defining a flow:

```java
List<Middleware<String, String>> middleware = List.of(
    loggingMiddleware,
    validationMiddleware,
    retryMiddleware
);

Flow<String, String, Void> chatFlow = genkit.defineFlow(
    "chat", String.class, String.class,
    (ctx, userMessage) -> {
        ModelResponse response = genkit.generate(
            GenerateOptions.builder()
                .model("openai/gpt-4o-mini")
                .prompt(userMessage)
                .build());
        return response.getText();
    },
    middleware);
```

Middleware executes in order — the first middleware in the list runs first (outermost), wrapping all subsequent middleware and the flow handler.

### Built-in middleware

The `CommonMiddleware` class provides factory methods for common patterns:

#### Logging

```java
import com.google.genkit.core.middleware.CommonMiddleware;

// Default logger
Middleware<String, String> logging = CommonMiddleware.logging("chat");

// Custom logger
Middleware<String, String> logging = CommonMiddleware.logging("chat", myLogger);
```

#### Retry with exponential backoff

```java
// Retry up to 3 times with 100ms initial delay
Middleware<String, String> retry = CommonMiddleware.retry(3, 100);

// With custom retry predicate
Middleware<String, String> retry = CommonMiddleware.retry(3, 100,
    error -> error.getMessage().contains("rate limit"));
```

#### Input validation

```java
Middleware<String, String> validate = CommonMiddleware.validate(input -> {
    if (input == null || input.trim().isEmpty()) {
        throw new GenkitException("Input cannot be empty");
    }
    if (input.length() > 1000) {
        throw new GenkitException("Input exceeds maximum length");
    }
});
```

#### Request and response transformation

```java
// Sanitize input
Middleware<String, String> sanitize = CommonMiddleware.transformRequest(
    input -> input.trim().replaceAll("\\s+", " "));

// Format output
Middleware<String, String> format = CommonMiddleware.transformResponse(
    output -> "[" + Instant.now() + "] " + output);
```

#### Caching

```java
import com.google.genkit.core.middleware.MiddlewareCache;

Middleware<String, String> cache = CommonMiddleware.cache(
    myCache,                        // MiddlewareCache implementation
    input -> input.hashCode() + "" // key extractor
);
```

The `MiddlewareCache<O>` interface requires `get(String key)` and `put(String key, O value)` methods.

#### Rate limiting

```java
// Max 10 requests per 60 seconds
Middleware<String, String> rateLimit = CommonMiddleware.rateLimit(10, 60_000);
```

#### Timeout

```java
// 30 second timeout
Middleware<String, String> timeout = CommonMiddleware.timeout(30_000);
```

#### Error handling

```java
Middleware<String, String> errorHandler = CommonMiddleware.errorHandler(
    error -> "Sorry, something went wrong: " + error.getMessage());
```

#### Conditional middleware

Apply middleware only when a condition is met:

```java
Middleware<String, String> conditional = CommonMiddleware.conditional(
    (request, context) -> request.length() > 100,  // only for long inputs
    CommonMiddleware.logging("long-input")
);
```

#### Before/after hooks

```java
Middleware<String, String> hooks = CommonMiddleware.beforeAfter(
    (request, context) -> System.out.println("Before: " + request),
    (response, context) -> System.out.println("After: " + response)
);
```

#### Timing

```java
Middleware<String, String> timing = CommonMiddleware.timing(
    duration -> System.out.println("Took " + duration + "ms"));
```

### Building a middleware chain

Use `MiddlewareChain` for more control over middleware ordering:

```java
import com.google.genkit.core.middleware.MiddlewareChain;

MiddlewareChain<String, String> chain = MiddlewareChain.of(
    CommonMiddleware.logging("chat"),
    CommonMiddleware.validate(input -> { /* ... */ }),
    CommonMiddleware.retry(3, 100));

// Add middleware dynamically
chain.use(CommonMiddleware.timing(d -> log.info("{}ms", d)));
chain.useFirst(CommonMiddleware.rateLimit(10, 60_000)); // insert at beginning

// Execute manually
String result = chain.execute(input, context, (ctx, req) -> {
    // final handler
    return genkit.generate(...).getText();
});
```

### Custom middleware example

A metrics-collecting middleware:

```java
Map<String, AtomicLong> requestCounts = new ConcurrentHashMap<>();
Map<String, List<Long>> responseTimes = new ConcurrentHashMap<>();

Middleware<String, String> metricsMiddleware = (request, context, next) -> {
    requestCounts.computeIfAbsent("chat", k -> new AtomicLong(0))
        .incrementAndGet();
    long start = System.currentTimeMillis();
    try {
        String result = next.apply(request, context);
        long duration = System.currentTimeMillis() - start;
        responseTimes.computeIfAbsent("chat", k -> new ArrayList<>())
            .add(duration);
        return result;
    } catch (GenkitException e) {
        // Track errors too
        throw e;
    }
};
```

### Built-in middleware reference

| Factory Method | Description |
|---------------|-------------|
| `logging(name)` | Log requests and responses |
| `retry(maxRetries, delayMs)` | Retry with exponential backoff |
| `validate(validator)` | Validate input before processing |
| `transformRequest(fn)` | Transform input before processing |
| `transformResponse(fn)` | Transform output after processing |
| `cache(cache, keyExtractor)` | Cache responses |
| `rateLimit(maxReqs, windowMs)` | Limit request rate |
| `timeout(timeoutMs)` | Fail if execution exceeds timeout |
| `errorHandler(handler)` | Return fallback on error |
| `conditional(predicate, mw)` | Apply middleware conditionally |
| `beforeAfter(before, after)` | Run hooks before and after |
| `timing(callback)` | Measure execution duration |

## Generation Middleware (V2)

Generation Middleware provides fine-grained hooks into the generation pipeline, letting you intercept model calls, tool executions, and generate loop iterations independently. Unlike flow-level middleware (which wraps the entire flow function), Generation Middleware operates inside `generate()` and is attached per call.

### Three hooks

| Hook | Wraps | Receives | Use cases |
|------|-------|----------|-----------|
| `wrapGenerate` | Each iteration of the tool loop | `GenerateParams` (request + iteration number) | Timing, logging per turn, retry logic |
| `wrapModel` | Each model API call | `ModelParams` (request + stream callback) | Token metering, request/response rewriting, caching |
| `wrapTool` | Each tool execution | `ToolParams` (request part + resolved tool) | Tool authorization, audit logging, error handling |

Hooks nest naturally: `wrapGenerate` is the outermost layer, `wrapModel` runs inside it, and `wrapTool` runs for each tool the model requests.

```
wrapGenerate (iteration 0)
├── wrapModel → model API call
├── wrapTool → tool1
├── wrapTool → tool2
└── wrapGenerate (iteration 1)  ← recursive via tool loop
    ├── wrapModel → model API call
    └── (no more tool calls → return)
```

### Defining Generation Middleware

Implement the `GenerationMiddleware` interface or extend `BaseGenerationMiddleware` (which passes through by default). Override only the hooks you need:

```java
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;

class TokenMeteringMiddleware extends BaseGenerationMiddleware {

    private final AtomicInteger totalTokens = new AtomicInteger(0);

    @Override
    public String name() {
        return "token-metering";
    }

    @Override
    public GenerationMiddleware newInstance() {
        return new TokenMeteringMiddleware();  // fresh counters per generate()
    }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
            throws GenkitException {
        ModelResponse response = next.apply(ctx, params);
        // Inspect response for token usage
        logger.info("Tokens used: {}", response.getUsage());
        return response;
    }
}
```

Key points:

- **`name()`** — unique identifier for the middleware.
- **`newInstance()`** — called once per `generate()` invocation. Return a fresh object so per-request state (counters, timers) is isolated. Stateless middleware can return `this`.
- **`next.apply(ctx, params)`** — calls the next middleware in the chain (or the core handler). You must call it to continue the pipeline. Skip it to short-circuit (e.g., return a cached response).

### Attaching middleware to generate()

Use `GenerateOptions.builder().use()`:

```java
GenerationMiddleware metering = new TokenMeteringMiddleware();
GenerationMiddleware logging = new ModelLoggingMiddleware();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o-mini")
        .prompt("Explain middleware")
        .use(metering, logging)
        .build());
```

Middleware order matters — the **first** middleware listed is **outermost** (runs first on the way in, last on the way out).

### Using middleware from the Dev UI

To make middleware selectable from the **Middleware** panel in the Genkit Dev UI, register it with the `Genkit` builder via `.middleware(...)`:

```java
GenerationMiddleware modelLogging = new ModelLoggingMiddleware();
GenerationMiddleware timing = new GenerateTimingMiddleware();
GenerationMiddleware toolMonitor = new ToolMonitorMiddleware();

Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .middleware(modelLogging, timing, toolMonitor)
    .build();
```

Each registered middleware appears in the Dev UI Middleware panel by `name()`. When you select one or more middlewares in the panel and run a model from the **Models** runner, the Dev UI invokes the `/util/generate` action with the selected middleware names in the `use` field. The action resolves each name back to the registered middleware via the registry and runs it through the same `wrapGenerate` / `wrapModel` / `wrapTool` chain that applies to programmatic `generate()` calls.

A fresh middleware instance (via `newInstance()`) is created per Dev UI invocation, so per-request state (counters, timers) is isolated just as it is for code-driven calls.

> **Note:** `.middleware(...)` only controls Dev UI visibility. Middleware attached programmatically with `GenerateOptions.builder().use(...)` does not need to be registered with the builder.

### Multi-hook middleware

A single middleware can implement all three hooks to observe every stage:

```java
class FullObservabilityMiddleware extends BaseGenerationMiddleware {

    private final AtomicInteger iterations = new AtomicInteger(0);
    private final AtomicInteger modelCalls = new AtomicInteger(0);
    private final AtomicInteger toolCalls = new AtomicInteger(0);

    @Override
    public String name() { return "full-observability"; }

    @Override
    public GenerationMiddleware newInstance() {
        return new FullObservabilityMiddleware();
    }

    @Override
    public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params,
            GenerateNext next) throws GenkitException {
        int iter = iterations.incrementAndGet();
        logger.info("=== Generate iteration {} ===", iter);
        ModelResponse resp = next.apply(ctx, params);
        logger.info("=== Iteration {} done (model: {}, tools: {}) ===",
            iter, modelCalls.get(), toolCalls.get());
        return resp;
    }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params,
            ModelNext next) throws GenkitException {
        modelCalls.incrementAndGet();
        return next.apply(ctx, params);
    }

    @Override
    public Part wrapTool(ActionContext ctx, ToolParams params,
            ToolNext next) throws GenkitException {
        toolCalls.incrementAndGet();
        logger.info("Tool: {}", params.getRequest().getName());
        return next.apply(ctx, params);
    }
}
```

### Middleware-provided tools

Middleware can inject additional tools into the generation by overriding `tools()`:

```java
@Override
public List<Tool<?, ?>> tools() {
    return List.of(myCustomTool);
}
```

These tools are merged with the tools from `GenerateOptions.tools()` and are available for the model to call.

### Middleware with interrupts and restarts

Generation Middleware integrates with the [interrupt system](/docs/interrupts). When a tool throws `ToolInterruptException`, the `wrapTool` hook still fires — the exception propagates through the middleware chain, so you can observe or handle it.

When resuming with `ResumeOptions.builder().restart(toolRequest)`, the restarted tool executes through the full `wrapTool` chain, and the subsequent model call goes through a new `wrapGenerate` iteration. This ensures middleware sees every operation regardless of whether it was an initial call or a restart.

```
Initial generate:
  wrapGenerate(0)
  ├── wrapModel → model requests tool4
  ├── wrapTool → tool1 (completes)
  ├── wrapTool → tool2 (completes)
  └── wrapTool → tool4 (interrupts!) → return interrupted response

Restart generate:
  wrapTool → tool4 (restart, completes)
  wrapGenerate(1)
  ├── wrapModel → model returns final answer
  └── return response
```

### BaseGenerationMiddleware

`BaseGenerationMiddleware` provides pass-through defaults for all hooks. Extend it to override only what you need:

```java
class TimingMiddleware extends BaseGenerationMiddleware {

    @Override
    public String name() { return "timing"; }

    @Override
    public GenerationMiddleware newInstance() { return new TimingMiddleware(); }

    @Override
    public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params,
            GenerateNext next) throws GenkitException {
        long start = System.currentTimeMillis();
        ModelResponse resp = next.apply(ctx, params);
        logger.info("Iteration {} took {}ms",
            params.getIteration(), System.currentTimeMillis() - start);
        return resp;
    }
}
```

### Generation Middleware vs Flow Middleware

| | Flow Middleware | Generation Middleware (V2) |
|---|---|---|
| **Scope** | The entire flow function | Inside `generate()` — model, tools, iterations |
| **Attached to** | `defineFlow(..., middleware)` | `GenerateOptions.builder().use()` |
| **Typed to** | Flow input/output types | `ModelRequest` / `ModelResponse` / `Part` |
| **State** | Shared across requests | Fresh per `generate()` via `newInstance()` |
| **Best for** | Auth, rate limiting, validation | Observability, metering, tool interception |

You can use both together — flow middleware wraps the outer flow, and generation middleware wraps the inner AI pipeline.

## Samples

- [middleware sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/middleware) — Flow-level middleware patterns (logging, retry, caching, validation)
- [middleware-v2 sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/middleware-v2) — Generation Middleware with all three hooks and interrupt/restart lifecycle
