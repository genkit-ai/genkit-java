# Genkit Groq Sample

This sample demonstrates integration with Groq's ultra-fast LLM inference using Genkit Java.

## Features Demonstrated

- **Groq Plugin Setup** - Configure Genkit with Groq's fast models
- **Llama 3.3 70B** - Most capable model with blazing speed
- **Llama 3.1 70B & 8B** - Fast inference with great quality
- **Mixtral 8x7B** - High-quality responses with 32K context
- **Gemma 2 9B** - Efficient model for various tasks
- **Tool Usage** - Time zone conversion tool
- **Ultra-Fast Streaming** - Experience Groq's legendary speed
- **Speed Benchmarks** - Compare inference times

## Supported Models

### Production Models
- `llama-3.1-8b-instant` - Ultra-fast Meta Llama 3.1 8B (~560 tokens/sec)
- `llama-3.3-70b-versatile` - Latest Meta Llama 3.3 70B (~280 tokens/sec)
- `openai/gpt-oss-120b` - OpenAI GPT-OSS 120B with reasoning (~500 tokens/sec)
- `openai/gpt-oss-20b` - OpenAI GPT-OSS 20B (~1000 tokens/sec)
- `meta-llama/llama-guard-4-12b` - Content moderation (~1200 tokens/sec)

## Prerequisites

- Java 21+
- Maven 3.6+
- Groq API key (get one at https://console.groq.com/)

## Running the Sample

### Option 1: Direct Run

```bash
# Set your Groq API key
export GROQ_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/groq

# Run the sample
./run.sh
# Or: mvn compile exec:java
```

### Option 2: With Genkit Dev UI (Recommended)

```bash
# Set your Groq API key
export GROQ_API_KEY=your-api-key-here

# Navigate to the sample directory
cd samples/groq

# Run with Genkit CLI
genkit start -- ./run.sh
```

The Dev UI will be available at http://localhost:4000

## Available Flows

| Flow | Model | Description |
|------|-------|-------------|
| `greeting` | - | Simple greeting flow |
| `chat` | llama-3.3-70b-versatile | Chat with most capable model |
| `timeAssistant` | llama-3.3-70b-versatile | Time zone assistant with tool |
| `fastStreaming` | llama-3.1-8b-instant | Ultra-fast streaming responses |
| `qualityChat` | mixtral-8x7b-32768 | High-quality chat with large context |
| `efficientChat` | gemma2-9b-it | Efficient chat |
| `realTimeQA` | llama-3.1-70b-versatile | Real-time Q&A with timing |
| `speedComparison` | multiple | Benchmark Groq's speed |

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
  -d '"Explain quantum computing in simple terms"'
```

### Time Assistant (with tools)
```bash
curl -X POST http://localhost:8080/api/flows/timeAssistant \
  -H 'Content-Type: application/json' \
  -d '"Convert 3 PM PST to EST"'
```

### Fast Streaming
```bash
curl -X POST http://localhost:8080/api/flows/fastStreaming \
  -H 'Content-Type: application/json' \
  -d '"Tell me about black holes"'
```

### Speed Comparison
```bash
curl -X POST http://localhost:8080/api/flows/speedComparison \
  -H 'Content-Type: application/json' \
  -d '"What is artificial intelligence?"'
```

## Why Groq?

Groq provides the **fastest LLM inference** in the industry:
- 🚀 Ultra-fast token generation (300+ tokens/sec)
- ⚡ Low latency for real-time applications
- 🎯 High-quality models (Llama 3, Mixtral, Gemma)
- 💰 Cost-effective inference

## Learn More

- [Groq Documentation](https://console.groq.com/docs)
- [Genkit Documentation](https://github.com/google/genkit)
