---
title: Architecture
description: Understand the architecture of Genkit for Java.
---

## Module structure

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
│   └── evaluation/          # Evaluation framework
├── genkit/                  # Main module
│   ├── Genkit               # Main entry point & builder
│   ├── GenkitOptions        # Configuration options
│   ├── ReflectionServer     # Dev UI integration
│   └── prompt/              # DotPrompt support
└── plugins/                 # Plugin implementations
    ├── openai/              # OpenAI models & embeddings
    ├── google-genai/        # Google Gemini & Imagen
    ├── anthropic/           # Anthropic Claude
    ├── aws-bedrock/         # AWS Bedrock
    ├── azure-foundry/       # Azure AI Foundry
    ├── xai/                 # xAI Grok
    ├── deepseek/            # DeepSeek
    ├── cohere/              # Cohere Command
    ├── mistral/             # Mistral AI
    ├── groq/                # Groq inference
    ├── ollama/              # Local Ollama
    ├── compat-oai/          # OpenAI-compatible base
    ├── jetty/               # Jetty HTTP server
    ├── spring/              # Spring Boot server
    ├── localvec/            # Local vector store
    ├── mcp/                 # Model Context Protocol
    ├── firebase/            # Firebase integration
    ├── weaviate/            # Weaviate vector DB
    ├── postgresql/          # PostgreSQL pgvector
    ├── pinecone/            # Pinecone vector DB
    └── evaluators/          # Pre-built evaluators
```

## Key concepts

### Actions

Actions are the fundamental building blocks. Every model call, tool invocation, flow execution, and embedding operation is an action. Actions provide:

- Consistent input/output typing
- Automatic tracing with OpenTelemetry
- Registration in the Genkit registry
- Discoverability via the Dev UI

### Registry

The registry tracks all registered actions (flows, models, tools, prompts, retrievers, indexers, evaluators). It enables:

- Dev UI discovery
- CLI integration
- Plugin-based extensibility

### Plugins

Plugins register actions with the Genkit registry. They implement the `Plugin` interface and add models, embedders, tools, or other capabilities.

```java
JettyPlugin jetty = new JettyPlugin(options);

Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(jetty)
    .build();

// Start the server after defining flows
jetty.start();
```
