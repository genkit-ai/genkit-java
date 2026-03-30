---
title: OpenAI-Compatible (compat-oai)
description: Connect to any OpenAI-compatible API endpoint.
---

The compat-oai plugin is a base implementation for connecting to any endpoint that follows the OpenAI API format.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-compat-oai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

Connect to custom OpenAI-compatible endpoints:

```java
import com.google.genkit.plugins.compatoai.CompatOaiPlugin;

Genkit genkit = Genkit.builder()
    .plugin(CompatOaiPlugin.create(
        "https://your-custom-endpoint.com/v1",
        "your-api-key"))
    .build();
```

## Built on by other plugins

This plugin serves as the base for several other plugins:

- **xAI** (Grok)
- **DeepSeek**
- **Mistral**
- **Cohere**
- **Groq**
- **OpenAI**

## Features

- Query parameter support (for Azure OpenAI, versioning, etc.)
- Tool calling
- Streaming
- Compatible with self-hosted LLM endpoints (vLLM, LiteLLM, etc.)
