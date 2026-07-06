---
title: Anthropic (Claude)
description: Use Anthropic's Claude models for text generation.
---

The Anthropic plugin provides access to Claude models.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-anthropic</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export ANTHROPIC_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.anthropic.AnthropicPlugin;

Genkit genkit = Genkit.builder()
    .plugin(AnthropicPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("anthropic/claude-sonnet-5")
        .prompt("Tell me about AI")
        .build());
```

## Available models

### Claude 5 family
- `anthropic/claude-fable-5`
- `anthropic/claude-sonnet-5`

### Claude 4 family
- `anthropic/claude-opus-4-8`
- `anthropic/claude-opus-4-7`
- `anthropic/claude-opus-4-6`
- `anthropic/claude-sonnet-4-6`
- `anthropic/claude-opus-4-5-20251101`
- `anthropic/claude-sonnet-4-5-20250929`
- `anthropic/claude-haiku-4-5-20251001`
- `anthropic/claude-opus-4-1-20250805`

## Features

- Text generation
- Streaming
- Tool calling

## Sample

See the [anthropic sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/anthropic).
