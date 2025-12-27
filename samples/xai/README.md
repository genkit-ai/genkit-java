# Genkit XAI Sample

This sample demonstrates integration with XAI (Grok) models using Genkit Java.

## Features Demonstrated

- **XAI Plugin Setup** - Configure Genkit with Grok models
- **Flow Definitions** - Create observable, traceable AI workflows
- **Tool Usage** - Define and use tools with automatic execution
- **Text Generation** - Generate text with latest Grok 4 models
- **Streaming** - Real-time response streaming
- **Code Generation** - Generate code with Grok 3
- **Fast Tool Calling** - Optimized agentic workflows with Grok 4.1 Fast

## Supported Models

### Latest Flagship
- `grok-4` - Latest flagship model (131K context)
- `grok-4-1-fast` - Optimized for agentic tool calling (2M context)

### Reasoning Variants
- `grok-4-1-fast-reasoning` - Fast reasoning mode (2M context)
- `grok-4-1-fast-non-reasoning` - Fast without reasoning (2M context)
- `grok-4-fast-reasoning` - Standard fast reasoning (2M context)
- `grok-4-fast-non-reasoning` - Standard fast without reasoning (2M context)

### Code Generation
- `grok-code-fast-1` - Specialized for code generation (256K context)

### Previous Generation
- `grok-4-0709` - July 2024 version (256K context)
- `grok-3` - Powerful previous generation (131K context)
- `grok-3-mini` - Efficient small model (131K context)

## Prerequisites

- Java 21+
- Maven 3.6+
- XAI API key (get one at https://x.ai/)

## Running the Sample

### Option 1: Direct Run

```bash
# Set your XAI API key
export XAI_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/xai

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Set your XAI API key
export XAI_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/xai

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

## Available Flows

| Flow | Model | Description |
|------|-------|-------------|
| `greeting` | - | Simple greeting flow |
| `chat` | grok-4 | Chat with latest Grok |
| `weatherAssistant` | grok-4-1-fast | Fast tool calling for weather |
| `streamingChat` | grok-4 | Streaming chat responses |
| `generateCode` | grok-3 | Code generation |
| `analyze` | grok-3-mini | Fast text analysis |
| `creativeWriting` | grok-4 | Creative writing with streaming |

## Example API Calls

Once the server is running on port 8080:

### Simple Greeting
```bash
curl -X POST http://localhost:8080/api/flows/greeting \
  -H 'Content-Type: application/json' \
  -d '"World"'
```

### Chat with Grok
```bash
curl -X POST http://localhost:8080/api/flows/chat \
  -H 'Content-Type: application/json' \
  -d '"What makes you different from other AI models?"'
```

### Weather Assistant (with tools)
```bash
curl -X POST http://localhost:8080/api/flows/weatherAssistant \
  -H 'Content-Type: application/json' \
  -d '"What is the weather in New York?"'
```

### Generate Code
```bash
curl -X POST http://localhost:8080/api/flows/generateCode \
  -H 'Content-Type: application/json' \
  -d '"Write a Java function to check if a string is a palindrome"'
```

## Learn More

- [XAI Documentation](https://docs.x.ai/)
- [Genkit Documentation](https://github.com/google/genkit)
