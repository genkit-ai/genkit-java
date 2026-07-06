---
title: Qdrant
description: Qdrant vector database integration for Genkit RAG workflows.
---

The Qdrant plugin registers retrievers and indexers backed by a [Qdrant](https://qdrant.tech/) server (REST API) for Retrieval-Augmented Generation.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-qdrant</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Requirements

- A running Qdrant server (`docker run -p 6333:6333 qdrant/qdrant`)
- Java 21+
- An embedder (e.g. from the Google GenAI plugin)

## Usage

`QdrantPlugin` registers a retriever and indexer named `qdrant/<collectionName>` for each configured collection.

```java
import com.google.genkit.plugins.qdrant.QdrantCollectionConfig;
import com.google.genkit.plugins.qdrant.QdrantPlugin;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(
        QdrantPlugin.builder()
            .url("http://localhost:6333")   // default
            .apiKey(System.getenv("QDRANT_API_KEY")) // optional; required for Qdrant Cloud
            .addCollection(
                QdrantCollectionConfig.builder()
                    .collectionName("films")
                    .embedderName("googleai/gemini-embedding-001")
                    .dimension(768)
                    .distance(QdrantCollectionConfig.Distance.COSINE)
                    .createCollectionIfNotExists(true) // default
                    .build())
            .build())
    .build();

// Index and retrieve
genkit.index("qdrant/films", documents);
List<Document> results = genkit.retrieve("qdrant/films", "a Christopher Nolan sci-fi film");
```

## Configuration

Tune per-collection settings with `QdrantCollectionConfig`:

- `collectionName` — the Qdrant collection name (required)
- `embedderName` — the embedder used to vectorize documents and queries (required)
- `dimension` — the embedding dimension used when creating the collection (default `768`). The store also probes the embedder on first use and creates the collection with the model's actual output dimension, so this is only a fallback.
- `distance` — `COSINE` (default), `EUCLIDEAN`, or `DOT_PRODUCT`
- `textPayloadKey` — the payload key that stores the document text (default `text`)
- `createCollectionIfNotExists` — auto-create the collection (default `true`)
- `addAdditionalMetadata(key, value)` — metadata merged into every indexed point's payload

Document ids that are not integers or UUIDs are hashed into a stable UUID (Qdrant point ids must be an unsigned integer or a UUID).

## Sample

See the [qdrant sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/qdrant).
