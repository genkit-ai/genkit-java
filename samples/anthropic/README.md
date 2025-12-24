# Genkit Anthropic Sample

This sample demonstrates basic integration with Anthropic Claude models using Genkit Java.

## Features Demonstrated

- **Anthropic Plugin Setup** - Configure Genkit with Claude models
- **Flow Definitions** - Create observable, traceable AI workflows
- **Tool Usage** - Define and use tools with automatic execution (Claude 3+)
- **Text Generation** - Generate text with Claude 4.5 Sonnet and Haiku
- **Streaming** - Real-time response streaming
- **Code Generation** - Generate code with Claude
- **Creative Writing** - Generate creative content with streaming

## Supported Models

The Anthropic plugin supports the following Claude models:

### Claude 4.5 Family
- `claude-opus-4-5-20251101` - Most capable model, best for complex tasks
- `claude-sonnet-4-5-20250929` - Excellent balance of capability and speed
- `claude-haiku-4-5-20251001` - Fast and efficient

### Claude 4 Family
- `claude-opus-4-1-20250805` - Claude 4.1 Opus
- `claude-opus-4-20250514` - Claude 4 Opus
- `claude-sonnet-4-20250514` - Claude 4 Sonnet

### Claude 3 Family
- `claude-3-7-sonnet-20250219` - Claude 3.7 Sonnet
- `claude-3-5-haiku-20241022` - Claude 3.5 Haiku
- `claude-3-opus-20240229` - Most powerful Claude 3 model
- `claude-3-haiku-20240307` - Fastest Claude 3 model

## Prerequisites

- Java 21+
- Maven 3.6+
- Anthropic API key (get one at https://console.anthropic.com/)

## Running the Sample

### Option 1: Direct Run

```bash
# Set your Anthropic API key
export ANTHROPIC_API_KEY=your-api-key-here

# Navigate to the sample directory
cd java/samples/anthropic

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Set your Anthropic API key
export ANTHROPIC_API_KEY=your-api-key-here

# Navigate to the sample directory
cd java/samples/anthropic

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

## Available Flows

| Flow | Model | Input | Output | Description |
|------|-------|-------|--------|-------------|
| `greeting` | - | String (name) | String | Simple greeting flow |
| `tellJoke` | claude-sonnet-4-5 | String (topic) | String | Generate a joke about a topic |
| `chat` | claude-sonnet-4-5 | String (message) | String | Chat with Claude |
| `weatherAssistant` | claude-sonnet-4-5 | String (query) | String | Weather assistant using tools |
| `streamingChat` | claude-sonnet-4-5 | String (message) | String | Streaming chat responses |
| `streamingWeather` | claude-sonnet-4-5 | String (query) | String | Streaming with tools |
| `generateCode` | claude-sonnet-4-5 | String (prompt) | String | Code generation |
| `creativeWriting` | claude-sonnet-4-5 | String (prompt) | String | Creative writing with streaming |
| `summarize` | claude-haiku-4-5 | String (text) | String | Text summarization |

## Example API Calls

Once the server is running on port 8080:

### Simple Greeting
```bash
curl -X POST http://localhost:8080/api/flows/greeting \
  -H 'Content-Type: application/json' \
  -d '"World"'
```

### Generate a Joke
```bash
curl -X POST http://localhost:8080/api/flows/tellJoke \
  -H 'Content-Type: application/json' \
  -d '"programming"'
```

### Chat with Claude
```bash
curl -X POST http://localhost:8080/api/flows/chat \
  -H 'Content-Type: application/json' \
  -d '"What makes Claude different from other AI assistants?"'
```

### Weather Assistant (with tools)
```bash
curl -X POST http://localhost:8080/api/flows/weatherAssistant \
  -H 'Content-Type: application/json' \
  -d '"What is the weather in San Francisco?"'
```

### Streaming Chat
```bash
curl -X POST http://localhost:8080/api/flows/streamingChat \
  -H 'Content-Type: application/json' \
  -d '"Explain quantum computing in simple terms"'
```

### Code Generation
```bash
curl -X POST http://localhost:8080/api/flows/generateCode \
  -H 'Content-Type: application/json' \
  -d '"Write a Python function to find all prime numbers up to n using the Sieve of Eratosthenes"'
```

### Creative Writing
```bash
curl -X POST http://localhost:8080/api/flows/creativeWriting \
  -H 'Content-Type: application/json' \
  -d '"Write a short story about a robot learning to paint"'
```

### Summarization
```bash
curl -X POST http://localhost:8080/api/flows/summarize \
  -H 'Content-Type: application/json' \
  -d '"The quick brown fox jumps over the lazy dog. This sentence contains every letter of the English alphabet and is often used for typing practice and font displays."'
```

## Configuration

The Anthropic plugin can be configured with the following options:

```java
AnthropicPlugin plugin = new AnthropicPlugin(
    AnthropicPluginOptions.builder()
        .apiKey("your-api-key")           // Or use ANTHROPIC_API_KEY env var
        .baseUrl("https://api.anthropic.com/v1")  // Custom base URL
        .anthropicVersion("2023-06-01")   // API version
        .timeout(120)                     // Request timeout in seconds
        .build()
);
```

## Features

### Tool Usage

Claude 3+ models support function calling (tools). Define tools and let Claude decide when to use them:

```java
Tool<Map<String, Object>, Map<String, Object>> weatherTool = genkit.defineTool(
    "getWeather",
    "Gets the current weather for a location",
    schema,
    inputClass,
    (ctx, input) -> {
        // Tool implementation
        return result;
    });

genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929")
        .prompt("What's the weather in Paris?")
        .tools(List.of(weatherTool))
        .build());
```

### Streaming

Stream responses for real-time output:

```java
genkit.generateStream(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929")
        .prompt("Tell me a story")
        .build(),
    (chunk) -> {
        System.out.print(chunk.getText());
    });
```

### Vision (Claude 3+)

Claude 3+ models support image input:

```java
Message userMessage = new Message();
userMessage.setRole(Role.USER);
userMessage.setContent(List.of(
    Part.text("What's in this image?"),
    Part.media("image/jpeg", imageUrl)
));

genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-4-5-20250929")
        .messages(List.of(userMessage))
        .build());
```

## Pricing

See [Anthropic's pricing page](https://www.anthropic.com/pricing) for current model pricing.
