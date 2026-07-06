---
title: Chroma
description: Chroma vector database integration for Genkit RAG workflows.
---

The Chroma plugin registers retrievers and indexers backed by a [Chroma](https://www.trychroma.com/) server (v2 REST API) for Retrieval-Augmented Generation.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-chroma</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Requirements

- A running Chroma server (`docker run -p 8000:8000 chromadb/chroma`)
- Java 21+
- An embedder (e.g. from the Google GenAI plugin)

## Usage

`ChromaPlugin` registers a retriever and indexer named `chroma/<collectionName>` for each configured collection.

```java
import com.google.genkit.plugins.chroma.ChromaCollectionConfig;
import com.google.genkit.plugins.chroma.ChromaPlugin;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(
        ChromaPlugin.builder()
            .url("http://localhost:8000")   // default
            .addCollection(
                ChromaCollectionConfig.builder()
                    .collectionName("films")
                    .embedderName("googleai/gemini-embedding-001")
                    .distance(ChromaCollectionConfig.Distance.COSINE)
                    .createCollectionIfNotExists(true) // default
                    .build())
            .build())
    .build();

// Index and retrieve
genkit.index("chroma/films", documents);
List<Document> results = genkit.retrieve("chroma/films", "a Christopher Nolan sci-fi film");
```

## Configuration

Tune per-collection settings with `ChromaCollectionConfig`:

- `collectionName` — the Chroma collection name (required)
- `embedderName` — the embedder used to vectorize documents and queries (required)
- `distance` — `COSINE` (default), `L2`, or `INNER_PRODUCT`
- `createCollectionIfNotExists` — auto-create the collection (default `true`)
- `addAdditionalMetadata(key, value)` — metadata merged into every indexed document

Plugin-level `tenant` and `database` default to `default_tenant` / `default_database`.

## Sample

See the [chroma sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/chroma).
