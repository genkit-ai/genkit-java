# Genkit Cohere Sample

This sample demonstrates integration with Cohere models using Genkit Java.

## Features Demonstrated

- **Cohere Plugin Setup** - Configure Genkit with Command models
- **Command A** - Most capable and performant model (256K context)
- **Command R** - Balanced performance for content generation (128K context)
- **Command R7B** - Small, fast model for efficient tasks (128K context)
- **Tool Usage** - Search tool with automatic execution
- **Streaming** - Real-time response streaming

## Supported Models

- `command-a-03-2025` - Most capable Cohere model with 256K context and 150% higher throughput than Command R+ 08-2024
- `command-r7b-12-2024` - Small and fast 7B model, 128K context
- `command-r-08-2024` - Balanced performance model, 128K context
- `command-r-plus-08-2024` - Previous flagship model, 128K context

## Prerequisites

- Java 21+
- Maven 3.6+
- Cohere API key (get one at https://dashboard.cohere.com/)

## Running the Sample

### Option 1: Direct Run

```bash
# Set your Cohere API key
export COHERE_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/cohere

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Set your Cohere API key
export COHERE_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/cohere

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

## Available Flows

| Flow | Model | Description |
|------|-------|-------------|
| `greeting` | - | Simple greeting flow |
| `chat` | command-a-03-2025 | Chat with Cohere's most capable model |
| `researchAssistant` | command-a-03-2025 | Research assistant with search tool |
| `streamingChat` | command-a-03-2025 | Streaming chat responses |
| `generateContent` | command-r-08-2024 | Content generation |
| `summarize` | command-r7b-12-2024 | Text summarization with small model |
| `qa` | command-r-08-2024 | Question answering |
| `creativeWriting` | command-a-03-2025 | Creative writing with streaming |

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
  -d '"What are the benefits of AI?"'
```

### Research Assistant (with tools)
```bash
curl -X POST http://localhost:8080/api/flows/researchAssistant \
  -H 'Content-Type: application/json' \
  -d '"Find information about renewable energy"'
```

### Question Answering
```bash
curl -X POST http://localhost:8080/api/flows/qa \
  -H 'Content-Type: application/json' \
  -d '"What is machine learning?"'
```

### Summarize
```bash
curl -X POST http://localhost:8080/api/flows/summarize \
  -H 'Content-Type: application/json' \
  -d '"[Your long text here]"'
```

## Learn More

- [Cohere Documentation](https://docs.cohere.com/)
- [Genkit Documentation](https://github.com/google/genkit)
