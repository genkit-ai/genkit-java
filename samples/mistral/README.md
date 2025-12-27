# Genkit Mistral AI Sample

This sample demonstrates integration with Mistral AI models using Genkit Java.

## Features Demonstrated

- **Mistral Plugin Setup** - Configure Genkit with Mistral models
- **Mistral Large 3** - Latest multimodal flagship (256K context)
- **Mistral Medium 3.1** - Balanced performance model (128K context)
- **Mistral Small 3.2** - Efficient model for faster responses (128K context)
- **Ministral 3** - Compact models (3B/8B/14B) for efficient processing
- **Codestral** - Specialized model for code generation (256K context)
- **Tool Usage** - Translation tool with automatic execution
- **Streaming** - Real-time response streaming

## Supported Models

### Flagship Models
- `mistral-large-2512` - Latest flagship multimodal model (262K context)
- `mistral-medium-2508` - Balanced performance (131K context)
- `mistral-small-2506` - Efficient and fast (131K context)

### Reasoning Models
- `magistral-medium-2509` - Frontier-class reasoning (131K context)
- `magistral-small-2509` - Efficient reasoning (131K context)

### Compact Models
- `ministral-3b-2512` - Tiny 3B model (131K context)
- `ministral-8b-2512` - Balanced 8B model (262K context)
- `ministral-14b-2512` - Advanced 14B model (262K context)

### Vision Models
- `pixtral-large-2411` - Multimodal with vision (131K context)

### Code Models
- `codestral-2508` - Code generation specialist (256K context)
- `devstral-2512` - Code-agentic model (262K context)

### Open Source
- `open-mistral-nemo` - Multilingual open source (131K context)

## Prerequisites

- Java 21+
- Maven 3.6+
- Mistral API key (get one at https://console.mistral.ai/)

## Running the Sample

### Option 1: Direct Run

```bash
# Set your Mistral API key
export MISTRAL_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/mistral

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Set your Mistral API key
export MISTRAL_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/mistral

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

## Available Flows

| Flow | Model | Description |
|------|-------|-------------|
| `greeting` | - | Simple greeting flow |
| `chat` | mistral-large-2512 | Chat with latest flagship |
| `translationAssistant` | mistral-medium-2508 | Translation with balanced model |
| `streamingChat` | mistral-small-2506 | Fast streaming responses |
| `generateCode` | codestral-2508 | Code generation specialist |
| `quickQA` | ministral-3b-2512 | Quick Q&A with compact model |
| `efficientChat` | ministral-8b-2512 | Efficient balanced chat |
| `creativeWriting` | mistral-large-2512 | Creative writing with streaming |

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
  -d '"Explain machine learning in simple terms"'
```

### Translation Assistant (with tools)
```bash
curl -X POST http://localhost:8080/api/flows/translationAssistant \
  -H 'Content-Type: application/json' \
  -d '"Translate Hello World to French"'
```

### Generate Code
```bash
curl -X POST http://localhost:8080/api/flows/generateCode \
  -H 'Content-Type: application/json' \
  -d '"Write a Python function to find the nth Fibonacci number"'
```

### Quick Q&A
```bash
curl -X POST http://localhost:8080/api/flows/quickQA \
  -H 'Content-Type: application/json' \
  -d '"What is photosynthesis?"'
```

## Learn More

- [Mistral AI Documentation](https://docs.mistral.ai/)
- [Genkit Documentation](https://github.com/google/genkit)
