---
title: Ollama
description: Run local models without API keys using Ollama.
---

The Ollama plugin enables local LLM inference without requiring API keys.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-ollama</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Prerequisites

Install and run [Ollama](https://ollama.com/):

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull a model
ollama pull gemma3n:e4b
```

## Configuration

```bash
# Optional: configure Ollama host (default: http://localhost:11434)
export OLLAMA_HOST=http://localhost:11434
```

## Usage

```java
import com.google.genkit.plugins.ollama.OllamaPlugin;

Genkit genkit = Genkit.builder()
    .plugin(OllamaPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("ollama/gemma3n")
        .prompt("Tell me about AI")
        .build());
```

## Models

Use any model available in Ollama. Popular choices:

- `ollama/gemma3n` — Google Gemma 3n
- `ollama/llama3.1` — Meta Llama 3.1
- `ollama/mistral` — Mistral 7B
- `ollama/codellama` — Code-focused model

## Features

- Text generation, streaming, local-first (no API key required)

## Sample

See the [ollama sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/ollama).
