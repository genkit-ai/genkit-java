---
title: DeepSeek
description: Use DeepSeek models for text generation and reasoning.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-deepseek</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export DEEPSEEK_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.deepseek.DeepSeekPlugin;

Genkit genkit = Genkit.builder()
    .plugin(DeepSeekPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("deepseek/deepseek-v4-pro")
        .prompt("Tell me about AI")
        .build());
```

## Available models

- `deepseek/deepseek-v4-pro` — Flagship V4 model
- `deepseek/deepseek-v4-flash` — Fast, economical V4 model
- `deepseek/deepseek-chat` — Legacy alias (deprecated, retires 2026-07-24)
- `deepseek/deepseek-reasoner` — Legacy reasoning alias (deprecated, retires 2026-07-24)

## Features

- Text generation, streaming, tool calling, RAG

## Sample

See the [deepseek sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/deepseek).
