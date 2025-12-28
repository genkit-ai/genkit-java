# Genkit DeepSeek Plugin

This plugin provides DeepSeek model integrations for Genkit.

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Supported Models

- `deepseek-chat` - DeepSeek-V3.2 for general chat and tasks (128K context)
- `deepseek-reasoner` - DeepSeek-V3.2 with enhanced reasoning capabilities (128K context)

## Using Custom Models

If you need to use a model not in the default list (e.g., a newer model release), register it using `customModel()`:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.deepseek.DeepSeekPlugin;

// Register custom model
Genkit genkit = Genkit.builder()
    .plugin(DeepSeekPlugin.create()
        .customModel("deepseek-v4"))  // Future model example
    .build();

// Use your custom model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("deepseek/deepseek-v4")
        .prompt("Hello from custom model!")
        .build());
```

> **Note**: The model name must be a valid DeepSeek model identifier. Check the [DeepSeek API documentation](https://api-docs.deepseek.com/) for available models.

## Usage

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.deepseek.DeepSeekPlugin;

// Create Genkit with DeepSeek plugin
Genkit genkit = Genkit.builder()
    .plugin(DeepSeekPlugin.create())
    .build();

// Use the model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("deepseek/deepseek-chat")
        .prompt("Explain quantum computing")
        .build()
);
```

## Configuration

Set the `DEEPSEEK_API_KEY` environment variable:

```bash
export DEEPSEEK_API_KEY=your-api-key-here
```

Or provide it programmatically:

```java
DeepSeekPlugin plugin = DeepSeekPlugin.create("your-api-key-here");
```
