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
        .model("mistral/mistral-large-3-25-12")
        .prompt("Tell me about AI")
        .build());
```

## Available models

| Model | Context |
|-------|---------|
| `mistral/mistral-large-3-25-12` | 128K |
| `mistral/mistral-medium-3-1-25-08` | 128K |
| `mistral/mistral-small-*` | 128K |
| `mistral/ministral-*` | 128K |
| `mistral/codestral-25-08` | 256K |

## Features

- Text generation, streaming, tool calling, RAG

## Sample

See the [mistral sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/mistral).
