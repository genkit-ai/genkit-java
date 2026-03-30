---
title: Middleware
description: Add cross-cutting concerns to your AI workflows with middleware.
---

Middleware lets you intercept and modify the behavior of flow executions. Use middleware for logging, caching, rate limiting, retries, input validation, and more. Middleware follows the chain-of-responsibility pattern — each middleware can modify the request, call the next handler, and modify the response.

## Defining middleware

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

## Attaching middleware to flows

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

## Built-in middleware

The `CommonMiddleware` class provides factory methods for common patterns:

### Logging

```java
import com.google.genkit.core.middleware.CommonMiddleware;

// Default logger
Middleware<String, String> logging = CommonMiddleware.logging("chat");

// Custom logger
Middleware<String, String> logging = CommonMiddleware.logging("chat", myLogger);
```

### Retry with exponential backoff

```java
// Retry up to 3 times with 100ms initial delay
Middleware<String, String> retry = CommonMiddleware.retry(3, 100);

// With custom retry predicate
Middleware<String, String> retry = CommonMiddleware.retry(3, 100,
    error -> error.getMessage().contains("rate limit"));
```

### Input validation

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

### Request and response transformation

```java
// Sanitize input
Middleware<String, String> sanitize = CommonMiddleware.transformRequest(
    input -> input.trim().replaceAll("\\s+", " "));

// Format output
Middleware<String, String> format = CommonMiddleware.transformResponse(
    output -> "[" + Instant.now() + "] " + output);
```

### Caching

```java
import com.google.genkit.core.middleware.MiddlewareCache;

Middleware<String, String> cache = CommonMiddleware.cache(
    myCache,                        // MiddlewareCache implementation
    input -> input.hashCode() + "" // key extractor
);
```

The `MiddlewareCache<O>` interface requires `get(String key)` and `put(String key, O value)` methods.

### Rate limiting

```java
// Max 10 requests per 60 seconds
Middleware<String, String> rateLimit = CommonMiddleware.rateLimit(10, 60_000);
```

### Timeout

```java
// 30 second timeout
Middleware<String, String> timeout = CommonMiddleware.timeout(30_000);
```

### Error handling

```java
Middleware<String, String> errorHandler = CommonMiddleware.errorHandler(
    error -> "Sorry, something went wrong: " + error.getMessage());
```

### Conditional middleware

Apply middleware only when a condition is met:

```java
Middleware<String, String> conditional = CommonMiddleware.conditional(
    (request, context) -> request.length() > 100,  // only for long inputs
    CommonMiddleware.logging("long-input")
);
```

### Before/after hooks

```java
Middleware<String, String> hooks = CommonMiddleware.beforeAfter(
    (request, context) -> System.out.println("Before: " + request),
    (response, context) -> System.out.println("After: " + response)
);
```

### Timing

```java
Middleware<String, String> timing = CommonMiddleware.timing(
    duration -> System.out.println("Took " + duration + "ms"));
```

## Building a middleware chain

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

## Custom middleware example

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

## Built-in middleware reference

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

## Samples

- [middleware sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/middleware) — Custom and built-in middleware patterns
- [middleware-v2 sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/middleware-v2) — Updated middleware approaches
