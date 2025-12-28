# Genkit OpenAI-Compatible API Plugin

This plugin provides a common implementation for OpenAI-compatible APIs. It can be used by multiple LLM providers that implement OpenAI-compatible endpoints, including:

- OpenAI
- Anthropic (via compatibility layer)
- XAI (x.ai / Grok)
- DeepSeek
- Mistral AI
- Cohere
- Groq
- **Any custom or self-hosted OpenAI-compatible endpoint**

## Features

- **Text Generation**: Synchronous and streaming text generation
- **Tool Calling**: Function/tool calling support
- **Document Context**: RAG support with document context
- **Streaming**: Server-sent events (SSE) streaming support

## Usage

### Option 1: Use as a Base for Provider Plugins

Provider-specific plugins (like `genkit-plugin-xai`, `genkit-plugin-groq`, etc.) use this plugin internally. See those plugins for provider-specific usage.

### Option 2: Use Directly for Custom Endpoints

You can use this plugin directly to connect to any OpenAI-compatible API endpoint:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.compatoai.CompatOAIPlugin;

// Connect to a custom OpenAI-compatible endpoint
CompatOAIPlugin customPlugin = CompatOAIPlugin.builder()
    .pluginName("my-provider")
    .apiKey("your-api-key")
    .baseUrl("https://api.example.com/v1")
    .addModels("model-1", "model-2", "model-3")
    .build();

Genkit genkit = Genkit.builder()
    .plugin(customPlugin)
    .build();

// Use your custom models
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("my-provider/model-1")
        .prompt("Hello!")
        .build());
```

### Advanced Configuration

```java
CompatOAIPlugin plugin = CompatOAIPlugin.builder()
    .pluginName("my-llm")
    .apiKey(System.getenv("MY_LLM_API_KEY"))
    .baseUrl("https://my-llm-api.example.com/v1")
    .organization("my-org-id")  // Optional
    .timeout(120)               // Optional, default 60s
    .queryParams(Map.of(        // Optional, for adding query parameters to API requests
        "api-version", "2024-10-01",
        "custom-param", "value"))
    .addModel("model-1", "My LLM Model 1")  // With custom label
    .addModel("model-2", "My LLM Model 2")
    .addModels("model-3", "model-4")        // Multiple at once
    .build();

Genkit genkit = Genkit.builder()
    .plugin(plugin)
    .build();
```

#### Query Parameters

The `queryParams` option allows you to add custom query parameters to all API requests. This is useful for:
- **API Versioning**: Some providers (like Azure OpenAI) require `api-version` query parameters
- **Custom Authentication**: Additional authentication parameters beyond API keys
- **Feature Flags**: Provider-specific feature toggles
- **Routing**: Custom routing parameters for multi-tenant deployments

Example with Azure OpenAI:
```java
CompatOAIPlugin.builder()
    .pluginName("azure-openai")
    .apiKey(System.getenv("AZURE_OPENAI_KEY"))
    .baseUrl("https://my-resource.openai.azure.com/openai/deployments/gpt-4")
    .queryParams(Map.of("api-version", "2024-10-01-preview"))
    .addModel("gpt-4")
    .build();
```

The final URL will be: `{baseUrl}/chat/completions?api-version=2024-10-01-preview`

### Use Cases

1. **Self-Hosted Models**: Connect to your own OpenAI-compatible inference server
   ```java
   CompatOAIPlugin.builder()
       .pluginName("local")
       .apiKey("not-needed")
       .baseUrl("http://localhost:8000/v1")
       .addModel("llama-3-70b")
       .build();
   ```

2. **Testing New Providers**: Try out new LLM providers before official plugins exist
   ```java
   CompatOAIPlugin.builder()
       .pluginName("new-provider")
       .apiKey(System.getenv("NEW_PROVIDER_KEY"))
       .baseUrl("https://api.newprovider.ai/v1")
       .addModels("their-model-1", "their-model-2")
       .build();
   ```

3. **Custom Endpoints**: Use specialized or custom-configured endpoints
   ```java
   CompatOAIPlugin.builder()
       .pluginName("custom")
       .apiKey(System.getenv("CUSTOM_API_KEY"))
       .baseUrl("https://custom.example.com/ai/v1")
       .organization("my-org")
       .timeout(180)
       .addModel("specialized-model")
       .build();
   ```

4. **Multiple Instances**: Connect to multiple different endpoints
   ```java
   Genkit genkit = Genkit.builder()
       .plugin(CompatOAIPlugin.builder()
           .pluginName("provider-a")
           .apiKey(System.getenv("PROVIDER_A_KEY"))
           .baseUrl("https://api.provider-a.com/v1")
           .addModel("model-a1")
           .build())
       .plugin(CompatOAIPlugin.builder()
           .pluginName("provider-b")
           .apiKey(System.getenv("PROVIDER_B_KEY"))
           .baseUrl("https://api.provider-b.com/v1")
           .addModel("model-b1")
           .build())
       .build();

   // Use models from both providers
   genkit.generate(GenerateOptions.builder()
       .model("provider-a/model-a1").prompt("...").build());
   genkit.generate(GenerateOptions.builder()
       .model("provider-b/model-b1").prompt("...").build());
   ```

## Full Example

```java
import com.google.genkit.Genkit;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.GenerationConfig;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.plugins.compatoai.CompatOAIPlugin;

public class CustomProviderExample {
  public static void main(String[] args) {
    // Create plugin for custom endpoint
    CompatOAIPlugin customPlugin = CompatOAIPlugin.builder()
        .pluginName("my-ai")
        .apiKey(System.getenv("MY_AI_API_KEY"))
        .baseUrl("https://api.my-ai.com/v1")
        .addModel("chat-model", "My AI Chat Model")
        .addModel("code-model", "My AI Code Model")
        .timeout(90)
        .build();

    // Initialize Genkit
    Genkit genkit = Genkit.builder()
        .plugin(customPlugin)
        .build();

    // Use the custom models
    ModelResponse response = genkit.generate(
        GenerateOptions.builder()
            .model("my-ai/chat-model")
            .prompt("Explain quantum computing")
            .config(GenerationConfig.builder()
                .temperature(0.7)
                .maxOutputTokens(500)
                .build())
            .build());

    System.out.println(response.getText());

    // Streaming example
    genkit.generateStream(
        GenerateOptions.builder()
            .model("my-ai/code-model")
            .prompt("Write a Python function to sort a list")
            .build(),
        chunk -> System.out.print(chunk.getText()));
  }
}
```

## API Compatibility

This plugin requires the endpoint to implement OpenAI's chat completions API:

**POST** `/chat/completions`

With support for:
- `messages` array with `role` and `content`
- `model` parameter
- `temperature`, `max_tokens`, `top_p` parameters
- `tools` array for function calling (optional)
- `stream` parameter for streaming responses (optional)

Most OpenAI-compatible inference servers support this standard, including:
- vLLM
- Text Generation Inference (TGI)
- Ollama (with OpenAI compatibility mode)
- LM Studio
- LocalAI
- And many more

## Architecture

The plugin provides:
- `CompatOAIModel`: Core model implementation with OpenAI-compatible API support
- `CompatOAIPluginOptions`: Configuration options for API endpoints and authentication
- `CompatOAIPlugin`: Plugin class for direct usage (NEW!)
- Streaming and non-streaming generation
- Tool calling and function execution
- Document context handling for RAG applications
