---
title: OpenAI
description: Use OpenAI GPT models, DALL-E, and embeddings.
---

The OpenAI plugin provides access to OpenAI's models including GPT-4o, DALL-E, and text embeddings.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-openai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export OPENAI_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.openai.OpenAIPlugin;

Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o-mini")
        .prompt("Tell me about AI")
        .build());
```

## Available models

- `openai/gpt-4o` — Most capable model
- `openai/gpt-4o-mini` — Fast and cost-effective
- `openai/gpt-4-turbo` — Previous generation flagship
- `openai/o1-preview` — Reasoning model
- `openai/o1-mini` — Fast reasoning model

## Embeddings

```java
EmbedResponse response = genkit.embed(
    "openai/text-embedding-3-small", documents
);
```

## Features

- Text generation and streaming
- Vision (image understanding)
- Tool calling
- Embeddings (text-embedding-3-small, text-embedding-3-large)
- Image generation (DALL-E 3/2)
- RAG support

## Sample

See the [openai sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/openai).
