# Genkit Groq Plugin

Groq model plugin for [Genkit Java](https://genkit.dev).

This plugin provides integration with Groq's ultra-fast LLM inference.

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Supported Models

### Text Generation (Production)
- `llama-3.1-8b-instant` - Ultra-fast Meta Llama 3.1 8B (~560 tokens/sec)
- `llama-3.3-70b-versatile` - Latest Meta Llama 3.3 70B (~280 tokens/sec)
- `openai/gpt-oss-120b` - OpenAI GPT-OSS 120B with reasoning (~500 tokens/sec)
- `openai/gpt-oss-20b` - OpenAI GPT-OSS 20B (~1000 tokens/sec)

### Content Moderation
- `meta-llama/llama-guard-4-12b` - Content moderation model (~1200 tokens/sec)

## Using Custom Models

If you need to use a model not in the default list (e.g., a newer model release), register it using `customModel()`:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.groq.GroqPlugin;

// Register custom model
Genkit genkit = Genkit.builder()
    .plugin(GroqPlugin.create()
        .customModel("llama-4-90b-preview"))  // Future model example
    .build();

// Use your custom model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("groq/llama-4-90b-preview")
        .prompt("Hello from custom model!")
        .build());
```

> **Note**: The model name must be a valid Groq model identifier. Check the [Groq Models documentation](https://console.groq.com/docs/models) for available models.

## Usage

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.groq.GroqPlugin;

// Create Genkit with Groq plugin
Genkit genkit = Genkit.builder()
    .plugin(GroqPlugin.create())
    .build();

// Use the model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("groq/llama-3.3-70b-versatile")
        .prompt("Explain artificial intelligence")
        .build()
);
```

## Configuration

Set the `GROQ_API_KEY` environment variable:

```bash
export GROQ_API_KEY=your-api-key-here
```

Or provide it programmatically:

```java
GroqPlugin plugin = GroqPlugin.create("your-api-key-here");
```
