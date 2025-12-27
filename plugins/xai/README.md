# Genkit XAI Plugin

This plugin provides XAI (x.ai / Grok) model integrations for Genkit.

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
