---
name: developing-genkit-java
description: Best practices for developing with and contributing to Genkit Java — the open-source Java AI framework by Google. Covers project architecture, plugin development, flow definition, model integration, RAG pipelines, testing, naming conventions, and code quality guidelines. Use this skill when the user asks about building AI applications in Java with Genkit, creating custom plugins, defining flows, working with models, embedders, retrievers, tools, prompts, agents, or contributing to the Genkit Java codebase.
argument-hint: Describe the Genkit Java task (e.g., "create an Anthropic plugin", "add a RAG flow", "define a tool")
---

# Developing with Genkit Java

You are an expert on **Genkit Java**, the open-source Java AI framework by Google. This skill covers the full framework: architecture, plugin system, AI abstractions, and contribution guidelines.

## Project Architecture

Genkit Java is a Maven multi-module project requiring **Java 21+**.

### Module Hierarchy

```
genkit-java/
├── pom.xml                  # Parent POM (dependency management, plugins)
├── core/                    # Foundational abstractions (Action, Flow, Registry, Plugin, Middleware, Tracing)
│   └── com.google.genkit.core
├── ai/                      # AI-specific abstractions (Model, Tool, Embedder, Retriever, Indexer, Message, Part)
│   └── com.google.genkit.ai
├── genkit/                  # High-level user-facing API (Genkit class, Prompts, Sessions, Agents, Evaluators)
│   └── com.google.genkit
├── plugins/                 # Provider integrations (21 plugins)
│   └── com.google.genkit.plugins.{name}
└── samples/                 # Example applications (20+ samples)
    └── com.google.genkit.samples
```

### Dependency Flow

```
core ← ai ← genkit ← plugins ← samples
```

- **core** has zero Genkit internal dependencies. It depends on Jackson, SLF4J, OpenTelemetry, victools JSON Schema.
- **ai** depends on core.
- **genkit** depends on core + ai + Handlebars (for .prompt files).
- **plugins** depend on genkit (or ai/core).
- **samples** depend on genkit + chosen plugins.

### Key Dependencies (Managed in Parent POM)

| Library | Version | Purpose |
|---------|---------|---------|
| Jackson | 2.21.2 | JSON serialization (databind, annotations, jsr310) |
| SLF4J | 2.0.17 | Logging facade |
| Logback | 1.5.32 | Logging implementation |
| OkHttp | 5.3.2 | HTTP client + SSE streaming |
| OpenTelemetry | 1.60.1 | Tracing and metrics |
| Handlebars | 4.5.0 | .prompt file templating |
| victools | 4.38.0 | JSON Schema generation from Java classes |
| JUnit | 6.0.3 | Testing framework |
| Mockito | 5.23.0 | Mocking framework |

---

## Core Abstractions

### Action — The Universal Unit

Every capability in Genkit is an `Action<I, O, S>`:

- `I` = Input type
- `O` = Output type
- `S` = Streaming chunk type (`Void` for non-streaming)

```java
public interface Action<I, O, S> extends Registerable {
    String getName();
    ActionType getType();
    O run(ActionContext ctx, I input);
    O run(ActionContext ctx, I input, Consumer<S> streamCallback);
}
```

All AI primitives (Model, Tool, Embedder, Retriever, Indexer, Flow) implement `Action`. Actions self-register with the `Registry` using keys in the format `{type}/{name}` (e.g., `model/openai/gpt-4o`, `flow/myFlow`, `tool/getWeather`).

### ActionType Enum

```java
RETRIEVER, INDEXER, EMBEDDER, EVALUATOR, FLOW, MODEL, BACKGROUND_MODEL,
EXECUTABLE_PROMPT, PROMPT, RESOURCE, TOOL, TOOL_V2, UTIL, CUSTOM,
CHECK_OPERATION, CANCEL_OPERATION
```

### ActionContext

Passed to every action execution. Carries tracing info, registry access, session state:

```java
public class ActionContext {
    SpanContext spanContext;
    String flowName;
    Registry registry;
    String sessionId;
}
```

### Registry

Centralized action discovery and lookup:

```java
registry.registerAction(key, action);
registry.lookupAction("model/openai/gpt-4o");
registry.lookupAction(ActionType.FLOW, "myFlow");
```

---

## The Genkit Class — Main Entry Point

The `Genkit` class is the high-level API. Always use the builder pattern:

```java
Genkit genkit = Genkit.builder()
    .options(GenkitOptions.builder()
        .devMode(true)
        .reflectionPort(3100)
        .build())
    .plugin(new OpenAIPlugin())
    .plugin(new JettyPlugin())
    .build();

genkit.init();  // Initialize plugins, start servers
```

### Lifecycle

1. `Genkit.builder()...build()` — creates instance, does NOT initialize
2. `genkit.init()` — calls `plugin.init(registry)` for each plugin, starts dev server if applicable
3. `genkit.stop()` — cleanup resources

---

## Defining Flows

Flows are user-defined actions exposed as HTTP endpoints:

```java
// Simple (no context needed)
Flow<String, String, Void> greetFlow = genkit.defineFlow(
    "greet", String.class, String.class,
    (name) -> "Hello, " + name + "!");

// With ActionContext (for nested AI calls)
Flow<String, String, Void> jokeFlow = genkit.defineFlow(
    "tellJoke", String.class, String.class,
    (ctx, topic) -> {
        ModelResponse response = genkit.generate(
            GenerateOptions.builder()
                .model("openai/gpt-4o-mini")
                .prompt("Tell a joke about: " + topic)
                .config(GenerationConfig.builder().temperature(0.9).build())
                .build());
        return response.getText();
    });

// With middleware
Flow<String, String, Void> securedFlow = genkit.defineFlow(
    "secured", String.class, String.class,
    (ctx, input) -> processInput(input),
    List.of(authMiddleware, loggingMiddleware));
```

---

## Generation API

### Simple Generation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Explain quantum computing")
        .build());
String text = response.getText();
```

### Streaming Generation

```java
ModelResponse response = genkit.generateStream(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Write a story")
        .build(),
    chunk -> System.out.print(chunk.getText()));
```

### Structured Output

```java
MyPojo result = genkit.generateObject(
    GenerateOptions.<MyPojo>builder()
        .model("openai/gpt-4o")
        .prompt("Generate a recipe for pasta")
        .outputClass(MyPojo.class)
        .build());
```

### Multi-turn Messages

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .messages(List.of(
            Message.system("You are a helpful assistant."),
            Message.user("What is the capital of France?"),
            Message.model("Paris is the capital of France."),
            Message.user("What about Germany?")))
        .build());
```

### GenerationConfig

```java
GenerationConfig.builder()
    .temperature(0.9)
    .maxOutputTokens(2048)
    .topK(40)
    .topP(0.95)
    .stopSequences(List.of("\n\n"))
    .build()
```

---

## Tools — AI-Callable Functions

Define tools that models can invoke:

```java
// With auto-generated JSON Schema from classes
Tool<WeatherInput, WeatherOutput> weatherTool = genkit.defineTool(
    "getWeather",
    "Get current weather for a location",
    (ctx, input) -> fetchWeather(input.getLocation()),
    WeatherInput.class, WeatherOutput.class);

// Use tools in generation
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("What's the weather in London?")
        .tools(List.of(weatherTool))
        .build());
```

---

## RAG (Retrieval-Augmented Generation)

### Embedding

```java
EmbedResponse embeddings = genkit.embed(
    "openai/text-embedding-3-small",
    List.of(Document.fromText("Hello world")));
```

### Indexing

```java
genkit.index("devLocalVectorStore/my-index", documents);
```

### Retrieval + Generation

```java
List<Document> context = genkit.retrieve("devLocalVectorStore/my-index", query);

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt(query)
        .docs(context)  // Inject retrieved documents
        .build());
```

---

## DotPrompt — .prompt Files

Prompt files live in `resources/prompts/` with Handlebars templates and YAML frontmatter:

```
---
model: openai/gpt-4o-mini
config:
  temperature: 0.9
  maxOutputTokens: 500
input:
  schema:
    ingredient: string
    style?: string
---
Create a recipe using {{ingredient}} in a {{style}} style.
```

### Loading Prompts

```java
ExecutablePrompt<RecipeInput> prompt = genkit.prompt("recipe", RecipeInput.class);

// With variant (recipe.robot.prompt)
ExecutablePrompt<RecipeInput> robotPrompt = genkit.prompt("recipe", RecipeInput.class, "robot");
```

---

## Sessions & Chat

```java
Session<MyState> session = genkit.createSession();
Chat<MyState> chat = genkit.chat(ChatOptions.<MyState>builder()
    .model("openai/gpt-4o")
    .session(session)
    .build());
```

---

## Agents

Multi-agent systems with tool delegation:

```java
Agent researchAgent = genkit.defineAgent(AgentConfig.builder()
    .name("researcher")
    .model("openai/gpt-4o")
    .description("Research specialist")
    .tools(List.of(searchTool, summarizeTool))
    .build());
```

---

## Interrupts — Human-in-the-Loop

```java
Tool<ConfirmInput, ConfirmOutput> confirmTool = genkit.defineInterrupt(
    InterruptConfig.<ConfirmInput, ConfirmOutput>builder()
        .name("confirmAction")
        .inputClass(ConfirmInput.class)
        .outputClass(ConfirmOutput.class)
        .build());
```

---

## Evaluators

```java
Evaluator<String> factualityEval = genkit.defineEvaluator(
    "factuality", "Factuality Check", "Checks factual accuracy",
    (datapoint) -> {
        // Return EvalResponse with score, rationale, detail
    });

EvalRunKey result = genkit.evaluate(RunEvaluationRequest.builder()
    .evaluators(List.of("factuality"))
    .dataset(dataset)
    .build());
```

---

## Plugin Development

### The Plugin Interface

```java
public interface Plugin {
    String getName();
    List<Action<?, ?, ?>> init();
    default List<Action<?, ?, ?>> init(Registry registry) { return init(); }
}
```

### Creating a New Plugin

1. **Create module** under `plugins/{name}/` with its own `pom.xml`.
2. **Package**: `com.google.genkit.plugins.{name}`
3. **Implement** `Plugin` interface.
4. **Return actions** from `init()` — Models, Embedders, Tools, Retrievers, etc.

### Standard Plugin Structure

```
plugins/my-provider/
├── pom.xml
├── README.md
└── src/main/java/com/google/genkit/plugins/my_provider/
    ├── MyProviderPlugin.java          # Plugin entry point
    ├── MyProviderPluginOptions.java   # Configuration POJO (builder pattern)
    ├── MyProviderModel.java           # Model implementation
    ├── MyProviderEmbedder.java        # Embedder (if applicable)
    └── ...
```

### Plugin Implementation Pattern

```java
public class MyProviderPlugin implements Plugin {
    public static final List<String> SUPPORTED_MODELS = List.of("model-a", "model-b");
    
    private final MyProviderPluginOptions options;
    
    public MyProviderPlugin(MyProviderPluginOptions options) {
        this.options = options;
    }
    
    public static MyProviderPlugin create() {
        return new MyProviderPlugin(MyProviderPluginOptions.builder().build());
    }
    
    @Override
    public String getName() {
        return "my-provider";
    }
    
    @Override
    public List<Action<?, ?, ?>> init() {
        List<Action<?, ?, ?>> actions = new ArrayList<>();
        for (String model : SUPPORTED_MODELS) {
            actions.add(new MyProviderModel(
                getName() + "/" + model, model, options));
        }
        return actions;
    }
}
```

### compat-oai — Shared OpenAI-Compatible Base

Many plugins (OpenAI, Anthropic, XAI, DeepSeek, Mistral, Cohere, Groq) extend the `compat-oai` plugin which provides a shared base for OpenAI-compatible APIs. When building a plugin for an OpenAI-compatible provider, extend `CompatOAIPlugin` / `CompatOAIModel` instead of implementing from scratch.

### Server Plugins

- **JettyPlugin** — Lightweight HTTP server, exposes flows as endpoints
- **SpringPlugin** — Spring Boot integration with auto-generated REST endpoints (`GenkitFlowController`)

---

## Model Implementation

Models implement `Action<ModelRequest, ModelResponse, ModelResponseChunk>`:

```java
public class MyModel implements Model {
    @Override
    public ModelInfo getInfo() { return modelInfo; }
    
    @Override
    public boolean supportsStreaming() { return true; }
    
    @Override
    public ModelResponse run(ActionContext ctx, ModelRequest request) {
        // Make HTTP call to provider API
        // Map response to ModelResponse
    }
    
    @Override
    public ModelResponse run(ActionContext ctx, ModelRequest request,
                            Consumer<ModelResponseChunk> streamCallback) {
        // SSE streaming via OkHttp
    }
}
```

### Message & Part Model

```java
Message.user("Hello")                          // User message
Message.system("You are a helper")             // System message
Message.model("Response text")                 // Model response
Message.tool(List.of(Part.toolResponse(...)))   // Tool result

Part.text("Hello")                             // Text content
Part.media("image/png", dataUrl)               // Media content
Part.toolRequest(name, ref, input)             // Tool call
Part.toolResponse(name, ref, output)           // Tool result
```

---

## Middleware

Cross-cutting concerns applied to flows:

```java
@FunctionalInterface
public interface Middleware<I, O> {
    O handle(I request, ActionContext context, MiddlewareNext<I, O> next)
        throws GenkitException;
}

// Example: logging middleware
Middleware<String, String> logger = (input, ctx, next) -> {
    log.info("Input: {}", input);
    String result = next.handle(input, ctx);
    log.info("Output: {}", result);
    return result;
};
```

---

## Tracing & Observability

Genkit uses OpenTelemetry for tracing:

```java
SpanMetadata metadata = SpanMetadata.builder()
    .name("myOperation")
    .type("model")
    .build();

Tracer.runInNewSpan(ctx, metadata, input, (spanCtx, req) -> {
    return doWork(req);
});
```

---

## Naming Conventions

### Packages

```
com.google.genkit                  # Main API
com.google.genkit.core             # Core abstractions
com.google.genkit.ai               # AI abstractions
com.google.genkit.prompt           # Prompt system
com.google.genkit.plugins.{name}   # Plugins (use underscores for multi-word: google_genai)
com.google.genkit.samples          # Sample apps
```

### Classes

| Category | Convention | Examples |
|----------|-----------|----------|
| Interfaces | Noun | `Action`, `Model`, `Plugin`, `Registry` |
| Implementations | Prefixed noun | `ActionDef`, `DefaultRegistry`, `OpenAIModel` |
| Data classes | NounNoun | `ModelRequest`, `ModelResponse`, `GenerateOptions` |
| Builders | Inner static class | `MyClass.Builder` with `MyClass.builder()` |
| Exceptions | `GenkitException` | Custom with errorCode, details, traceId |
| Plugins | `{Provider}Plugin` | `OpenAIPlugin`, `AnthropicPlugin` |
| Options | `{Provider}PluginOptions` | `OpenAIPluginOptions` |

### Methods

| Category | Convention | Examples |
|----------|-----------|----------|
| Getters | `get{Property}()` | `getName()`, `getType()` |
| Factories | `static create()`, `static builder()`, `static define()` | |
| Execution | `run()` | `action.run(ctx, input)` |
| Registration | `register{Thing}()` | `registerAction()`, `registerPlugin()` |
| Lookup | `lookup{Thing}()` | `lookupAction()`, `lookupPlugin()` |
| Definition | `define{Thing}()` | `defineFlow()`, `defineTool()`, `defineAgent()` |

### Action Keys

Format: `{type}/{name}`

```
flow/myFlow
model/openai/gpt-4o
tool/getWeather
embedder/openai/text-embedding-3-small
retriever/myStore/docs
executable-prompt/myPrompt
```

---

## Testing Patterns

### Framework

JUnit 5 (Jupiter) + Mockito. Tests mirror source structure in `src/test/java/`.

### Typical Test

```java
@Test
void testFlowExecution() {
    Registry registry = new DefaultRegistry();
    
    Flow<String, String, Void> flow = Flow.define(
        registry, "testFlow", String.class, String.class,
        (ctx, input) -> input.toUpperCase());
    
    ActionContext ctx = new ActionContext(registry);
    String result = flow.run(ctx, "hello");
    
    assertEquals("HELLO", result);
}
```

### Test Conventions

- One test class per production class
- Test class name: `{ClassName}Test.java`
- Method names: `test{Behavior}` or descriptive camelCase
- Use `@Test`, `@BeforeEach`, `@AfterEach` annotations
- Mock external dependencies with Mockito

---

## Sample Application Structure

```
samples/{provider}/
├── pom.xml          # Dependencies: genkit, plugin, jetty/spring
├── README.md        # Setup instructions
├── run.sh           # Execution script
└── src/main/java/com/google/genkit/samples/
    └── {Provider}Sample.java
```

### Canonical Sample Pattern

```java
public class MySample {
    public static void main(String[] args) throws Exception {
        JettyPlugin jetty = new JettyPlugin(
            JettyPluginOptions.builder().port(8080).build());
        
        Genkit genkit = Genkit.builder()
            .options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
            .plugin(MyProviderPlugin.create())
            .plugin(jetty)
            .build();
        
        // Define flows
        genkit.defineFlow("myFlow", String.class, String.class,
            (ctx, input) -> {
                return genkit.generate(GenerateOptions.builder()
                    .model("provider/model-name")
                    .prompt(input)
                    .build()).getText();
            });
        
        genkit.init();
    }
}
```

---

## Code Quality & Build

### Commands

```bash
# Full build
mvn clean install

# Format code (Google Java Format)
mvn fmt:format

# Check formatting
mvn fmt:check

# Run tests
mvn test

# Build specific module
mvn -pl core clean install

# Build with dependencies
mvn -pl plugins/openai -am clean install
```

### Code Style

- **Formatter**: Google Java Format 1.35.0
- **Linter**: Checkstyle 13.4.0 (google_checks.xml)
- **Serialization**: Jackson `@JsonInclude(Include.NON_NULL)` — always omit null fields
- **Immutability**: Use builder pattern for configuration objects
- **Generics**: Preserve type safety with `Action<I, O, S>` pattern throughout

---

## Conventional Commits

All commits follow the format: `<type>(<scope>): <subject>`

### Types

| Type | Purpose | Version Impact |
|------|---------|----------------|
| `feat` | New feature | Minor bump |
| `fix` | Bug fix | Patch bump |
| `docs` | Documentation only | None |
| `style` | Formatting, no code change | None |
| `refactor` | Code restructuring | None |
| `perf` | Performance improvement | None |
| `test` | Adding/fixing tests | None |
| `build` | Build system/deps | None |
| `ci` | CI configuration | None |
| `chore` | Maintenance | None |
| `feat!` / `BREAKING CHANGE:` | Breaking change | Major bump |

### Scopes

`core`, `ai`, `genkit`, `openai`, `google-genai`, `anthropic`, `jetty`, `spring`, `firebase`, `localvec`, `mcp`, `samples`, `deps`

### Examples

```
feat(ai): add streaming support for generate
fix(openai): handle rate limit errors gracefully
docs(samples): add RAG example README
feat!: rename Genkit.create() to Genkit.builder()
```

---

## Error Handling

```java
public class GenkitException extends RuntimeException {
    String errorCode;
    Object details;
    String traceId;
    
    public static Builder builder() { ... }
}

// Throw structured errors
throw GenkitException.builder()
    .errorCode("NOT_FOUND")
    .message("Model not found: " + modelName)
    .build();
```

---

## Adding a New Module Checklist

When adding a new plugin or module:

1. Create directory under `plugins/{name}/`
2. Add `pom.xml` with parent reference to `genkit-parent`
3. Add module to root `pom.xml` `<modules>` section
4. Create package `com.google.genkit.plugins.{name}`
5. Implement `Plugin` interface
6. Create options class with builder pattern
7. Add tests in `src/test/java/`
8. Add `README.md` with setup instructions
9. Add sample app under `samples/{name}/`
10. Format code with `mvn fmt:format`
