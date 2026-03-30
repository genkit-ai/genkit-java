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

- `cohere/command-a-03-2025`
- `cohere/command-r7b-12-2024`
- `cohere/command-r-08-2024`
- `cohere/command-r-plus-08-2024`

## Features

- Text generation, tool calling, RAG support, SSE streaming

## Sample

See the [cohere sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/cohere).
