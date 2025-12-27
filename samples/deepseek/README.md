# Genkit DeepSeek Sample

This sample demonstrates integration with DeepSeek models using Genkit Java.

## Features Demonstrated

- **DeepSeek Plugin Setup** - Configure Genkit with DeepSeek models
- **DeepSeek-Chat** - Fast, efficient chat model for general tasks
- **DeepSeek-Reasoner** - Advanced reasoning model for complex problems
- **Tool Usage** - Mathematical calculator tool with automatic execution
- **Text Generation** - Generate responses with streaming support
- **Code Generation** - Generate and review code
- **Problem Solving** - Step-by-step reasoning with explanations

## Supported Models

- `deepseek-chat` - Efficient chat model for general tasks
- `deepseek-reasoner` - Advanced model with enhanced reasoning capabilities

## Prerequisites

- Java 21+
- Maven 3.6+
- DeepSeek API key (get one at https://platform.deepseek.com/)

## Running the Sample

### Option 1: Direct Run

```bash
# Set your DeepSeek API key
export DEEPSEEK_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/deepseek

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Set your DeepSeek API key
export DEEPSEEK_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/deepseek

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

## Available Flows

| Flow | Model | Description |
|------|-------|-------------|
| `greeting` | - | Simple greeting flow |
| `chat` | deepseek-chat | General chat |
| `mathAssistant` | deepseek-chat | Math assistant with calculator tool |
| `reasoning` | deepseek-reasoner | Complex reasoning and problem analysis |
| `streamingChat` | deepseek-chat | Streaming chat responses |
| `generateCode` | deepseek-chat | Code generation |
| `codeReview` | deepseek-chat | Code review and feedback |
| `problemSolving` | deepseek-reasoner | Step-by-step problem solving with streaming |

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
  -d '"Explain quantum entanglement in simple terms"'
```

### Math Assistant (with tools)
```bash
curl -X POST http://localhost:8080/api/flows/mathAssistant \
  -H 'Content-Type: application/json' \
  -d '"What is 25 times 47?"'
```

### Reasoning
```bash
curl -X POST http://localhost:8080/api/flows/reasoning \
  -H 'Content-Type: application/json' \
  -d '"How can we address climate change effectively?"'
```

### Generate Code
```bash
curl -X POST http://localhost:8080/api/flows/generateCode \
  -H 'Content-Type: application/json' \
  -d '"Write a Python function to calculate Fibonacci numbers"'
```

## Learn More

- [DeepSeek Documentation](https://platform.deepseek.com/docs)
- [Genkit Documentation](https://github.com/google/genkit)
