---
title: Milvus
description: Milvus vector database integration for Genkit RAG workflows.
---

The Milvus plugin registers retrievers and indexers backed by a [Milvus](https://milvus.io/) server (v2 REST API) for Retrieval-Augmented Generation.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-milvus</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Requirements

- A running Milvus server. For local development:
  ```bash
  curl -sfL https://raw.githubusercontent.com/milvus-io/milvus/master/scripts/standalone_embed.sh -o standalone_embed.sh
  bash standalone_embed.sh start
  ```
- Java 21+
- An embedder (e.g. from the Google GenAI plugin)

## Usage

`MilvusPlugin` registers a retriever and indexer named `milvus/<collectionName>` for each configured collection.

```java
import com.google.genkit.plugins.milvus.MilvusCollectionConfig;
import com.google.genkit.plugins.milvus.MilvusPlugin;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(
        MilvusPlugin.builder()
            .url("http://localhost:19530")   // default; serves both gRPC and REST
            .token(System.getenv("MILVUS_TOKEN")) // optional; required for auth-enabled servers / Zilliz Cloud
            .addCollection(
                MilvusCollectionConfig.builder()
                    .collectionName("films")
                    .embedderName("googleai/gemini-embedding-001")
                    .metric(MilvusCollectionConfig.Metric.COSINE)
                    .createCollectionIfNotExists(true) // default
                    .build())
            .build())
    .build();

// Index and retrieve
genkit.index("milvus/films", documents);
List<Document> results = genkit.retrieve("milvus/films", "a Christopher Nolan sci-fi film");
```

## Configuration

Tune per-collection settings with `MilvusCollectionConfig`:

- `collectionName` — the Milvus collection name (required)
- `embedderName` — the embedder used to vectorize documents and queries (required)
- `dimension` — the embedding dimension used when creating the collection (default `768`). The store also probes the embedder on first use and creates the collection with the model's actual output dimension, so this is only a fallback.
- `metric` — `COSINE` (default), `L2`, or `INNER_PRODUCT`
- `createCollectionIfNotExists` — auto-create the collection (default `true`)
- `addAdditionalMetadata(key, value)` — metadata merged into every indexed document

Collections are created in Milvus "quick setup" mode (auto id primary key, a `vector` field, and dynamic fields). The document text is stored under `text` and its metadata as a JSON string under `metadata`.

## Sample

See the [milvus sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/milvus).
