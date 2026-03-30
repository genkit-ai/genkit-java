---
title: Groq
description: Ultra-fast inference with Groq for Llama, Mixtral, and more.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-groq</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export GROQ_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.groq.GroqPlugin;

Genkit genkit = Genkit.builder()
    .plugin(GroqPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("groq/llama-3.3-70b-versatile")
        .prompt("Tell me about AI")
        .build());
```

## Available models

| Model | Speed |
|-------|-------|
| `groq/llama-3.1-8b-instant` | ~1200 tokens/sec |
| `groq/llama-3.3-70b-versatile` | ~560 tokens/sec |
| `groq/openai/gpt-oss` models | Varies |
| `groq/meta-llama/llama-guard-4` | Content moderation |

## Features

- Ultra-fast inference, text generation, streaming, tool calling, RAG, content moderation

## Sample

See the [groq sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/groq).
