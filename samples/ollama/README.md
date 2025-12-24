# Genkit Ollama Sample

This sample demonstrates integration with local Ollama models using Genkit Java.

## Features Demonstrated

- **Ollama Plugin Setup** - Configure Genkit with local Ollama models
- **Flow Definitions** - Create observable, traceable AI workflows
- **Text Generation** - Generate text with Gemma 3n
- **Streaming** - Real-time response streaming
- **Code Generation** - Generate code
- **Creative Writing** - Generate creative content
- **Translation & Summarization** - Language tasks

## Model

This sample uses `gemma3n:e4b` - Google Gemma 3n Edge 4B model.

## Prerequisites

- Java 21+
- Maven 3.6+
- Ollama installed and running

### Installing Ollama

1. Download and install Ollama from https://ollama.ai
2. Pull the models you want to use:

```bash
# Pull the model
ollama pull gemma3n:e4b
```

3. Verify Ollama is running:
```bash
ollama list
```

## Running the Sample

### Option 1: Direct Run

```bash
# Navigate to the sample directory
cd java/samples/ollama

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Navigate to the sample directory
cd java/samples/ollama

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

### Custom Ollama Host

If Ollama is running on a different host:

```bash
export OLLAMA_HOST=http://your-ollama-server:11434
./run.sh
```

## Available Flows

| Flow | Input | Output | Description |
|------|-------|--------|-------------|
| `greeting` | String (name) | String | Simple greeting flow |
| `chat` | String (message) | String | Chat with Gemma |
| `tellJoke` | String (topic) | String | Generate a joke |
| `streamingChat` | String (message) | String | Streaming chat |
| `generateCode` | String (prompt) | String | Code generation (streaming) |
| `quickAnswer` | String (question) | String | Fast, brief answers |
| `creativeWriting` | String (prompt) | String | Creative writing (streaming) |
| `translate` | String (text) | String | Translate to Spanish |
| `summarize` | String (text) | String | Text summarization |

## Example API Calls

Once the server is running on port 8080:

### Simple Greeting
```bash
curl -X POST http://localhost:8080/api/flows/greeting \
  -H 'Content-Type: application/json' \
  -d '"World"'
```

### Chat
```bash
curl -X POST http://localhost:8080/api/flows/chat \
  -H 'Content-Type: application/json' \
  -d '"What is the capital of France?"'
```

### Generate a Joke
```bash
curl -X POST http://localhost:8080/api/flows/tellJoke \
  -H 'Content-Type: application/json' \
  -d '"programming"'
```

### Streaming Chat
```bash
curl -X POST http://localhost:8080/api/flows/streamingChat \
  -H 'Content-Type: application/json' \
  -d '"Explain quantum computing"'
```

### Code Generation
```bash
curl -X POST http://localhost:8080/api/flows/generateCode \
  -H 'Content-Type: application/json' \
  -d '"Write a Python function to find prime numbers up to n"'
```

### Quick Answer
```bash
curl -X POST http://localhost:8080/api/flows/quickAnswer \
  -H 'Content-Type: application/json' \
  -d '"What is 2+2?"'
```

### Creative Writing
```bash
curl -X POST http://localhost:8080/api/flows/creativeWriting \
  -H 'Content-Type: application/json' \
  -d '"Write a short story about a robot learning to paint"'
```

### Translation
```bash
curl -X POST http://localhost:8080/api/flows/translate \
  -H 'Content-Type: application/json' \
  -d '"Hello, how are you?"'
```

### Summarization
```bash
curl -X POST http://localhost:8080/api/flows/summarize \
  -H 'Content-Type: application/json' \
  -d '"The quick brown fox jumps over the lazy dog. This sentence contains every letter of the English alphabet."'
```

## Configuration

The Ollama plugin can be configured with the following options:

```java
OllamaPlugin plugin = new OllamaPlugin(
    OllamaPluginOptions.builder()
        .baseUrl("http://localhost:11434")  // Or use OLLAMA_HOST env var
        .timeout(300)                        // Request timeout in seconds
        .models("gemma3n:e4b")               // Model to register
        .build()
);
```

## Features

### Streaming

Stream responses for real-time output:

```java
genkit.generateStream(
    GenerateOptions.builder()
        .model("ollama/gemma3n:e4b")
        .prompt("Tell me a story")
        .build(),
    (chunk) -> {
        System.out.print(chunk.getText());
    });
```

### JSON Output

Request JSON-formatted responses:

```java
genkit.generate(
    GenerateOptions.builder()
        .model("ollama/gemma3n:e4b")
        .prompt("List 3 colors as JSON")
        .output(OutputConfig.builder()
            .format(OutputFormat.JSON)
            .build())
        .build());
```

## Performance Tips

1. **Enable GPU acceleration** - Ollama automatically uses GPU if available
2. **Adjust context window** - Smaller context = faster responses
3. **Use streaming** - Better UX for longer responses

## Troubleshooting

### "Connection refused" error
Ensure Ollama is running:
```bash
ollama serve
```

### "Model not found" error
Pull the required model:
```bash
ollama pull gemma3n:e4b
```

### Slow responses
- Check if GPU is being used: `ollama ps`
- Reduce `maxOutputTokens`

### Out of memory
- Use a smaller model
- Reduce context length
- Close other applications

## Resources

- [Ollama Documentation](https://github.com/ollama/ollama)
- [Available Models](https://ollama.ai/library)
- [Ollama API Reference](https://github.com/ollama/ollama/blob/main/docs/api.md)
