---
title: Local Vector Store
description: File-based vector store for local development and testing.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-localvec</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

The local vector store is ideal for development and testing. It stores vectors in local files.

```java
import com.google.genkit.plugins.localvec.LocalVecPlugin;

Genkit genkit = Genkit.builder()
    .plugin(LocalVecPlugin.create())
    .build();
```

## When to use

- Local development and prototyping
- Unit testing RAG pipelines
- Quick experiments without external dependencies

For production, use [Weaviate](/genkit-java/plugins/weaviate/), [PostgreSQL](/genkit-java/plugins/postgresql/), [Pinecone](/genkit-java/plugins/pinecone/), or [Firebase Firestore](/genkit-java/plugins/firebase/).

## Sample

See the [rag sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/rag).
