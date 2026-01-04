# Genkit Anthropic Plugin

Anthropic Claude model plugin for [Genkit Java](https://genkit.dev).

This plugin provides integration with Anthropic's Claude models, including Claude 4.5 Opus/Sonnet/Haiku, Claude 4, and Claude 3 variants.

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-anthropic</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.anthropic.AnthropicPlugin;

// Create Genkit with Anthropic plugin
Genkit genkit = Genkit.builder()
    .plugin(AnthropicPlugin.create())
    .build();

// Generate text with Claude
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929")
        .prompt("Hello, Claude!")
        .build());

System.out.println(response.getText());
```

## Configuration

### Using Environment Variable (Recommended)

Set `ANTHROPIC_API_KEY` in your environment:

```bash
export ANTHROPIC_API_KEY=your-api-key-here
```

Then create the plugin:

```java
AnthropicPlugin plugin = AnthropicPlugin.create();
```

### Using API Key Directly

```java
AnthropicPlugin plugin = AnthropicPlugin.create("your-api-key");
```

### Advanced Configuration

```java
AnthropicPlugin plugin = new AnthropicPlugin(
    AnthropicPluginOptions.builder()
        .apiKey("your-api-key")
        .baseUrl("https://api.anthropic.com/v1")
        .anthropicVersion("2023-06-01")
        .timeout(120)  // seconds
        .build()
);
```

## Supported Models

### Claude 4.5 Family (Latest)
- `anthropic/claude-opus-4-5-20251101` - Most powerful Claude 4.5
- `anthropic/claude-sonnet-4-5-20250929` - Balanced performance (recommended)
- `anthropic/claude-haiku-4-5-20251001` - Fast and efficient

### Claude 4 Family
- `anthropic/claude-opus-4-1-20250805` - Claude Opus 4.1
- `anthropic/claude-opus-4-20250514` - Claude Opus 4
- `anthropic/claude-sonnet-4-20250514` - Claude Sonnet 4

### Claude 3 Family
- `anthropic/claude-3-7-sonnet-20250219` - Claude Sonnet 3.7
- `anthropic/claude-3-5-haiku-20241022` - Claude Haiku 3.5
- `anthropic/claude-3-opus-20240229` - Claude Opus 3
- `anthropic/claude-3-haiku-20240307` - Claude Haiku 3

## Using Custom Models

If you need to use a model not in the default list (e.g., a newer model release), register it using `customModel()`:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.anthropic.AnthropicPlugin;

// Register custom model
Genkit genkit = Genkit.builder()
    .plugin(AnthropicPlugin.create()
        .customModel("claude-opus-5-20260101"))  // Future model example
    .build();

// Use your custom model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-opus-5-20260101")
        .prompt("Hello from custom model!")
        .build());
```

> **Note**: The model name must be a valid Anthropic model identifier. Check the [Anthropic Models documentation](https://docs.anthropic.com/claude/docs/models-overview) for available models.

## Features

### Text Generation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929")
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
        .model("anthropic/claude-sonnet-4-5-20250929")
        .prompt("Write a story about a space explorer")
        .build(),
    (chunk) -> {
        System.out.print(chunk.getText());
    });
```

### Tool Use (Function Calling)

Claude 3+ models support tool use:

```java
Tool<Map<String, Object>, Map<String, Object>> calculator = genkit.defineTool(
    "calculator",
    "Performs arithmetic calculations",
    inputSchema,
    inputClass,
    (ctx, input) -> {
        // Implementation
        return result;
    });

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929")
        .prompt("What is 15 * 23?")
        .tools(List.of(calculator))
        .build());
```

### Vision (Claude 3+)

Claude 3+ models support image input:

```java
Message userMessage = new Message();
userMessage.setRole(Role.USER);
userMessage.setContent(List.of(
    Part.text("Describe this image"),
    Part.media("image/jpeg", imageUrl)  // URL or base64 data URI
));

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929")
        .messages(List.of(userMessage))
        .build());
```

## Generation Config Options

| Option | Type | Description |
|--------|------|-------------|
| `temperature` | double | Controls randomness (0.0-1.0) |
| `maxOutputTokens` | int | Maximum tokens to generate |
| `topP` | double | Nucleus sampling threshold |
| `topK` | int | Top-K sampling |
| `stopSequences` | List<String> | Stop generation at these sequences |

## Model Capabilities

| Model | Vision | Tools | System Role | Streaming |
|-------|--------|-------|-------------|-----------|
| Claude 4.5 Opus | ✅ | ✅ | ✅ | ✅ |
| Claude 4.5 Sonnet | ✅ | ✅ | ✅ | ✅ |
| Claude 4.5 Haiku | ✅ | ✅ | ✅ | ✅ |
| Claude 4.x | ✅ | ✅ | ✅ | ✅ |
| Claude 3.x | ✅ | ✅ | ✅ | ✅ |

## API Reference

See the [Anthropic API documentation](https://docs.anthropic.com/) for more details on model capabilities and pricing.

## License

Apache 2.0 - See [LICENSE](../../LICENSE) for details.
