---
title: Mistral
description: Use Mistral AI models for text generation and code.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-mistral</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export MISTRAL_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.mistral.MistralPlugin;

Genkit genkit = Genkit.builder()
    .plugin(MistralPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("mistral/mistral-large-2512")
        .prompt("Tell me about AI")
        .build());
```

## Available models

| Model | Context |
|-------|---------|
| `mistral/mistral-large-2512` | 128K |
| `mistral/mistral-medium-2604` | 128K |
| `mistral/mistral-small-2603` | 128K |
| `mistral/ministral-3b-2512`, `mistral/ministral-8b-2512`, `mistral/ministral-14b-2512` | 128K |
| `mistral/codestral-2508` | 256K |

## Embeddings

- `mistral/mistral-embed`
- `mistral/codestral-embed`

```java
EmbedResponse response = genkit.embed("mistral/mistral-embed", documents);
```

## Features

- Text generation, streaming, tool calling, RAG, embeddings

## Sample

See the [mistral sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/mistral).
