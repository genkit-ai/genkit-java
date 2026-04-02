---
name: building-ai-apps-with-genkit-java
description: Guide for building AI-powered Java applications using the Genkit Java framework. Use this skill when the user wants to create a new AI app, add AI features to an existing Java project, define flows, call models, use tools, build RAG pipelines, manage prompts, handle structured output, set up multi-turn chat, create agents, run evaluations, or deploy with Genkit Java. Covers all supported providers (OpenAI, Google Gemini, Anthropic, Ollama, AWS Bedrock, Azure, and more).
argument-hint: Describe what you want to build (e.g., "a chatbot with RAG", "a Spring Boot app with Gemini", "structured output with OpenAI")
---

# Building AI Applications with Genkit Java

You are helping a developer build AI-powered applications using **Genkit Java**, the open-source Java AI framework by Google. This skill covers everything an end user needs: setup, configuration, all APIs, providers, patterns, and deployment.

---

## Quick Start

### Prerequisites

- **Java 21+**
- **Maven**
- **API key** for your chosen provider (OpenAI, Google, Anthropic, etc.)
- **Genkit CLI** (optional, for Dev UI): `npm install -g genkit`

### Minimal pom.xml

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>my-ai-app</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <genkit.version>1.0.0-SNAPSHOT</genkit.version>
    </properties>

    <dependencies>
        <!-- Genkit core -->
        <dependency>
            <groupId>com.google.genkit</groupId>
            <artifactId>genkit</artifactId>
            <version>${genkit.version}</version>
        </dependency>

        <!-- Pick a model provider (see Provider Setup below) -->
        <dependency>
            <groupId>com.google.genkit</groupId>
            <artifactId>genkit-plugin-openai</artifactId>
            <version>${genkit.version}</version>
        </dependency>

        <!-- HTTP server (pick one) -->
        <dependency>
            <groupId>com.google.genkit</groupId>
            <artifactId>genkit-plugin-jetty</artifactId>
            <version>${genkit.version}</version>
        </dependency>

        <!-- Logging -->
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>1.5.32</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <configuration>
                    <mainClass>com.example.MyApp</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### Minimal Application

```java
package com.example;

import com.google.genkit.Genkit;
import com.google.genkit.core.GenkitOptions;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.GenerationConfig;
import com.google.genkit.ai.model.ModelResponse;
import com.google.genkit.core.flow.Flow;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

public class MyApp {
    public static void main(String[] args) throws Exception {
        JettyPlugin jetty = new JettyPlugin(
            JettyPluginOptions.builder().port(8080).build());

        Genkit genkit = Genkit.builder()
            .options(GenkitOptions.builder()
                .devMode(true)
                .reflectionPort(3100)
                .build())
            .plugin(OpenAIPlugin.create())
            .plugin(jetty)
            .build();

        genkit.defineFlow("ask", String.class, String.class,
            (ctx, question) -> genkit.generate(
                GenerateOptions.builder()
                    .model("openai/gpt-4o-mini")
                    .prompt(question)
                    .build()).getText());

        genkit.init();
    }
}
```

### Run It

```bash
export OPENAI_API_KEY=sk-...
mvn compile exec:java

# Or with Dev UI (recommended)
genkit start -- mvn compile exec:java
```

### Test It

```bash
curl -X POST http://localhost:8080/ask \
  -H 'Content-Type: application/json' \
  -d '"What is the capital of France?"'
```

---

## Provider Setup

### Maven Artifacts (all `com.google.genkit`, version `1.0.0-SNAPSHOT`)

| Provider | Artifact | Env Var | Plugin Init |
|----------|----------|---------|-------------|
| OpenAI | `genkit-plugin-openai` | `OPENAI_API_KEY` | `OpenAIPlugin.create()` |
| Google Gemini | `genkit-plugin-google-genai` | `GOOGLE_GENAI_API_KEY` | `GoogleGenAIPlugin.create()` |
| Anthropic | `genkit-plugin-anthropic` | `ANTHROPIC_API_KEY` | `AnthropicPlugin.create()` |
| Ollama | `genkit-plugin-ollama` | none (local) | `OllamaPlugin.create("gemma3n:e4b")` |
| AWS Bedrock | `genkit-plugin-aws-bedrock` | AWS credentials | `AwsBedrockPlugin.create("us-east-1")` |
| Azure Foundry | `genkit-plugin-azure-foundry` | Azure credentials | `AzureFoundryPlugin.create()` |
| DeepSeek | `genkit-plugin-deepseek` | `DEEPSEEK_API_KEY` | `DeepSeekPlugin.create()` |
| Mistral | `genkit-plugin-mistral` | `MISTRAL_API_KEY` | `MistralPlugin.create()` |
| Groq | `genkit-plugin-groq` | `GROQ_API_KEY` | `GroqPlugin.create()` |
| Cohere | `genkit-plugin-cohere` | `COHERE_API_KEY` | `CoherePlugin.create()` |
| xAI | `genkit-plugin-xai` | `XAI_API_KEY` | `XAIPlugin.create()` |
| Any OpenAI-compatible | `genkit-plugin-compat-oai` | varies | `CompatOAIPlugin.create(options)` |

### Model Names by Provider

```
# OpenAI
openai/gpt-4o, openai/gpt-4o-mini, openai/gpt-4-turbo, openai/gpt-3.5-turbo
openai/o1-preview, openai/o1-mini
openai/text-embedding-3-small, openai/text-embedding-3-large
openai/dall-e-3, openai/dall-e-2, openai/gpt-image-1

# Google Gemini
googleai/gemini-2.0-flash, googleai/gemini-1.5-pro, googleai/gemini-1.5-flash
googleai/text-embedding-004, googleai/imagen-3.0-generate-002

# Anthropic
anthropic/claude-sonnet-4-5-20250929, anthropic/claude-opus-4-5-20251101
anthropic/claude-haiku-4-5-20251001
anthropic/claude-opus-4-1, anthropic/claude-sonnet-4

# Ollama (any model you have pulled)
ollama/gemma3n:e4b, ollama/llama3, ollama/mistral

# AWS Bedrock
aws-bedrock/amazon.nova-lite-v1:0, aws-bedrock/amazon.nova-pro-v1:0
aws-bedrock/anthropic.claude-sonnet-4-5-20250929-v1:0
aws-bedrock/meta.llama3-2-90b-instruct-v1:0
```

### Passing API Keys Programmatically

```java
// Instead of environment variables
OpenAIPlugin.create("sk-your-key-here")
AnthropicPlugin.create("sk-ant-your-key-here")
GoogleGenAIPlugin.create("AIza...")
```

---

## Server Options

### Jetty (Lightweight)

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-jetty</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
JettyPlugin jetty = new JettyPlugin(
    JettyPluginOptions.builder().port(8080).build());

// Flows are exposed at: POST http://localhost:8080/{flowName}
```

### Spring Boot

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-spring</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
// Flows exposed at: POST http://localhost:8080/api/flows/{flowName}
// Health check: GET http://localhost:8080/health
// List flows: GET http://localhost:8080/api/flows
```

---

## Defining Flows

Flows are observable, HTTP-callable functions that form the backbone of your app.

### Simple Flow (no AI context needed)

```java
genkit.defineFlow("greet", String.class, String.class,
    (name) -> "Hello, " + name + "!");
```

### Flow with AI Generation

```java
genkit.defineFlow("summarize", String.class, String.class,
    (ctx, text) -> genkit.generate(
        GenerateOptions.builder()
            .model("openai/gpt-4o-mini")
            .prompt("Summarize this: " + text)
            .build()).getText());
```

### Flow with Custom Input/Output Types

```java
public record TranslateInput(String text, String targetLanguage) {}
public record TranslateOutput(String translation, String detectedLanguage) {}

genkit.defineFlow("translate", TranslateInput.class, TranslateOutput.class,
    (ctx, input) -> {
        ModelResponse response = genkit.generate(
            GenerateOptions.<TranslateOutput>builder()
                .model("openai/gpt-4o")
                .prompt("Translate to " + input.targetLanguage() + ": " + input.text())
                .outputClass(TranslateOutput.class)
                .build());
        return response.getOutput();
    });
```

### Flow with Middleware

```java
genkit.defineFlow("secured", String.class, String.class,
    (ctx, input) -> processInput(input),
    List.of(loggingMiddleware, authMiddleware));
```

---

## Generation API

### Basic Text Generation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Explain quantum computing in simple terms")
        .build());

String text = response.getText();
```

### With Configuration

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Write a creative poem")
        .config(GenerationConfig.builder()
            .temperature(0.9)
            .maxOutputTokens(2048)
            .topP(0.95)
            .topK(40)
            .stopSequences(List.of("\n\n\n"))
            .build())
        .build());
```

### System Prompt

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .system("You are a pirate. Respond in pirate speak.")
        .prompt("How do I cook pasta?")
        .build());
```

### Multi-Turn Conversation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .messages(List.of(
            Message.system("You are a helpful math tutor."),
            Message.user("What is 2+2?"),
            Message.model("2+2 equals 4."),
            Message.user("What about 2+2+2?")))
        .build());
```

### Streaming

```java
ModelResponse response = genkit.generateStream(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Write a long story about a dragon")
        .build(),
    chunk -> System.out.print(chunk.getText()));  // Print as it arrives
```

### Structured Output (JSON)

Use `@JsonProperty` and `@JsonPropertyDescription` for schema generation:

```java
public class Recipe {
    @JsonProperty(required = true)
    @JsonPropertyDescription("Name of the recipe")
    private String title;

    @JsonProperty(required = true)
    @JsonPropertyDescription("List of ingredients with quantities")
    private List<Ingredient> ingredients;

    @JsonProperty(required = true)
    @JsonPropertyDescription("Step-by-step instructions")
    private List<String> steps;

    @JsonPropertyDescription("Preparation time in minutes")
    private int prepTime;

    // getters and setters
}

Recipe recipe = genkit.generate(
    GenerateOptions.<Recipe>builder()
        .model("openai/gpt-4o")
        .prompt("Create a pasta carbonara recipe")
        .outputClass(Recipe.class)
        .build());
```

### Token Usage

```java
ModelResponse response = genkit.generate(...);
Usage usage = response.getUsage();
System.out.println("Input tokens: " + usage.getInputTokens());
System.out.println("Output tokens: " + usage.getOutputTokens());
```

---

## Tools (Function Calling)

Let models call your Java functions:

```java
public record WeatherInput(String location) {}
public record WeatherOutput(String temperature, String conditions) {}

Tool<WeatherInput, WeatherOutput> weatherTool = genkit.defineTool(
    "getWeather",
    "Get current weather for a location",
    (ctx, input) -> new WeatherOutput("72°F", "Sunny in " + input.location()),
    WeatherInput.class, WeatherOutput.class);

// The model will call the tool automatically when needed
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("What's the weather in London and Tokyo?")
        .tools(List.of(weatherTool))
        .build());
```

### Multiple Tools

```java
Tool<SearchInput, SearchOutput> searchTool = genkit.defineTool(
    "search", "Search the web", searchHandler, SearchInput.class, SearchOutput.class);

Tool<CalcInput, CalcOutput> calcTool = genkit.defineTool(
    "calculate", "Perform math", calcHandler, CalcInput.class, CalcOutput.class);

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Search for the population of France and calculate its density")
        .tools(List.of(searchTool, calcTool))
        .build());
```

---

## DotPrompt — Prompt Files

Manage prompts as files with YAML frontmatter + Handlebars templates.

### Create a Prompt File

Place in `src/main/resources/prompts/recipe.prompt`:

```
---
model: openai/gpt-4o-mini
config:
  temperature: 0.9
  maxOutputTokens: 500
input:
  schema:
    food: string
    ingredients?(array): string
output:
  format: json
  schema:
    title: string, recipe title
    ingredients(array):
      name: string
      quantity: string
    steps(array): string
    prepTime: string
    cookTime: string
    servings: integer
---

You are a chef famous for creative recipes prepared in 45 minutes or less.

Generate a detailed recipe for {{food}}.

{{#if ingredients}}
Make sure to include:
{{#each ingredients}}
- {{this}}
{{/each}}
{{/if}}

Provide the recipe in the exact JSON format specified in the output schema.
```

### Use It in Code

```java
public record RecipeInput(String food, List<String> ingredients) {}

ExecutablePrompt<RecipeInput> recipePrompt = genkit.prompt("recipe", RecipeInput.class);
ModelResponse response = recipePrompt.generate(new RecipeInput("pasta", List.of("tomatoes", "basil")));
```

### Prompt Variants

Create alternate versions of the same prompt:

- `recipe.prompt` — default
- `recipe.robot.prompt` — robot variant

```java
ExecutablePrompt<RecipeInput> robotRecipe = genkit.prompt("recipe", RecipeInput.class, "robot");
```

### Partials (Reusable Fragments)

Create `src/main/resources/prompts/_style.prompt`:

```
You always respond with enthusiasm and use exclamation marks!
```

Reference in other prompts:

```
{{> _style}}
Now generate a recipe for {{food}}.
```

---

## RAG (Retrieval-Augmented Generation)

### Local Development with LocalVec

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-localvec</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(LocalVecPlugin.builder()
        .addStore(LocalVecConfig.builder()
            .indexName("my-docs")
            .embedderName("openai/text-embedding-3-small")
            .directory("/tmp/genkit-vectors")
            .build())
        .build())
    .plugin(jetty)
    .build();

// Index documents
genkit.defineFlow("indexDocs", Void.class, String.class,
    (ctx, input) -> {
        List<Document> docs = List.of(
            Document.fromText("Paris is the capital of France."),
            Document.fromText("Berlin is the capital of Germany."),
            Document.fromText("Tokyo is the capital of Japan."));
        genkit.index("devLocalVectorStore/my-docs", docs);
        return "Indexed " + docs.size() + " documents";
    });

// RAG query
genkit.defineFlow("ragQuery", String.class, String.class,
    (ctx, query) -> {
        List<Document> context = genkit.retrieve("devLocalVectorStore/my-docs", query);
        return genkit.generate(
            GenerateOptions.builder()
                .model("openai/gpt-4o-mini")
                .prompt(query)
                .docs(context)
                .build()).getText();
    });
```

### Production Vector Stores

```xml
<!-- Choose one -->
<artifactId>genkit-plugin-firebase</artifactId>    <!-- Firestore Vector Search -->
<artifactId>genkit-plugin-pinecone</artifactId>     <!-- Pinecone -->
<artifactId>genkit-plugin-postgresql</artifactId>   <!-- pgvector -->
<artifactId>genkit-plugin-weaviate</artifactId>     <!-- Weaviate -->
```

### Embeddings Directly

```java
EmbedResponse embeddings = genkit.embed(
    "openai/text-embedding-3-small",
    List.of(Document.fromText("Hello world")));
```

---

## Sessions & Multi-Turn Chat

```java
Session<MyState> session = genkit.createSession();

Chat<MyState> chat = genkit.chat(ChatOptions.<MyState>builder()
    .model("openai/gpt-4o")
    .session(session)
    .system("You are a helpful assistant.")
    .build());

// Each call maintains conversation history
ModelResponse r1 = chat.send("What is the capital of France?");
ModelResponse r2 = chat.send("What about Germany?");  // Remembers context
```

---

## Agents

Create specialized AI agents that can use tools and delegate to each other:

```java
Tool<ReservationInput, ReservationOutput> reserveTool = genkit.defineTool(
    "makeReservation", "Book a restaurant reservation",
    reserveHandler, ReservationInput.class, ReservationOutput.class);

Tool<MenuInput, MenuOutput> menuTool = genkit.defineTool(
    "getMenu", "Get restaurant menu",
    menuHandler, MenuInput.class, MenuOutput.class);

Agent reservationAgent = genkit.defineAgent(AgentConfig.builder()
    .name("reservationAgent")
    .model("openai/gpt-4o")
    .description("Handles restaurant reservations")
    .tools(List.of(reserveTool))
    .build());

Agent menuAgent = genkit.defineAgent(AgentConfig.builder()
    .name("menuAgent")
    .model("openai/gpt-4o")
    .description("Answers menu questions")
    .tools(List.of(menuTool))
    .build());

// Triage agent routes to specialists
Agent triageAgent = genkit.defineAgent(AgentConfig.builder()
    .name("triageAgent")
    .model("openai/gpt-4o")
    .description("Routes requests to the appropriate specialist")
    .tools(List.of(
        genkit.getAllToolsForAgent(reservationAgent),
        genkit.getAllToolsForAgent(menuAgent)))
    .build());
```

---

## Interrupts (Human-in-the-Loop)

Pause execution and wait for user confirmation:

```java
Tool<ConfirmInput, ConfirmOutput> confirmTool = genkit.defineInterrupt(
    InterruptConfig.<ConfirmInput, ConfirmOutput>builder()
        .name("confirmTransfer")
        .inputClass(ConfirmInput.class)
        .outputClass(ConfirmOutput.class)
        .build());

// In a flow, use ctx.interrupt() to pause
genkit.defineFlow("transfer", TransferRequest.class, String.class,
    (ctx, input) -> {
        if (input.amount() > 1000) {
            ctx.interrupt(InterruptRequest.builder()
                .type("CONFIRMATION")
                .data(Map.of("action", "TRANSFER", "amount", input.amount()))
                .build());
        }
        return performTransfer(input);
    });

// Resume with user response
genkit.generate(GenerateOptions.builder()
    .model("openai/gpt-4o")
    .resume(ResumeOptions.builder()
        .sessionId(sessionId)
        .response(userConfirmation)
        .build())
    .build());
```

---

## Middleware

Add cross-cutting concerns to flows:

```java
// Logging middleware
Middleware<String, String> logger = (input, ctx, next) -> {
    System.out.println("Input: " + input);
    String result = next.handle(input, ctx);
    System.out.println("Output: " + result);
    return result;
};

// Apply to flow
genkit.defineFlow("myFlow", String.class, String.class,
    handler, List.of(logger));
```

---

## MCP (Model Context Protocol)

Connect to MCP tool servers:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-mcp</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
MCPPlugin mcpPlugin = MCPPlugin.create(MCPPluginOptions.builder()
    .addServer(MCPServerConfig.stdio(
        "npx", "@modelcontextprotocol/server-filesystem", "/tmp"))
    .build());

Genkit genkit = Genkit.builder()
    .plugin(mcpPlugin)
    .plugin(OpenAIPlugin.create())
    .build();

// MCP tools are auto-registered and available for generation
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("List files in /tmp")
        .tools(mcpPlugin.getTools())
        .build());

// Access MCP resources
List<MCPResource> resources = mcpPlugin.getResources();
MCPResourceContent content = mcpPlugin.readResource("file:///tmp/data.txt");
```

---

## Evaluations

### Built-in RAGAS Evaluators

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-evaluators</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(EvaluatorsPlugin.create(
        EvaluatorsPluginOptions.builder()
            .judge("openai/gpt-4o-mini")
            .metrics(List.of(
                GenkitMetric.FAITHFULNESS,
                GenkitMetric.ANSWER_RELEVANCY,
                GenkitMetric.ANSWER_ACCURACY,
                GenkitMetric.MALICIOUSNESS))
            .build()))
    .build();
```

Available metrics: `FAITHFULNESS`, `ANSWER_RELEVANCY`, `ANSWER_ACCURACY`, `MALICIOUSNESS`, `REGEX`, `DEEP_EQUAL`, `JSONATA`

### Custom Evaluators

```java
Evaluator<Void> lengthEval = genkit.defineEvaluator(
    "custom/length", "Length Check", "Checks if output meets minimum length",
    (dataPoint, options) -> {
        int len = dataPoint.getOutput().length();
        boolean pass = len >= 100;
        return EvalResponse.builder()
            .score(pass ? 1.0 : 0.0)
            .rationale(pass ? "Adequate length" : "Too short: " + len + " chars")
            .build();
    });
```

### Run Evaluations via Dev UI

Start with `genkit start -- ./run.sh`, open http://localhost:4000, and use the Evaluations tab to create datasets and run evaluations interactively.

---

## Firebase Deployment

### Firestore Vector Search (RAG)

```java
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create())
    .plugin(FirebasePlugin.builder()
        .projectId("my-project")
        .addRetriever(FirestoreRetrieverConfig.builder()
            .name("my-docs")
            .collection("documents")
            .embedderName("googleai/text-embedding-004")
            .vectorField("embedding")
            .contentField("text")
            .build())
        .enableTelemetry(true)  // Cloud Trace + Monitoring + Logging
        .build())
    .build();

List<Document> docs = genkit.retrieve("firebase/my-docs", "search query");
```

### Cloud Functions

```java
public class MyFunction implements HttpFunction {
    private final OnCallGenkit genkitFunction;

    public MyFunction() {
        Genkit genkit = Genkit.builder()
            .plugin(GoogleGenAIPlugin.create(System.getenv("GEMINI_API_KEY")))
            .plugin(FirebasePlugin.builder().build())
            .build();

        genkit.defineFlow("myFlow", String.class, String.class,
            (ctx, input) -> genkit.generate(
                GenerateOptions.builder()
                    .model("googleai/gemini-2.0-flash")
                    .prompt(input)
                    .build()).getText());

        genkit.init();
        this.genkitFunction = OnCallGenkit.fromFlow(genkit, "myFlow");
    }

    @Override
    public void service(HttpRequest request, HttpResponse response) throws IOException {
        genkitFunction.service(request, response);
    }
}
```

---

## Observability

### Built-in Tracing (OpenTelemetry)

Every flow and generation call is automatically traced. In dev mode, traces are visible in the Dev UI at http://localhost:4000.

### Token Metrics

Automatic metrics exported via OpenTelemetry:

- `genkit/ai/generate/requests` — generation call count
- `genkit/ai/generate/latency` — generation latency
- `genkit/ai/generate/input/tokens` — input token count
- `genkit/ai/generate/output/tokens` — output token count
- `genkit/feature/requests` — flow call count
- `genkit/feature/latency` — flow latency

### Production Telemetry (Firebase)

```java
FirebasePlugin.builder()
    .projectId("my-project")
    .enableTelemetry(true)  // Exports to Cloud Trace, Monitoring, Logging
    .build()
```

---

## Dev Workflow

### Development Mode

```java
GenkitOptions.builder()
    .devMode(true)            // Enable reflection server
    .reflectionPort(3100)     // Default port for dev tools
    .build()
```

### Dev UI

```bash
# Install CLI
npm install -g genkit

# Start app with Dev UI
genkit start -- mvn compile exec:java

# Open http://localhost:4000
```

The Dev UI lets you:
- List and test all flows, models, tools, prompts, retrievers, evaluators
- Send test inputs and see outputs
- View full traces with timing and token counts
- Create evaluation datasets and run evaluations
- Inspect registered actions and their schemas

### HTTP Endpoints

```bash
# Jetty
curl -X POST http://localhost:8080/{flowName} \
  -H 'Content-Type: application/json' \
  -d '"your input"'

# With complex input
curl -X POST http://localhost:8080/translate \
  -H 'Content-Type: application/json' \
  -d '{"text": "Hello", "targetLanguage": "Spanish"}'

# Spring Boot
curl http://localhost:8080/health
curl http://localhost:8080/api/flows
curl -X POST http://localhost:8080/api/flows/{flowName} \
  -H 'Content-Type: application/json' \
  -d '"input"'
```

---

## Common Patterns

### Error Handling

```java
import com.google.genkit.core.GenkitException;

genkit.defineFlow("safe", String.class, String.class,
    (ctx, input) -> {
        try {
            return genkit.generate(
                GenerateOptions.builder()
                    .model("openai/gpt-4o")
                    .prompt(input)
                    .build()).getText();
        } catch (GenkitException e) {
            // e.getErrorCode(), e.getDetails(), e.getTraceId()
            return "Error: " + e.getMessage();
        }
    });
```

### Switching Providers

Genkit is provider-agnostic. Change the model string and plugin:

```java
// Switch from OpenAI to Gemini — just change plugin + model name
.plugin(GoogleGenAIPlugin.create())
// ...
.model("googleai/gemini-2.0-flash")

// Switch to Anthropic
.plugin(AnthropicPlugin.create())
// ...
.model("anthropic/claude-sonnet-4-5-20250929")

// Switch to local Ollama
.plugin(OllamaPlugin.create("gemma3n:e4b"))
// ...
.model("ollama/gemma3n:e4b")
```

### Multiple Providers in One App

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(GoogleGenAIPlugin.create())
    .plugin(AnthropicPlugin.create())
    .build();

// Use different models for different tasks
genkit.defineFlow("quickAnswer", String.class, String.class,
    (ctx, q) -> genkit.generate(GenerateOptions.builder()
        .model("openai/gpt-4o-mini").prompt(q).build()).getText());

genkit.defineFlow("deepAnalysis", String.class, String.class,
    (ctx, q) -> genkit.generate(GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929").prompt(q).build()).getText());

genkit.defineFlow("creative", String.class, String.class,
    (ctx, q) -> genkit.generate(GenerateOptions.builder()
        .model("googleai/gemini-2.0-flash").prompt(q).build()).getText());
```

### Custom OpenAI-Compatible Endpoint

```java
.plugin(CompatOAIPlugin.create(CompatOAIPluginOptions.builder()
    .baseUrl("https://my-custom-endpoint.com/v1")
    .apiKey("my-key")
    .models(List.of("my-model"))
    .build()))
```

---

## Project Structure Recommendation

```
my-ai-app/
├── pom.xml
├── run.sh
├── src/main/
│   ├── java/com/example/
│   │   ├── MyApp.java              # Genkit init + flow definitions
│   │   ├── flows/                   # Complex flows in separate classes
│   │   │   ├── RagFlow.java
│   │   │   └── ChatFlow.java
│   │   └── tools/                   # Tool implementations
│   │       ├── SearchTool.java
│   │       └── CalcTool.java
│   └── resources/
│       ├── prompts/                 # .prompt files
│       │   ├── recipe.prompt
│       │   └── recipe.robot.prompt
│       ├── data/                    # RAG source data
│       │   └── knowledge-base.txt
│       └── logback.xml              # Logging config
└── src/test/java/com/example/      # Tests
```

### run.sh

```bash
#!/bin/bash
cd "$(dirname "$0")"
mvn compile exec:java
```
