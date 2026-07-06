# Genkit XAI Sample

This sample demonstrates integration with XAI (Grok) models using Genkit Java.

## Features Demonstrated

- **XAI Plugin Setup** - Configure Genkit with Grok models
- **Flow Definitions** - Create observable, traceable AI workflows
- **Tool Usage** - Define and use tools with automatic execution
- **Text Generation** - Generate text with latest Grok 4 models
- **Streaming** - Real-time response streaming
- **Code Generation** - Generate code with Grok 3
- **Fast Tool Calling** - Optimized agentic workflows with Grok 4.3

## Supported Models

### Latest Flagship
- `grok-4.3` - Latest flagship model (2M context)

### Grok 4.20 Variants
- `grok-4.20-0309-reasoning` - Reasoning mode (2M context)
- `grok-4.20-0309-non-reasoning` - Non-reasoning mode (2M context)
- `grok-4.20-multi-agent-0309` - Multi-agent mode (2M context)

### Agentic Coding
- `grok-build-0.1` - Specialized for agentic coding (256K context)

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
| `chat` | grok-4.3 | Chat with latest Grok |
| `weatherAssistant` | grok-4.3 | Tool calling for weather |
| `streamingChat` | grok-4.3 | Streaming chat responses |
| `generateCode` | grok-build-0.1 | Agentic code generation |
| `analyze` | grok-4.20-0309-reasoning | Reasoning-based text analysis |
| `creativeWriting` | grok-4.3 | Creative writing with streaming |

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
