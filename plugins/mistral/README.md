# Genkit Mistral Plugin

Mistral AI model plugin for [Genkit Java](https://genkit.dev).

This plugin provides integration with Mistral AI models.

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Supported Models

- `mistral-large-2512` - Flagship model (256K context)
- `mistral-medium-2604` - Mistral Medium 3.5 (128K context)
- `mistral-small-2603` - Mistral Small 4 (128K context)
- `magistral-medium-2509` - Reasoning model
- `ministral-3b-2512`, `ministral-8b-2512`, `ministral-14b-2512` - Compact models
- `codestral-2508` - Code generation specialist (256K context)
- `devstral-2512` - Developer/agentic coding model
- `open-mistral-nemo` - Open-source multilingual model

## Embeddings

- `mistral-embed`
- `codestral-embed`

Register additional embedding models with `customEmbeddingModel(...)`.

## Using Custom Models

If you need to use a model not in the default list (e.g., a newer model release), register it using `customModel()`:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.mistral.MistralPlugin;

// Register custom model
Genkit genkit = Genkit.builder()
    .plugin(MistralPlugin.create()
        .customModel("mistral-large-2601"))  // Future model example
    .build();

// Use your custom model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("mistral/mistral-large-2601")
        .prompt("Hello from custom model!")
        .build());
```

> **Note**: The model name must be a valid Mistral model identifier. Check the [Mistral Models documentation](https://docs.mistral.ai/getting-started/models/) for available models.

## Usage

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.mistral.MistralPlugin;

// Create Genkit with Mistral plugin
Genkit genkit = Genkit.builder()
    .plugin(MistralPlugin.create())
    .build();

// Use the model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("mistral/mistral-large-3-25-12")
        .prompt("Explain machine learning")
        .build()
);
```

## Configuration

Set the `MISTRAL_API_KEY` environment variable:

```bash
export MISTRAL_API_KEY=your-api-key-here
```

Or provide it programmatically:

```java
MistralPlugin plugin = MistralPlugin.create("your-api-key-here");
```
