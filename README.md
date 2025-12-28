# Genkit for Java

Genkit for Java is the Java implementation of the Genkit framework for building AI-powered applications.

See: https://genkit.dev

> **Status**: Currently in active development (1.0.0-SNAPSHOT). Requires Java 21+.
> 
> **Note**: The Java SDK supports OpenAI, Google GenAI (Gemini), Anthropic (Claude), AWS Bedrock, Azure AI Foundry, XAI (Grok), DeepSeek, Cohere, Mistral, Groq, Ollama (local models), any OpenAI-compatible endpoint (via compat-oai), Firebase (Firestore vector search, Cloud Functions, telemetry), vector databases (Weaviate, PostgreSQL, Pinecone), MCP, and pre-built evaluators. See [Modules](#modules) for the full list.

<!-- TOC -->

- [Genkit for Java](#genkit-for-java)
  - [Installation](#installation)
  - [Quick Start](#quick-start)
  - [Defining Flows](#defining-flows)
  - [Using Tools](#using-tools)
  - [DotPrompt Support](#dotprompt-support)
  - [Structured Output](#structured-output)
  - [RAG (Retrieval Augmented Generation)](#rag-retrieval-augmented-generation)
  - [Firebase Integration](#firebase-integration)
    - [Firestore Vector Search](#firestore-vector-search)
    - [Cloud Functions](#cloud-functions)
    - [Firebase Telemetry](#firebase-telemetry)
  - [Evaluations](#evaluations)
    - [Pre-built Evaluators Plugin](#pre-built-evaluators-plugin)
  - [Streaming](#streaming)
  - [Embeddings](#embeddings)
  - [Modules](#modules)
  - [Observability](#observability)
    - [Tracing](#tracing)
    - [Metrics](#metrics)
    - [Usage Tracking](#usage-tracking)
    - [Session Context](#session-context)
  - [Samples](#samples)
    - [Running Samples](#running-samples)
  - [Development](#development)
    - [Prerequisites](#prerequisites)
    - [Installing Genkit CLI](#installing-genkit-cli)
    - [Building](#building)
    - [Running Tests](#running-tests)
    - [Running Samples](#running-samples-1)
  - [CLI Integration](#cli-integration)
  - [Dev UI](#dev-ui)
  - [Architecture](#architecture)
  - [License](#license)

<!-- /TOC -->

## Installation

Add the following dependencies to your Maven `pom.xml`:

```xml
<!-- Core Genkit framework -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- OpenAI plugin (models and embeddings) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-openai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Google GenAI plugin (Gemini models and Imagen) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-google-genai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Anthropic plugin (Claude models) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-anthropic</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Ollama plugin (local models) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-ollama</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- XAI plugin (Grok models) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-xai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- DeepSeek plugin (DeepSeek models) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-deepseek</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Cohere plugin (Command models) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-cohere</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Mistral plugin (Mistral AI models) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-mistral</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Groq plugin (ultra-fast inference) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-groq</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- compat-oai plugin (any OpenAI-compatible endpoint) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-compat-oai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- HTTP server plugin with Jetty -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-jetty</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- HTTP server plugin with Spring Boot -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-spring</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Local vector store plugin (for RAG development) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-localvec</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- MCP plugin (Model Context Protocol) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-mcp</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Firebase plugin (Firestore vector search, Cloud Functions, telemetry) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-firebase</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Weaviate plugin (vector database) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-weaviate</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- PostgreSQL plugin (pgvector) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-postgresql</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Pinecone plugin (vector database) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-pinecone</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Evaluators plugin (RAGAS-style metrics) -->
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-evaluators</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.GenerationConfig;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

public class Main {
    public static void main(String[] args) {
        // Create Genkit with plugins
        Genkit genkit = Genkit.builder()
            .options(GenkitOptions.builder()
                .devMode(true)
                .reflectionPort(3100)
                .build())
            .plugin(OpenAIPlugin.create())
            .plugin(new JettyPlugin(JettyPluginOptions.builder()
                .port(8080)
                .build()))
            .build();

        // Generate text
        ModelResponse response = genkit.generate(
            GenerateOptions.builder()
                .model("openai/gpt-4o-mini")
                .prompt("Tell me a fun fact!")
                .config(GenerationConfig.builder()
                    .temperature(0.9)
                    .maxOutputTokens(200)
                    .build())
                .build());

        System.out.println(response.getText());
    }
}
```

## Defining Flows

Flows are observable, traceable AI workflows that can be exposed as HTTP endpoints:

```java
// Simple flow with typed input/output
Flow<String, String, Void> greetFlow = genkit.defineFlow(
    "greeting",
    String.class,
    String.class,
    name -> "Hello, " + name + "!");

// AI-powered flow with context access
Flow<String, String, Void> jokeFlow = genkit.defineFlow(
    "tellJoke",
    String.class,
    String.class,
    (ctx, topic) -> {
        ModelResponse response = genkit.generate(
            GenerateOptions.builder()
                .model("openai/gpt-4o-mini")
                .prompt("Tell me a short, funny joke about: " + topic)
                .build());
        return response.getText();
    });

// Run a flow programmatically
String result = genkit.runFlow("greeting", "World");
```

## Using Tools

Define tools that models can call during generation:

```java
@SuppressWarnings("unchecked")
Tool<Map<String, Object>, Map<String, Object>> weatherTool = genkit.defineTool(
    "getWeather",
    "Gets the current weather for a location",
    Map.of(
        "type", "object",
        "properties", Map.of(
            "location", Map.of("type", "string", "description", "The city name")
        ),
        "required", new String[]{"location"}
    ),
    (Class<Map<String, Object>>) (Class<?>) Map.class,
    (ctx, input) -> {
        String location = (String) input.get("location");
        return Map.of(
            "location", location,
            "temperature", "72°F",
            "conditions", "sunny"
        );
    });

// Use tool in generation - tool execution is handled automatically
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("What's the weather in Paris?")
        .tools(List.of(weatherTool))
        .build());
```

## DotPrompt Support

Load and use `.prompt` files with Handlebars templating:

```java
// Load a prompt from resources/prompts/recipe.prompt
ExecutablePrompt<RecipeInput> recipePrompt = genkit.prompt("recipe", RecipeInput.class);

// Execute with typed input
ModelResponse response = recipePrompt.generate(new RecipeInput("pasta carbonara"));

// Prompts support variants (e.g., recipe.robot.prompt)
ExecutablePrompt<RecipeInput> robotPrompt = genkit.prompt("recipe", RecipeInput.class, "robot");
```

## Structured Output

Generate type-safe outputs with automatic JSON schema generation using Jackson annotations:

```java
// Define your output class with annotations
public class MenuItem {
  @JsonProperty(required = true)
  @JsonPropertyDescription("The name of the menu item")
  private String name;
  
  @JsonProperty(required = true)
  @JsonPropertyDescription("A detailed description")
  private String description;
  
  @JsonProperty(required = true)
  @JsonPropertyDescription("Price in dollars")
  private double price;
  
  @JsonPropertyDescription("Preparation time in minutes")
  private int prepTimeMinutes;
  
  @JsonPropertyDescription("Dietary information (e.g., vegan, gluten-free)")
  private List<String> dietaryInfo;
  
  // getters/setters...
}

// Generate with structured output - returns typed object
MenuItem item = genkit.generate(
    GenerateOptions.<MenuItem>builder()
        .model("openai/gpt-4o-mini")
        .prompt("Suggest a fancy French menu item")
        .outputClass(MenuItem.class)
        .build()
);

// Works with flows too - fully type-safe
genkit.defineFlow(
    "generateMenuItem",
    MenuItemRequest.class,
    MenuItem.class,
    (ctx, request) -> {
        return genkit.generate(
            GenerateOptions.<MenuItem>builder()
                .model("openai/gpt-4o-mini")
                .prompt(request.getDescription())
                .outputClass(MenuItem.class)
                .build()
        );
    }
);

// Works with DotPrompt
ExecutablePrompt<DishRequest> prompt = genkit.prompt("italian-dish", DishRequest.class);
MenuItem dish = prompt.generate(new DishRequest("Italian"), MenuItem.class);

// Works with tools
Tool<RecipeRequest, MenuItem> recipeGen = genkit.defineTool(
    "generateRecipe",
    "Generates a recipe",
    (ctx, request) -> new MenuItem(...),
    RecipeRequest.class,
    MenuItem.class
);
```

See [samples/structured-output](samples/structured-output) for complete examples.

## RAG (Retrieval Augmented Generation)

Build RAG applications with retrievers and indexers:

```java
// Define a retriever
Retriever myRetriever = genkit.defineRetriever("myStore/docs", (ctx, request) -> {
    List<Document> docs = findSimilarDocs(request.getQuery());
    return new RetrieverResponse(docs);
});

// Define an indexer
Indexer myIndexer = genkit.defineIndexer("myStore/docs", (ctx, request) -> {
    indexDocuments(request.getDocuments());
    return new IndexerResponse();
});

// Index documents
List<Document> docs = List.of(
    Document.fromText("Paris is the capital of France."),
    Document.fromText("Berlin is the capital of Germany.")
);
genkit.index("myStore/docs", docs);

// Retrieve and generate
List<Document> relevantDocs = genkit.retrieve("myStore/docs", "What is the capital of France?");
ModelResponse response = genkit.generate(GenerateOptions.builder()
    .model("openai/gpt-4o-mini")
    .prompt("Answer based on context: What is the capital of France?")
    .docs(relevantDocs)
    .build());
```

## Firebase Integration

The Firebase plugin provides Firestore vector search, Cloud Functions integration, and Google Cloud telemetry:

### Firestore Vector Search

```java
import com.google.genkit.plugins.firebase.FirebasePlugin;
import com.google.genkit.plugins.firebase.retriever.FirestoreRetrieverConfig;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(FirebasePlugin.builder()
        .projectId("my-project")
        .enableTelemetry(true)
        .addRetriever(FirestoreRetrieverConfig.builder()
            .name("my-docs")
            .collection("documents")
            .embedderName("googleai/text-embedding-004")
            .vectorField("embedding")
            .contentField("content")
            .distanceMeasure(FirestoreRetrieverConfig.DistanceMeasure.COSINE)
            .defaultLimit(5)
            .build())
        .build())
    .build();

// Index documents (embeddings generated automatically)
List<Document> docs = List.of(
    Document.fromText("Genkit is a framework for building AI apps"),
    Document.fromText("Firebase provides cloud services for apps")
);
genkit.index("firebase/my-docs", docs);

// Retrieve with vector similarity search
List<Document> results = genkit.retrieve("firebase/my-docs", "What is Genkit?");
```

### Cloud Functions

Expose Genkit flows as Firebase Cloud Functions. The `FirebasePlugin` is optional for simple functions that don't need Firestore:

```java
import com.google.genkit.plugins.firebase.functions.OnCallGenkit;

public class MyFunction implements HttpFunction {
    private final OnCallGenkit genkitFunction;

    public MyFunction() {
        Genkit genkit = Genkit.builder()
            .plugin(GoogleGenAIPlugin.create(System.getenv("GEMINI_API_KEY")))
            .plugin(FirebasePlugin.builder().build())  // Optional - no project ID needed
            .build();

        genkit.defineFlow("generatePoem", String.class, String.class, (ctx, topic) -> {
            return genkit.generate(GenerateOptions.builder()
                .model("googleai/gemini-2.0-flash")
                .prompt("Write a poem about: " + topic)
                .build()).getText();
        });

        this.genkitFunction = OnCallGenkit.fromFlow(genkit, "generatePoem");
    }

    @Override
    public void service(HttpRequest request, HttpResponse response) throws IOException {
        genkitFunction.service(request, response);
    }
}
```

### Firebase Telemetry

Enable automatic export to Google Cloud observability:

```java
FirebasePlugin.builder()
    .projectId("my-project")
    .enableTelemetry(true)      // Export to Cloud Trace, Monitoring, Logging
    .forceDevExport(true)       // Also export in dev mode
    .build()
```

## Evaluations

Define custom evaluators to assess AI output quality:

```java
genkit.defineEvaluator("accuracyCheck", "Accuracy Check", "Checks factual accuracy",
    (dataPoint, options) -> {
        double score = calculateAccuracyScore(dataPoint.getOutput());
        return EvalResponse.builder()
            .testCaseId(dataPoint.getTestCaseId())
            .evaluation(Score.builder().score(score).build())
            .build();
    });

// Run evaluation
EvalRunKey result = genkit.evaluate(RunEvaluationRequest.builder()
    .datasetId("my-dataset")
    .evaluators(List.of("accuracyCheck"))
    .actionRef("/flow/myFlow")
    .build());
```

### Pre-built Evaluators Plugin

Use the evaluators plugin for RAGAS-style metrics without writing custom evaluators:

```java
import com.google.genkit.plugins.evaluators.EvaluatorsPlugin;
import com.google.genkit.plugins.evaluators.EvaluatorsPluginOptions;
import com.google.genkit.plugins.evaluators.GenkitMetric;

Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(EvaluatorsPlugin.create(
        EvaluatorsPluginOptions.builder()
            .judge("openai/gpt-4o-mini")  // LLM for judging
            .metrics(List.of(
                GenkitMetric.FAITHFULNESS,      // Factual accuracy against context
                GenkitMetric.ANSWER_RELEVANCY,  // Answer pertains to question
                GenkitMetric.ANSWER_ACCURACY,   // Matches reference answer
                GenkitMetric.MALICIOUSNESS,     // Detects harmful content
                GenkitMetric.REGEX,             // Pattern matching
                GenkitMetric.DEEP_EQUAL,        // JSON deep equality
                GenkitMetric.JSONATA            // JSONata expressions
            ))
            .build()))
    .build();
```

## Streaming

Generate responses with streaming for real-time output:

```java
StringBuilder result = new StringBuilder();
ModelResponse response = genkit.generateStream(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Tell me a story")
        .build(),
    chunk -> {
        System.out.print(chunk.getText());
        result.append(chunk.getText());
    });
```

## Embeddings

Generate vector embeddings for semantic search:

```java
List<Document> documents = List.of(
    Document.fromText("Hello world"),
    Document.fromText("Goodbye world")
);
EmbedResponse response = genkit.embed("openai/text-embedding-3-small", documents);
```

## Modules

| Module | Description |
|--------|-------------|
| **genkit-core** | Core framework: actions, flows, registry, tracing (OpenTelemetry) |
| **genkit-ai** | AI abstractions: models, embedders, tools, prompts, retrievers, indexers, evaluators |
| **genkit** | Main entry point combining core and AI with reflection server |
| **plugins/openai** | OpenAI models (GPT-4o, GPT-4o-mini, etc.) and embeddings |
| **plugins/google-genai** | Google Gemini models and Imagen image generation |
| **plugins/anthropic** | Anthropic Claude models (Claude 4.5, Claude 4, Claude 3) |
| **plugins/aws-bedrock** | AWS Bedrock models (Amazon Nova, Claude, Llama, Mistral, etc.) |
| **plugins/azure-foundry** | Azure AI Foundry models (GPT-4, Llama, Mistral, Cohere, etc.) |
| **plugins/ollama** | Local Ollama models (Gemma, Llama, Mistral, etc.) |
| **plugins/jetty** | HTTP server plugin using Jetty 12 |
| **plugins/spring** | HTTP server plugin using Spring Boot |
| **plugins/localvec** | Local file-based vector store for development |
| **plugins/mcp** | Model Context Protocol (MCP) client integration |
| **plugins/firebase** | Firebase integration: Firestore vector search, Cloud Functions, telemetry |
| **plugins/weaviate** | Weaviate vector database for RAG applications |
| **plugins/postgresql** | PostgreSQL with pgvector for vector similarity search |
| **plugins/pinecone** | Pinecone managed vector database for RAG applications |
| **plugins/evaluators** | Pre-built RAGAS-style evaluators (faithfulness, relevancy, etc.) |


## Observability

Genkit Java SDK provides comprehensive observability features through OpenTelemetry integration:

### Tracing

All actions (models, tools, flows) are automatically traced with rich metadata:

- **Span types**: `action`, `flow`, `flowStep`, `util`
- **Subtypes**: `model`, `tool`, `flow`, `embedder`, etc.
- **Session tracking**: `sessionId` and `threadName` for multi-turn conversations
- **Input/Output capture**: Full request/response data in span attributes

Example span attributes:
```
genkit:name = "openai/gpt-4o-mini"
genkit:type = "action"
genkit:metadata:subtype = "model"
genkit:path = "/{myFlow,t:flow}/{openai/gpt-4o-mini,t:action,s:model}"
genkit:input = {...}
genkit:output = {...}
genkit:sessionId = "user-123"
```

### Metrics

The SDK exposes OpenTelemetry metrics for monitoring:

| Metric | Description |
|--------|-------------|
| `genkit/ai/generate/requests` | Model generation request count |
| `genkit/ai/generate/latency` | Model generation latency (ms) |
| `genkit/ai/generate/input/tokens` | Input token count |
| `genkit/ai/generate/output/tokens` | Output token count |
| `genkit/ai/generate/input/characters` | Input character count |
| `genkit/ai/generate/output/characters` | Output character count |
| `genkit/ai/generate/input/images` | Input image count |
| `genkit/ai/generate/output/images` | Output image count |
| `genkit/ai/generate/thinking/tokens` | Thinking/reasoning token count |
| `genkit/tool/requests` | Tool execution request count |
| `genkit/tool/latency` | Tool execution latency (ms) |
| `genkit/feature/requests` | Feature (flow) request count |
| `genkit/feature/latency` | Feature (flow) latency (ms) |
| `genkit/action/requests` | General action request count |
| `genkit/action/latency` | General action latency (ms) |

### Usage Tracking

Model responses include detailed usage statistics:

```java
ModelResponse response = genkit.generate(options);
Usage usage = response.getUsage();

System.out.println("Input tokens: " + usage.getInputTokens());
System.out.println("Output tokens: " + usage.getOutputTokens());
System.out.println("Latency: " + response.getLatencyMs() + "ms");
```

### Session Context

Track multi-turn conversations with session and thread context:

```java
ActionContext ctx = ActionContext.builder()
    .registry(genkit.getRegistry())
    .sessionId("user-123")
    .threadName("support-chat")
    .build();
```

## Samples

The following samples are available in `java/samples/`. See the [samples README](./samples/README.md) for detailed instructions on running each sample.

| Sample | Description |
|--------|-------------|
| **openai** | Basic OpenAI integration with flows and tools |
| **google-genai** | Google Gemini integration with image generation |
| **anthropic** | Anthropic Claude integration with streaming |
| **ollama** | Local Ollama models with Gemma 3n |
| **dotprompt** | DotPrompt files with complex inputs/outputs, variants, and partials |
| **rag** | RAG application with local vector store |
| **chat-session** | Multi-turn chat with session persistence |
| **evaluations** | Custom evaluators and evaluation workflows |
| **evaluators-plugin** | Pre-built RAGAS-style evaluators plugin demo |
| **complex-io** | Complex nested types, arrays, maps in flow inputs/outputs |
| **middleware** | Middleware patterns for logging, caching, rate limiting |
| **multi-agent** | Multi-agent orchestration patterns |
| **interrupts** | Flow interrupts and human-in-the-loop patterns |
| **mcp** | Model Context Protocol (MCP) integration |
| **firebase** | Firebase integration with Firestore RAG and Cloud Functions |
| **spring** | Spring Boot HTTP server integration |
| **weaviate** | Weaviate vector database RAG sample |
| **postgresql** | PostgreSQL pgvector RAG sample |
| **pinecone** | Pinecone vector database RAG sample |

### Running Samples

```bash
# Set your API key
export OPENAI_API_KEY=your-api-key
# Or: export GOOGLE_GENAI_API_KEY=your-api-key

# Navigate to a sample and run
cd java/samples/openai
./run.sh

# Or with Genkit Dev UI
genkit start -- ./run.sh
```

## Development

### Prerequisites

- Java 21+
- Maven 3.6+
- OpenAI API key or Google GenAI API key (for samples)
- Genkit CLI (optional, for Dev UI)

### Installing Genkit CLI

```bash
npm install -g genkit
```

### Building

```bash
cd java
mvn clean install
```

### Running Tests

```bash
mvn test
```

### Running Samples

See the [samples README](./samples/README.md) for detailed instructions.

```bash
# Set your API key
export OPENAI_API_KEY=your-api-key
# Or: export GOOGLE_GENAI_API_KEY=your-api-key

# Run a sample
cd java/samples/openai
./run.sh
# Or: mvn compile exec:java

# Run with Genkit Dev UI (recommended)
genkit start -- ./run.sh
```

## CLI Integration

The Java implementation works with the Genkit CLI. Start your application with:

```bash
genkit start -- ./run.sh
# Or: genkit start -- mvn exec:java
```

The reflection server starts automatically in dev mode (`devMode(true)`).

## Dev UI

When running in dev mode, Genkit starts a reflection server on port 3100 (configurable via `reflectionPort()`).
The Dev UI connects to this server to:

- List all registered actions (flows, models, tools, prompts, retrievers, evaluators)
- Run actions with test inputs
- View traces and execution logs
- Manage datasets and run evaluations

## Architecture

```
com.google.genkit
├── core/                    # Core framework
│   ├── Action               # Base action interface
│   ├── ActionDef            # Action implementation
│   ├── ActionContext        # Execution context with registry access
│   ├── Flow                 # Flow definition
│   ├── Registry             # Action registry
│   ├── Plugin               # Plugin interface
│   └── tracing/             # OpenTelemetry integration
│       ├── Tracer           # Span management
│       └── TelemetryClient  # Telemetry export
├── ai/                      # AI features
│   ├── Model                # Model interface
│   ├── ModelRequest/Response# Model I/O types
│   ├── Tool                 # Tool definition
│   ├── Embedder             # Embedder interface
│   ├── Retriever            # Retriever interface
│   ├── Indexer              # Indexer interface
│   ├── Prompt               # Prompt templates
│   ├── telemetry/           # AI-specific metrics
│   │   ├── GenerateTelemetry# Model generation metrics
│   │   ├── ToolTelemetry    # Tool execution metrics
│   │   ├── ActionTelemetry  # Action execution metrics
│   │   ├── FeatureTelemetry # Flow/feature metrics
│   │   └── ModelTelemetryHelper # Telemetry helper
│   └── evaluation/          # Evaluation framework
│       ├── Evaluator        # Evaluator definition
│       ├── EvaluationManager# Run evaluations
│       └── DatasetStore     # Dataset management
├── genkit/                  # Main module
│   ├── Genkit               # Main entry point & builder
│   ├── GenkitOptions        # Configuration options
│   ├── ReflectionServer     # Dev UI integration
│   └── prompt/              # DotPrompt support
│       ├── DotPrompt        # Prompt file parser
│       └── ExecutablePrompt # Prompt execution
└── plugins/                 # Plugin implementations
    ├── openai/              # OpenAI models & embeddings
    ├── google-genai/        # Google Gemini models & Imagen
    ├── anthropic/           # Anthropic Claude models
    ├── ollama/              # Local Ollama models
    ├── jetty/               # Jetty HTTP server
    ├── spring/              # Spring Boot HTTP server
    ├── localvec/            # Local vector store
    ├── mcp/                 # Model Context Protocol client
    ├── firebase/            # Firebase: Firestore RAG, Cloud Functions, telemetry
    ├── weaviate/            # Weaviate vector database
    ├── postgresql/          # PostgreSQL with pgvector
    ├── pinecone/            # Pinecone vector database
    └── evaluators/          # Pre-built RAGAS-style evaluators
```

## License

Apache License 2.0
