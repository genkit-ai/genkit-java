---
title: Samples
description: Example applications for every supported provider and feature.
---

All samples are located in the [`samples/`](https://github.com/genkit-ai/genkit-java/tree/main/samples) directory.

## Running a sample

```bash
# Set your API key
export OPENAI_API_KEY=your-api-key
# Or: export GOOGLE_GENAI_API_KEY=your-api-key

# Navigate to a sample and run
cd samples/openai
./run.sh

# Or with Genkit Dev UI (recommended)
genkit start -- ./run.sh
```

## Available samples

### Model provider samples

| Sample | Description |
|--------|-------------|
| **openai** | Basic OpenAI integration with flows and tools |
| **google-genai** | Google Gemini integration with image generation |
| **anthropic** | Anthropic Claude integration with streaming |
| **aws-bedrock** | AWS Bedrock models integration |
| **azure-foundry** | Azure AI Foundry models integration |
| **xai** | xAI Grok models integration |
| **deepseek** | DeepSeek models integration |
| **cohere** | Cohere Command models integration |
| **mistral** | Mistral AI models integration |
| **groq** | Groq ultra-fast inference integration |
| **ollama** | Local Ollama models with Gemma 3n |

### Feature samples

| Sample | Description |
|--------|-------------|
| **agents-weather** | Weather assistant agent demonstrating the beta Agents API (server-managed and client-managed sessions) |
| **dotprompt** | DotPrompt files with complex inputs/outputs, variants, and partials |
| **structured-output** | Type-safe structured output generation |
| **rag** | RAG application with local vector store |
| **evaluations** | Custom evaluators and evaluation workflows |
| **evaluators-plugin** | Pre-built RAGAS-style evaluators plugin demo |
| **complex-io** | Complex nested types, arrays, maps in flow inputs/outputs |
| **middleware** | Middleware patterns for logging, caching, rate limiting |
| **interrupts** | Flow interrupts and human-in-the-loop patterns |
| **mcp** | Model Context Protocol (MCP) integration |

### Deployment & infrastructure samples

| Sample | Description |
|--------|-------------|
| **firebase** | Firebase integration with Firestore RAG and Cloud Functions |
| **spring** | Spring Boot HTTP server integration |
| **weaviate** | Weaviate vector database RAG |
| **postgresql** | PostgreSQL pgvector RAG |
| **pinecone** | Pinecone vector database RAG |
