# Genkit Ollama Plugin

Ollama local model plugin for [Genkit Java](https://github.com/firebase/genkit).

This plugin provides integration with [Ollama](https://ollama.ai), allowing you to run open-source LLMs locally without any API keys.

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-ollama</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Prerequisites

1. Install Ollama from https://ollama.ai
2. Pull a model: `ollama pull gemma3n:e4b`
3. Ensure Ollama is running (it starts automatically after installation)

## Quick Start

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.ollama.OllamaPlugin;

// Create Genkit with Ollama plugin
Genkit genkit = Genkit.builder()
    .plugin(OllamaPlugin.create("gemma3n:e4b"))
    .build();

// Generate text
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("ollama/gemma3n:e4b")
        .prompt("Hello!")
        .build());

System.out.println(response.getText());
```

## Configuration

### Default Configuration

```java
OllamaPlugin plugin = OllamaPlugin.create("gemma3n:e4b");
```

### Custom Host

If Ollama runs on a different host:

```bash
export OLLAMA_HOST=http://your-server:11434
```

Or programmatically:

```java
OllamaPlugin plugin = new OllamaPlugin(
    OllamaPluginOptions.builder()
        .baseUrl("http://your-server:11434")
        .models("gemma3n:e4b")
        .build()
);
```

### Advanced Configuration

```java
OllamaPlugin plugin = new OllamaPlugin(
    OllamaPluginOptions.builder()
        .baseUrl("http://localhost:11434")
        .timeout(300)  // seconds (default: 300)
        .models("gemma3n:e4b")
        .build()
);
```

## Example Model

This plugin works with any Ollama model. We recommend `gemma3n:e4b` (Google Gemma 3n Edge 4B):

```bash
ollama pull gemma3n:e4b
```

## Features

### Text Generation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("ollama/gemma3n:e4b")
        .system("You are a helpful assistant.")
        .prompt("What is the capital of France?")
        .config(GenerationConfig.builder()
            .temperature(0.7)
            .maxOutputTokens(500)
            .build())
        .build());
```

### Streaming

```java
genkit.generateStream(
    GenerateOptions.builder()
        .model("ollama/gemma3n:e4b")
        .prompt("Write a story about space exploration")
        .build(),
    (chunk) -> {
        System.out.print(chunk.getText());
    });
```

### JSON Output

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("ollama/gemma3n:e4b")
        .prompt("List 3 programming languages as JSON"))
        .output(OutputConfig.builder()
            .format(OutputFormat.JSON)
            .build())
        .build());
```

## Generation Config Options

| Option | Type | Ollama Parameter | Description |
|--------|------|------------------|-------------|
| `temperature` | double | `temperature` | Controls randomness (0.0-2.0) |
| `maxOutputTokens` | int | `num_predict` | Maximum tokens to generate |
| `topP` | double | `top_p` | Nucleus sampling threshold |
| `topK` | int | `top_k` | Top-K sampling |
| `stopSequences` | List<String> | `stop` | Stop generation sequences |
| `seed` | int | `seed` | Random seed for reproducibility |
| `repeatPenalty` | double | `repeat_penalty` | Penalize repetition |

## Model Capabilities

| Feature | Support |
|---------|---------|
| Multi-turn chat | ✅ |
| System messages | ✅ |
| Streaming | ✅ |
| JSON output | ✅ |
| Tool use | ❌ |

## Performance Tips

1. **Use GPU** - Ollama automatically uses GPU if available
2. **Choose appropriate model size** - Smaller models are faster
3. **Enable streaming** - Better UX for long responses
4. **Adjust timeout** - Increase for larger models

## Troubleshooting

### Connection refused
```bash
# Check if Ollama is running
ollama list

# Start Ollama if needed
ollama serve
```

### Model not found
```bash
# Pull the required model
ollama pull gemma3n:e4b
```

### Slow responses
- Check GPU usage: `ollama ps`
- Reduce `maxOutputTokens`

### Out of memory
- Use a smaller model
- Close other applications
- Check available RAM/VRAM

## Resources

- [Ollama Documentation](https://github.com/ollama/ollama)
- [Model Library](https://ollama.ai/library)
- [API Reference](https://github.com/ollama/ollama/blob/main/docs/api.md)

## License

Apache 2.0 - See [LICENSE](../../LICENSE) for details.
