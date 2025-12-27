# Genkit Mistral Plugin

This plugin provides Mistral AI model integrations for Genkit.

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Supported Models

- `mistral-large-3-25-12` - Latest flagship multimodal model (256K context)
- `mistral-medium-3-1-25-08` - Balanced performance (128K context)
- `mistral-small-3-2-25-06` - Efficient and fast (128K context)
- `ministral-3-3b-25-12` - Compact 3B model (128K context)
- `ministral-3-8b-25-12` - Balanced 8B model (128K context)
- `ministral-3-14b-25-12` - Advanced 14B model (128K context)
- `codestral-25-08` - Code generation specialist (256K context)
- `devstral-2-25-12` - Developer-focused model

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
