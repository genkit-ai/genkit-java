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

### Sharing middleware as a plugin

Beyond the ad-hoc `.middleware(...)` method above, middleware can be **shared as a plugin** — the same way it works in the Genkit JS and Go SDKs. A plugin that provides middleware implements `MiddlewarePlugin` (alongside `Plugin`) and returns a list of **middleware descriptors** built with `GenerationMiddlewares.define(...)`:

```java
public class MyMiddlewarePlugin implements Plugin, MiddlewarePlugin {

    @Override
    public String getName() { return "my-middleware"; }

    @Override
    public List<Action<?, ?, ?>> init() { return List.of(); }

    @Override
    public List<GenerationMiddlewareDesc> middlewares(Registry registry) {
        return List.of(
            GenerationMiddlewares.define(
                "retry",                                   // name
                "Retry failed model calls.",               // description
                RetryMiddleware.Options.class,             // config type (schema auto-inferred)
                RetryMiddleware::new));                     // factory: config -> middleware
    }
}
```

Add the plugin to the builder and every middleware it provides is registered automatically:

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(new MyMiddlewarePlugin())
    .build();
```

### Parameterized middleware (Dev UI parameters form)

A middleware defined with a config type exposes a **parameters form** in the Dev UI. The config type is a plain POJO whose fields become the parameters; the JSON Schema is inferred automatically (use `@JsonPropertyDescription` to document each field):

```java
public class RetryMiddleware extends BaseGenerationMiddleware {

    private final Options options;

    public RetryMiddleware(Options options) { this.options = options; }

    @Override public String name() { return "retry"; }
    @Override public GenerationMiddleware newInstance() { return this; }

    @Override
    public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
            throws GenkitException {
        // ...use options.maxRetries etc...
        return next.apply(ctx, params);
    }

    /** These fields render as a form in the Dev UI Middleware panel. */
    public static class Options {
        @JsonProperty("maxRetries")
        @JsonPropertyDescription("Maximum number of retries after the initial attempt.")
        public int maxRetries = 3;

        public Options() {}
    }
}
```

In the Dev UI, selecting `retry` shows a form for `maxRetries`; the values you enter are sent to the `/util/generate` action as `use: [{ "name": "retry", "config": { "maxRetries": 2 } }]`. The runtime resolves the descriptor by name, binds the `config` onto a fresh `Options` instance (missing fields keep their defaults), and runs the resulting middleware. Config is applied as-is — defaults live in the middleware, so there is no separate validation step.

### Built-in middleware plugin

The `genkit-plugin-middleware` module ships a ready-to-use plugin, `GenerationMiddlewarePlugin`, with parameterized middleware (the Java equivalent of the JS `@genkit-ai/middleware` package):

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(GenerationMiddlewarePlugin.create())
    .build();
```

| Name | Hook | Parameters |
|------|------|------------|
| `retry` | `wrapModel` | `maxRetries`, `initialDelayMs`, `maxDelayMs`, `backoffFactor` |
| `fallback` | `wrapGenerate` | `models` (ordered list of fallback model names) |
| `simulateSystemPrompt` | `wrapGenerate` | `preface`, `acknowledgement` |
| `genkit-middleware/toolApproval` | `wrapTool` | `allowedTools`, `judge`, `judgePolicy` — see [Tool approval](#tool-approval) |
| `genkit-middleware/softToolErrors` | tool errors | `tools` — see [Returning tool errors to the model](#returning-tool-errors-to-the-model) |
| `genkit-middleware/filesystem` | tools | `rootDirectory`, `allowWriteAccess`, `toolNamePrefix` — see [File access](#file-access) |
| `genkit-middleware/skills` | tools, `wrapGenerate` | `skillPaths`, `preload`, `allowResourceAccess`, `toolNamePrefix` — see [Skills](#skills) |
| `genkit-middleware-exp/contextCompression` (experimental) | `wrapGenerate`, `wrapModel` | see [Context compression](#context-compression-experimental) |
| `genkit-middleware-exp/budget` (experimental) | `wrapGenerate`, `wrapTool` | `limit`, `scope`, `interrupt` (see [Usage budget](#usage-budget-experimental)) |

Each appears in the Dev UI Middleware panel with a parameters form, and can also be used programmatically, e.g. `new RetryMiddleware(opts)` passed to `GenerateOptions.builder().use(...)`.

### Context compression (experimental)

:::caution[Experimental]
`ContextCompressionMiddleware` is experimental and may change in any minor release.
:::

`ContextCompressionMiddleware` keeps a conversation inside a context budget through long chats and agentic tool loops. Before each tool-loop iteration it checks two triggers: the context is larger than `maxInputTokens` (measured by the input tokens the model reported for the previous call, or estimated from the messages before there is one), or the conversation holds more than `maxMessages` messages. When a trigger fires, it applies its strategies cheapest first:

1. **Deduplication** (`deduplicateToolResponses`) replaces older duplicate tool responses with a short notice.
2. **Truncation** (`toolResponses`) shortens older tool responses, leaving the newest tool messages intact.
3. **Summarization** (`summarize`) has a model fold older messages into a summary, unless the first two strategies already saved `skipSummarizationThreshold` of the context and brought it under budget.
4. **Dropping**: when nothing was summarized and the conversation is still over budget, the oldest messages are dropped, and a notice tells the model that context was removed.

Independently of the triggers, `maxToolResponseChars` caps every tool response on every iteration.

```java
ContextCompressionMiddleware.Options opts = new ContextCompressionMiddleware.Options();
opts.maxInputTokens = 80_000;
opts.deduplicateToolResponses = new ContextCompressionMiddleware.Dedupe();
opts.toolResponses = new ContextCompressionMiddleware.ToolTruncation(2000);
opts.summarize = new ContextCompressionMiddleware.Summarizer("googleai/gemini-2.5-flash-lite");

ModelResponse response = genkit.generate(GenerateOptions.builder()
    .model("googleai/gemini-2.5-flash")
    .prompt("Research this topic thoroughly.")
    .tools(List.of(searchTool))
    .use(new ContextCompressionMiddleware(opts), new RetryMiddleware(null))
    .build());
```

List it after middleware that adds context to the conversation, so the compression accounts for what they add, and before retry and fallback middleware, so retried and fallback calls receive the compressed conversation.

| Option | Default | Description |
|--------|---------|-------------|
| `maxInputTokens` | unset | Compress when the context exceeds this many tokens. |
| `maxMessages` | unset | Compress when the conversation holds more than this many messages, and cap the messages kept. Without `summarize`, a tight cap drops the turns that record a tool loop's progress, and the model may repeat calls it already made. |
| `preserveRecent` | 4 | How many of the newest non-system messages are kept when older messages are dropped, and the default window for `summarize`. Shrinks when the context is far over budget: halved beyond 1.5 times `maxInputTokens`, and down to 2 beyond twice. |
| `maxToolResponseChars` | 400000 | Cap on the characters of any single tool response, output and content together, on every iteration. Negative disables it. |
| `deduplicateToolResponses.matchBy` | `name-and-input` | Match duplicates by tool name and input, or by tool name alone (`name-only`), for tools that return the latest overall state. |
| `deduplicateToolResponses.keepRecent` | 1 | How many of the newest responses in each duplicate group stay intact. |
| `deduplicateToolResponses.notice` | built in | Text that replaces each older duplicate. |
| `toolResponses.maxChars` | required | Most characters an older tool response keeps. Text content is folded into the output when a response is truncated. |
| `toolResponses.preserveRecent` | 2 | How many of the newest tool messages stay intact. Negative truncates every tool message. |
| `summarize.model` | required | The model that writes summaries, typically a smaller, faster model. |
| `summarize.config` | unset | Config sent to the summarizer as is, such as its output limit. |
| `summarize.preserveRecent` | `preserveRecent`, else 6 | How many of the newest non-system messages stay out of the summary. |
| `summarize.prompt` | built in | Custom summarization prompt. Each `{conversation}` is replaced with the messages to summarize; without one, they are appended. |
| `skipSummarizationThreshold` | unset | Skip summarization when deduplication and truncation saved at least this fraction (0 to 1) of the context and brought it under `maxInputTokens`. |
| `truncationNotice` | built in | Custom notice inserted when messages are dropped. |
| `noTruncationNotice` | `false` | Drop messages without telling the model. |
| `replaceHistory` | `false` | Return the compressed messages as the history instead of the original messages. |

Leading system messages are always kept, and the kept messages never start with a tool response separated from the request it answers. Two kinds of message are never summarized or dropped: the scaffolding an agent's prompt renders on every turn, and the message that carries output-format instructions. If a summary fails (the summarizer errors, returns nothing, or stops early), the call continues with message dropping instead.

**History.** The history you get back from `response.getMessages()` keeps every original message: only the model sees the compressed conversation. The compression is recorded in message metadata under the `contextCompression` key, so when you pass the history to the next `generate()` call, store it in a session, or restore it after a restart, compression continues where it left off. To see what the model receives for a stored history, call:

```java
List<Message> view = ContextCompressionMiddleware.resolveCompressedHistory(history);
```

Set `replaceHistory` to get the compressed messages back as the history instead. The middleware never modifies the messages you pass in, so one history can be shared between concurrent calls.

**Stats.** The final response reports the newest compression under `response.getCustom().get("contextCompression")`: whether it was `triggered`, `inputTokensBefore`, `messagesOriginal` and `messagesAfter`, how many tool responses were capped, deduplicated, and truncated, and whether it inserted a notice, `summarized`, or skipped summarization.

### Usage budget (experimental)

:::caution[Experimental]
`BudgetMiddleware` is experimental and may change in any minor release.
:::

`BudgetMiddleware` caps the usage a `generate()` run, or an agent session, may spend. Every non-zero field of its `limit` is a cap: `inputTokens`, `outputTokens`, `thoughtsTokens`, `outputImages`, or a provider-reported cost in `custom`, key by key. Usage counts as models report it, so `outputTokens` does not include `thoughtsTokens`: cap both to cap what a model generates.

```java
Usage limit = new Usage();
limit.setInputTokens(50_000);
limit.setCustom(Map.of("cost", 0.25));

ModelResponse response = genkit.generate(GenerateOptions.builder()
    .model("googleai/gemini-2.5-flash")
    .prompt("Research this topic thoroughly.")
    .tools(List.of(searchTool))
    .use(new BudgetMiddleware(new BudgetMiddleware.Options(limit)))
    .build());
```

The limit is checked before each model call of the tool loop. Once a capped field reaches its limit, the run stops with `BudgetExceededException`: its error code is `ABORTED`, and `getResponse()` holds the partial response with finish reason `ABORTED`. A turn already under way finishes, so a run can go over by one turn (several billed calls when a retry middleware is in the chain). The budget counts every model call made under the run: the tool loop, calls other middleware make, `generate()` calls inside tools, and sub-agents the run invokes, so delegation cannot spend around it.

| Option | Default | Description |
|--------|---------|-------------|
| `limit` | required | The usage the run or session may spend. At least one field must be set; negative and non-finite values are rejected. |
| `scope` | `run` | `run` counts one `generate()` call. `session` counts the agent session's usage across all its turns and invocations, plus the current run. |
| `interrupt` | `false` | Pause the run for approval instead of stopping it. Run scope only. |

**Session scope** needs an agent with a session store, because a client-managed session's state, usage included, comes back from the caller. Create the middleware inside the agent's turn; outside such an agent it fails with `FAILED_PRECONDITION`. A session-scoped budget cannot interrupt. When a session budget stops a turn, the agent turn finishes as `ABORTED`, and the next invocation of an over-budget session stops before it calls the model.

**Pausing for approval.** With `interrupt` set, the run pauses instead of stopping: the tool calls that would lead to the next turn are held as interrupts. Claim each hold with `BudgetMiddleware.interrupted(part)`, read what was spent against which limit with `BudgetMiddleware.exceeded(call)`, and resume with a decision:

```java
ResumeOptions.Builder resume = ResumeOptions.builder();
for (Part part : response.getInterrupts()) {
  BudgetMiddleware.interrupted(part).ifPresent(call -> {
    System.out.println(BudgetMiddleware.exceeded(call).message);
    resume.part(call.restart(BudgetMiddleware.Decision.approve()));
  });
}

ModelResponse resumed = genkit.generate(GenerateOptions.builder()
    .model("googleai/gemini-2.5-flash")
    .messages(response.getMessages())
    .tools(List.of(searchTool))
    .resume(resume.build())
    .use(budget)
    .build());
```

Approving continues the run with the full limit again. `Decision.refuse(reason)` stops it with `BudgetExceededException` before the held calls run, and so does answering a hold with `call.respond(output)`. The run pauses only when it is over its limit by the time its tool calls start; a run that goes over while its tools run (for example, in a sub-agent) has nothing left to hold, and stops at the next check.

A run-scoped budget counts one run across its resumes: the run's final message records what it spent, so answering another middleware's interrupt continues the count rather than renewing it. Only approving the budget's own hold renews the limit, and a new turn of the conversation starts from zero.

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

When a tool interrupts after a restart, the call ends interrupted again (the model is not called), and the calls this resume already resolved are kept, so the next resume only answers the new interrupt.

### Holding a tool call from middleware

A `wrapTool` hook can hold a call before the tool runs by throwing `ToolInterruptException` instead of calling `next`. The hold is the middleware's own question: the interrupt part records the middleware that raised it, and a restart delivers its answer to that middleware alone, through `ctx.getResumed()` in its `wrapTool` hook. The tool never sees that answer; once the middleware lets the call through, the tool runs as a fresh call and may interrupt with a question of its own.

When a later question is answered, a middleware that already let the call through sees `params.isReleased()` return `true`, so it can pass the restart on instead of holding the call again. A restart that replaces the call's input is not released: every middleware decides again.

Applications claim a middleware's holds with `InterruptedCall.ofMiddleware(name, part)`, and answer them with `restart(answer)`. Middleware authors usually wrap that in a method that fixes the name and the answer type, as `ToolApproval.toolApprovalInterrupted(part)` does:

```java
ResumeOptions.Builder resume = ResumeOptions.builder();
for (Part part : response.getInterrupts()) {
    InterruptedCall.<MyAnswer>ofMiddleware("myapp/gate", part)
        .ifPresent(call -> resume.part(call.restart(new MyAnswer(true))));
}

ModelResponse resumed = genkit.generate(GenerateOptions.builder()
    .model("openai/gpt-4o-mini")
    .messages(response.getMessages())
    .tools(tools)
    .use(gate)
    .resume(resume.build())
    .build());
```

Resuming without answering an interrupted call fails with an error that names the call and, for a hold, the middleware that raised it.

A tool, or a `wrapTool` hook, can also answer a call with an error the model reads, rather than one that reaches your code, by throwing `ToolFailException`. The model receives `{"error": "<message>"}` as the tool's result and can correct its input and try again:

```java
if (!orders.containsKey(input.id)) {
    throw new ToolFailException("no order with id " + input.id + "; list the orders first");
}
```

### Tool approval

`ToolApproval` holds every tool call that isn't on its allowlist until the caller approves it:

```java
ToolApproval approval = ToolApproval.builder()
    .allowedTools("listFiles")      // runs without asking
    .build();

ModelResponse response = genkit.generate(GenerateOptions.builder()
    .model("openai/gpt-4o-mini")
    .prompt("Clean up the build directory")
    .tools(List.of(listFiles, deleteFile))
    .use(approval)
    .build());
```

Each held call comes back as an interrupt. Claim it with `ToolApproval.toolApprovalInterrupted(part)` and answer it with a `ToolCallDecision`:

```java
ResumeOptions.Builder resume = ResumeOptions.builder();
for (Part part : response.getInterrupts()) {
    ToolApproval.toolApprovalInterrupted(part).ifPresent(call -> {
        boolean ok = askUser(call.getPart().getToolRequest());
        resume.part(call.restart(ok
            ? ToolCallDecision.approve()
            : ToolCallDecision.deny("the user declined")));
    });
}
```

Every restart of a hold decides it. An approved decision runs the tool. Any other restart, including one without a decision, answers the call with an error the model sees (with your reason, when you give one), and the generation continues without it. Answering a hold with `call.respond(output)` does not refuse the call: the output stands in for the tool's result and must match the tool's output schema.

Over the wire, the decision is the restart's `resumed` metadata: `{"toolApproved": true}`, or `{"toolApproved": false, "reason": "..."}`.

**Judge.** With a judge model set, a model decides each call that the allowlist does not approve, instead of holding it unconditionally:

```java
ToolApproval approval = ToolApproval.builder()
    .allowedTools("listFiles")
    .judge("googleai/gemini-2.5-flash-lite")
    .judgePolicy("Never allow deleting files outside the build directory.")
    .build();
```

The judge answers with one of three verdicts:

- `allow` runs the tool.
- `deny` answers the call with an error the model sees, and the generation continues.
- `ask` holds the call for the caller, as without a judge.

The judge sees the text the user wrote and the pending call (the tool's name, description, and input) as one JSON document. It does not see model text, tool results, retrieved documents, or file contents added by the `Filesystem` middleware, since injected instructions usually arrive through those. A judge that fails, or answers with anything other than a verdict, holds the call. A hold the judge caused carries a `judge` key in its interrupt data, `"ask"` or `"failed"`, so you can tell a judge that wants the user to confirm from one that could not decide. Your answer to such a hold is final: the judge is not asked again.

The judge is asked through the enum output format, so any model that can answer with one of a list of values works. A smaller, faster model than the one being judged keeps the added latency low; each judged call costs one model call. Pass `judge(modelName, config)` to give the judge its own model configuration.

The allowlist matches tool names exactly, so list the names of tools that other middleware add (such as `read_file` or `use_skill`) if they should run without approval.

### Returning tool errors to the model

`SoftToolErrors` returns tool errors to the model as the tool's result, `{"error": "<message>"}`, instead of failing the generation, so the model can correct its input and try again. It also covers calls to tool names that don't exist.

```java
genkit.generate(GenerateOptions.builder()
    .model("openai/gpt-4o-mini")
    .prompt("What is the weather in Paris?")
    .tools(List.of(weatherTool))
    .use(new SoftToolErrors())                  // every tool
    // .use(SoftToolErrors.forTools("weather"))  // only these tools
    .build());
```

With `SoftToolErrors` on a call, an error from a tool it does not cover fails the generation. Several instances combine: a tool covered by any of them returns its errors to the model. Errors that a `wrapTool` hook raises in place of the tool's own, and interrupts, are never turned into results. Other `wrapTool` hooks, such as a retry, still see the tool's error first.

Without any error policy on the call, a tool error is returned to the model as `"Tool execution failed: <message>"`.

### File access

`Filesystem` gives the model tools to work with files under one root directory: `list_files` and `read_file`, plus `write_file` and `edit_file` with `allowWriteAccess`.

```java
genkit.generate(GenerateOptions.builder()
    .model("openai/gpt-4o-mini")
    .prompt("Summarise docs/ and save the summary to out.md")
    .use(Filesystem.builder()
        .rootDirectory("./workspace")
        .allowWriteAccess(true)
        .build())
    .build());
```

- Every path is confined to the root directory. Paths that leave it, through `..`, an absolute path, or a symbolic link, are refused.
- `read_file` delivers a file's contents as a user message on the next turn, and answers the call with a short summary. Images are delivered as media. Use `offset` and `limit` (1-indexed lines) for files over 256 KB; files over 10 MB can't be read.
- Re-reading an unchanged file returns a short note instead of the contents again.
- `edit_file` and overwriting an existing file with `write_file` require a prior `read_file` of that file in the same generation, and fail if the file changed on disk since.
- A failed file operation answers the call with its error, so the model can correct the path or the edit. Errors of other tools are handled as usual.
- `toolNamePrefix` prefixes every tool name, so you can attach more than one `Filesystem` to a call.

The messages that carry file contents are marked with the `filesystemMiddlewareTool` metadata key, so other middleware can tell them apart from what the user wrote.

### Skills

`Skills` gives the model a local library of skills, following the [Agent Skills](https://agentskills.io) format. Each skill is a folder containing a `SKILL.md` file that starts with YAML frontmatter carrying a `name` and a `description`.

```java
Skills skills = Skills.builder()
    .skillPaths("skills")
    .allowResourceAccess(true)
    .build();

ModelResponse resp = genkit.generate(GenerateOptions.builder()
    .model("googleai/gemini-2.5-flash")
    .prompt("Use the python skill to compute the first 10 primes")
    .use(skills)
    .build());
```

- A short catalog of the available skills is added to the system prompt.
- The model loads a skill's full instructions on demand with the `use_skill` tool. A skill already loaded in the conversation is not sent again.
- With `allowResourceAccess`, each loaded skill also lists the files it bundles, and the model can read them with `read_skill_file`. Reads stay inside that skill's folder.
- `preload` names skills whose instructions are added up front, without waiting for the model to load them.
- By default the middleware scans `.agents/skills` and `skills` under the working directory. Skill folders are rescanned on every `generate()` call, and a malformed skill is logged rather than failing the request.
- To ship skills inside your application, set `skillFs` to a path on any `FileSystem`, such as a zip or jar:

```java
FileSystem zip = FileSystems.newFileSystem(Path.of("skills.zip"));
Skills embedded = Skills.builder().skillFs(zip.getPath("/")).build();
```

Every part that carries a skill's instructions has the `skillActivation` metadata key, valued with the skill name. Treat a skills folder like source code: its instructions are followed by the model.

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
