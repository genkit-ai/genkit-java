# Genkit Cohere Plugin

This plugin provides Cohere model integrations for Genkit.

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Supported Models

- `command-a-03-2025` - Most performant model, excels at tool use, agents, RAG, multilingual (256K context)
- `command-r7b-12-2024` - Small, fast model for RAG, tool use, and agents (128K context)
- `command-r-08-2024` - Balanced model for complex workflows (128K context)
- `command-r-plus-08-2024` - Enhanced model for complex RAG and multi-step tool use (128K context)

## Using Custom Models

If you need to use a model not in the default list (e.g., a newer model release), register it using `customModel()`:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.cohere.CoherePlugin;

// Register custom model
Genkit genkit = Genkit.builder()
    .plugin(CoherePlugin.create()
        .customModel("command-r-v2"))  // Future model example
    .build();

// Use your custom model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("cohere/command-r-v2")
        .prompt("Hello from custom model!")
        .build());
```

> **Note**: The model name must be a valid Cohere model identifier. Check the [Cohere Models documentation](https://docs.cohere.com/docs/models) for available models.

## Usage

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.cohere.CoherePlugin;

// Create Genkit with Cohere plugin
Genkit genkit = Genkit.builder()
    .plugin(CoherePlugin.create())
    .build();

// Use the model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("cohere/command-a-03-2025")
        .prompt("Explain neural networks")
        .build()
);
```

## Configuration

Set the `COHERE_API_KEY` environment variable:

```bash
export COHERE_API_KEY=your-api-key-here
```

Or provide it programmatically:

```java
CoherePlugin plugin = CoherePlugin.create("your-api-key-here");
```
