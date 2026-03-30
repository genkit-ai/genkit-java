---
title: xAI (Grok)
description: Use xAI Grok models for text generation.
---

The xAI plugin provides access to Grok models.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-xai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export XAI_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.xai.XAIPlugin;

Genkit genkit = Genkit.builder()
    .plugin(XAIPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("xai/grok-3")
        .prompt("Tell me about AI")
        .build());
```

## Available models

| Model | Context Window |
|-------|---------------|
| `xai/grok-4` | Up to 2M tokens |
| `xai/grok-4-1-fast` | 131K tokens |
| `xai/grok-3` | 131K tokens |
| `xai/grok-3-mini` | 131K tokens |

## Features

- Text generation, streaming, tool calling, RAG

## Sample

See the [xai sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/xai).
