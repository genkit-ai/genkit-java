---
title: Pinecone
description: Managed vector database for RAG applications.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-pinecone</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export PINECONE_API_KEY=your-api-key
```

## Usage

```java
import com.google.genkit.plugins.pinecone.PineconePlugin;

Genkit genkit = Genkit.builder()
    .plugin(PineconePlugin.create())
    .build();
```

## Features

- Serverless and Pod-based indexes
- Namespace support
- Metadata filtering
- Batch operations
- Similarity search

## Sample

See the [pinecone sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/pinecone).
