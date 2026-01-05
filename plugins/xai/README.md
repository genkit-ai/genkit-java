# Genkit XAI Plugin

XAI (x.ai / Grok) model plugin for [Genkit Java](https://genkit.dev).

This plugin provides integration with XAI (x.ai / Grok) models.

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Supported Models

- `grok-4` - Latest flagship model (131K context)
- `grok-4-1-fast` - Optimized for agentic tool calling (2M context)
- `grok-3` - Previous generation (131K context)
- `grok-3-mini` - Efficient small model (131K context)

## Using Custom Models

If you need to use a model not in the default list (e.g., a newer model release), register it using `customModel()`:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.xai.XAIPlugin;

// Register custom model
Genkit genkit = Genkit.builder()
    .plugin(XAIPlugin.create()
        .customModel("grok-5"))  // Future model example
    .build();

// Use your custom model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("xai/grok-5")
        .prompt("Hello from custom model!")
        .build());
```

> **Note**: The model name must be a valid XAI model identifier. Check the [XAI documentation](https://docs.x.ai/) for available models.

## Usage

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.xai.XAIPlugin;

// Create Genkit with XAI plugin
Genkit genkit = Genkit.builder()
    .plugin(XAIPlugin.create())
    .build();

// Use the model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("xai/grok-4")
        .prompt("Tell me a joke")
        .build()
);
```

## Configuration

Set the `XAI_API_KEY` environment variable:

```bash
export XAI_API_KEY=your-api-key-here
```

Or provide it programmatically:

```java
XAIPlugin plugin = XAIPlugin.create("your-api-key-here");
```
