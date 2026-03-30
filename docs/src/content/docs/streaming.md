---
title: Streaming
description: Stream AI responses in real-time for better user experience.
---

Streaming lets you present output as it's generated, improving perceived responsiveness.

## Basic streaming

```java
StringBuilder result = new StringBuilder();
ModelResponse response = genkit.generateStream(
    GenerateOptions.builder()
        .model("openai/gpt-4o")
        .prompt("Tell me a story")
        .build(),
    chunk -> {
        // Process each chunk as it arrives
        System.out.print(chunk.getText());
        result.append(chunk.getText());
    });
```

## How it works

1. Call `generateStream()` instead of `generate()`
2. Provide a callback that receives each chunk
3. Each chunk contains partial text as it's generated
4. The method returns the complete `ModelResponse` when done

## Use cases

- **Chat interfaces** — Display responses as they're typed
- **Long-form content** — Show progress during generation
- **Real-time applications** — Reduce time to first token
