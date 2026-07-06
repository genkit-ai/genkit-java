---
title: Cohere
description: Use Cohere Command models for text generation.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-cohere</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export COHERE_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.cohere.CoherePlugin;

Genkit genkit = Genkit.builder()
    .plugin(CoherePlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("cohere/command-a-03-2025")
        .prompt("Tell me about AI")
        .build());
```

## Available models

- `cohere/command-a-plus-05-2026` — flagship (Mixture-of-Experts, text + vision)
- `cohere/command-a-reasoning-08-2025`
- `cohere/command-a-vision-07-2025`
- `cohere/command-a-03-2025`
- `cohere/command-r7b-12-2024`
- `cohere/command-r-08-2024`
- `cohere/command-r-plus-08-2024`

## Embeddings

Cohere embedding models are available through the OpenAI-compatible endpoint:

- `cohere/embed-v4.0`
- `cohere/embed-multilingual-v3.0`
- `cohere/embed-english-v3.0`

```java
EmbedResponse response = genkit.embed("cohere/embed-v4.0", documents);
```

## Features

- Text generation, tool calling, RAG support, SSE streaming, embeddings

## Sample

See the [cohere sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/cohere).
