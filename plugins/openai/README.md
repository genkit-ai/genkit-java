# Genkit OpenAI Plugin

OpenAI model plugin for [Genkit Java](https://github.com/firebase/genkit).

This plugin provides integration with OpenAI's GPT models, embeddings, and DALL-E image generation.

**Note:** This plugin has been refactored to use the `compat-oai` base plugin for chat models, while embeddings and image generation still use OpenAI-specific implementations.

## Features

- **Chat Models**: GPT-4, GPT-4o, GPT-3.5, and more
- **Embeddings**: text-embedding-3-small, text-embedding-3-large, text-embedding-ada-002
- **Image Generation**: DALL-E 3, DALL-E 2
- **Streaming**: Real-time streaming responses
- **Tool Calling**: Function calling support
- **Vision**: Image understanding with GPT-4o and GPT-4 Vision
- **Document Context**: RAG support

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-openai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.openai.OpenAIPlugin;

// Create Genkit with OpenAI plugin
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .build();

// Generate text with GPT-4
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Explain quantum computing")
        .build());

System.out.println(response.getText());
```

## Configuration

### Using Environment Variable (Recommended)

Set `OPENAI_API_KEY` in your environment:

```bash
export OPENAI_API_KEY=your-api-key-here
```

Then create the plugin:

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .build();
```

### Programmatic Configuration

```java
Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create("your-api-key-here"))
    .build();
```

### Advanced Configuration

```java
OpenAIPluginOptions options = OpenAIPluginOptions.builder()
    .apiKey("your-api-key-here")
    .baseUrl("https://api.openai.com/v1")  // Custom base URL
    .organization("your-org-id")            // Optional organization
    .timeout(120)                           // Timeout in seconds
    .build();

Genkit genkit = Genkit.builder()
    .plugin(new OpenAIPlugin(options))
    .build();
```

## Supported Models

### Chat Models
- `gpt-5.2`, `gpt-5.1`, `gpt-5`
- `gpt-4o`, `gpt-4o-mini`
- `gpt-4-turbo`, `gpt-4`, `gpt-4-32k`
- `gpt-3.5-turbo`, `gpt-3.5-turbo-16k`
- `o1-preview`, `o1-mini`

### Embedding Models
- `text-embedding-3-small`
- `text-embedding-3-large`
- `text-embedding-ada-002`

### Image Generation Models
- `dall-e-3`
- `dall-e-2`
- `gpt-image-1`

## Examples

### Text Generation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Write a haiku about AI")
        .config(GenerationConfig.builder()
            .temperature(0.7)
            .maxOutputTokens(100)
            .build())
        .build());
```

### Streaming

```java
ModelResponse response = genkit.generateStream(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Tell me a story")
        .build(),
    (chunk) -> {
        System.out.print(chunk.getText());
    });
```

### Tool Calling

```java
Tool<WeatherInput, WeatherOutput> weatherTool = genkit.defineTool(
    "getWeather",
    "Gets weather for a location",
    WeatherInput.class,
    (ctx, input) -> {
        return new WeatherOutput("sunny", 72);
    });

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("What's the weather in San Francisco?")
        .tools(List.of(weatherTool))
        .build());
```

### Vision (Image Understanding)

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .messages(List.of(
            Message.user(List.of(
                Part.text("What's in this image?"),
                Part.media("https://example.com/image.jpg")
            ))
        ))
        .build());
```

### Document Context (RAG)

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Summarize the documents")
        .context(List.of(
            Document.fromText("Document 1 content..."),
            Document.fromText("Document 2 content...")
        ))
        .build());
```

## Architecture

The OpenAI plugin uses a hybrid architecture:

- **Chat Models**: Use the `compat-oai` base implementation for OpenAI-compatible API calls
- **Embeddings**: Use OpenAI-specific implementation
- **Image Generation**: Use OpenAI-specific DALL-E implementation

This allows the plugin to leverage the common OpenAI-compatible API layer while maintaining specialized implementations for unique features.
