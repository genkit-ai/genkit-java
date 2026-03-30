---
title: Weaviate
description: Vector similarity search with Weaviate for RAG applications.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-weaviate</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

### Local Docker deployment

```java
import com.google.genkit.plugins.weaviate.WeaviatePlugin;

Genkit genkit = Genkit.builder()
    .plugin(WeaviatePlugin.createLocal("http://localhost:8080"))
    .build();
```

### Weaviate Cloud

```java
Genkit genkit = Genkit.builder()
    .plugin(WeaviatePlugin.createCloud(
        "https://your-cluster.weaviate.network",
        "your-api-key"))
    .build();
```

## Features

- Configurable distance measures (COSINE, L2_SQUARED, DOT)
- Batch indexing
- Automatic collection creation
- Flexible retrieval

## Sample

See the [weaviate sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/weaviate).
