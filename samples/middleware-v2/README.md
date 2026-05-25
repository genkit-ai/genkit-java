# Genkit Java Middleware V2 Sample

This sample demonstrates the **V2 GenerationMiddleware** system, which provides three distinct hooks into the generation pipeline:

- **WrapGenerate** — wraps each iteration of the tool loop
- **WrapModel** — wraps each model API call
- **WrapTool** — wraps each tool execution

Unlike V1 middleware (which wraps flows), V2 middleware is attached per `generate()` call via `GenerateOptions.builder().use()` and hooks directly into the AI generation pipeline.

## Prerequisites

- Java 21+
- Maven 3.6+
- OpenAI API key

## Running the Sample

### Option 1: Direct Run

```bash
# Set your OpenAI API key
export OPENAI_API_KEY=your-api-key-here

# Navigate to the sample directory
cd java/samples/middleware-v2

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Set your OpenAI API key
export OPENAI_API_KEY=your-api-key-here

# Navigate to the sample directory
cd java/samples/middleware-v2

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

## Middleware Examples

### 1. ModelLoggingMiddleware (WrapModel)
Logs every model API call with a per-invocation counter. Demonstrates `newInstance()` for fresh state per `generate()` call.

### 2. GenerateTimingMiddleware (WrapGenerate)
Measures wall-clock time for each generate loop iteration (model call + tool execution).

### 3. ToolMonitorMiddleware (WrapTool)
Logs tool execution name and duration. Stateless — `newInstance()` returns `this`.

### 4. FullObservabilityMiddleware (All 3 hooks)
A single middleware that implements all three hooks, showing how one middleware can observe the entire pipeline with per-invocation counters.

## Available Endpoints

| Endpoint | Description | Middleware |
|----------|-------------|------------|
| `/v2-chat` | AI chat | Model logging + generate timing |
| `/v2-observable` | AI chat | Full observability (all 3 hooks) |
| `/v2-stacked` | AI chat | Three separate middleware stacked |
| `/v2-baseline` | AI chat | No middleware (baseline) |

## Example Requests

```bash
# Chat with model logging + timing
curl -X POST http://localhost:8080/v2-chat \
  -H 'Content-Type: application/json' \
  -d '"What is middleware?"'

# Chat with full observability
curl -X POST http://localhost:8080/v2-observable \
  -H 'Content-Type: application/json' \
  -d '"Explain Java records"'

# Chat with stacked middleware
curl -X POST http://localhost:8080/v2-stacked \
  -H 'Content-Type: application/json' \
  -d '"Hello world"'

# Baseline (no middleware)
curl -X POST http://localhost:8080/v2-baseline \
  -H 'Content-Type: application/json' \
  -d '"Hello world"'
```

## Creating Custom V2 Middleware

Extend `BaseGenerationMiddleware` and override only the hooks you need:

```java
import com.google.genkit.ai.middleware.*;
import com.google.genkit.core.ActionContext;

public class MyMiddleware extends BaseGenerationMiddleware {

  @Override
  public String name() { return "my-middleware"; }

  @Override
  public GenerationMiddleware newInstance() { return new MyMiddleware(); }

  @Override
  public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
      throws GenkitException {
    System.out.println("Before model call");
    ModelResponse resp = next.apply(ctx, params);
    System.out.println("After model call: " + resp.getText().length() + " chars");
    return resp;
  }
}
```

Then attach it to a `generate()` call:

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o-mini")
        .prompt("Hello")
        .use(new MyMiddleware())
        .build());
```

## Using middleware from the Dev UI

Register middleware with the `Genkit` builder so they appear in the Dev UI **Middleware** panel:

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .middleware(new MyMiddleware(), new AnotherMiddleware())
    .build();
```

In the Dev UI, open the Middleware panel, tick one or more middlewares, then run any model from the **Models** runner. The Dev UI sends the selected middleware names in the `use` field of the `/util/generate` action, which resolves them from the registry and dispatches the full `wrapGenerate` / `wrapModel` / `wrapTool` chain — middleware logs will appear in the server console.

`.middleware(...)` only controls Dev UI visibility; programmatic `GenerateOptions.builder().use(...)` calls do not require registration.

## Architecture

V2 middleware wraps the generation pipeline at three levels:

```
generate() call
  └─ WrapGenerate (per tool-loop iteration)
       └─ WrapModel (per model API call)
       └─ WrapTool  (per tool execution)
              └─ recurse → next WrapGenerate iteration
```

Each `generate()` call creates fresh middleware instances via `newInstance()`, enabling per-invocation state (counters, timers) without shared mutable state across requests.

Middleware are chained in order — the first middleware in the `use()` list is the outermost wrapper.

## V1 vs V2 Middleware

| | V1 (`Middleware<I,O>`) | V2 (`GenerationMiddleware`) |
|---|---|---|
| **Scope** | Wraps flows | Wraps generation pipeline |
| **Hooks** | Single `apply()` | 3 hooks: Generate, Model, Tool |
| **Attachment** | `defineFlow(..., middleware)` | `GenerateOptions.builder().use(...)` |
| **State** | Shared across calls | Fresh per `generate()` via `newInstance()` |

## See Also

- [V1 Middleware Sample](../middleware/) — flow-level middleware
- [Genkit Documentation](https://github.com/genkit-ai/genkit-java)
