# Genkit XAI Plugin

XAI (x.ai / Grok) model plugin for [Genkit Java](https://genkit.dev).

This plugin provides integration with XAI (x.ai / Grok) models.

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Supported Models

- `grok-4.3` - Latest flagship model (2M context)
- `grok-4.20-0309-reasoning` - Reasoning mode (2M context)
- `grok-4.20-0309-non-reasoning` - Non-reasoning mode (2M context)
- `grok-4.20-multi-agent-0309` - Multi-agent mode (2M context)
- `grok-build-0.1` - Agentic coding model (256K context)

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
        .model("xai/grok-4.3")
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
