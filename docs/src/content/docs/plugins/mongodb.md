---
title: MongoDB
description: MongoDB integration for Genkit - Atlas Vector Search retrieval and agent session persistence.
---

The MongoDB plugin provides two capabilities: `MongoPlugin` registers Atlas Vector Search retrievers/indexers for RAG, and `MongoSessionStore` persists server-managed agent sessions.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-mongodb</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Requirements

- MongoDB 4.4+ (Atlas Vector Search requires MongoDB Atlas or the `mongodb/mongodb-atlas-local` Docker image)
- Java 21+

## Vector search

`MongoPlugin` registers a retriever and indexer named `mongodb/<collectionName>` for each configured collection, backed by an [Atlas Vector Search](https://www.mongodb.com/docs/atlas/atlas-vector-search/) index and the `$vectorSearch` aggregation stage. A plain MongoDB server does **not** support `$vectorSearch` — use a MongoDB Atlas cluster or the `mongodb/mongodb-atlas-local` Docker image.

```java
import com.google.genkit.plugins.mongodb.MongoPlugin;
import com.google.genkit.plugins.mongodb.MongoVectorStoreConfig;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(
        MongoPlugin.builder()
            .connectionString("mongodb://localhost:27017/?directConnection=true")
            .addCollection(
                MongoVectorStoreConfig.builder()
                    .collectionName("films")
                    .embedderName("googleai/gemini-embedding-001")
                    .dimension(768)
                    .similarity(MongoVectorStoreConfig.Similarity.COSINE)
                    .createIndexIfNotExists(true) // create the Atlas Vector Search index on first use
                    .build())
            .build())
    .build();

// Index and retrieve
genkit.index("mongodb/films", documents);
List<Document> results = genkit.retrieve("mongodb/films", "a Christopher Nolan sci-fi film");
```

Tune per-collection settings with `MongoVectorStoreConfig` (database/collection names, embedder, index name, dimension, similarity — `COSINE`/`EUCLIDEAN`/`DOT_PRODUCT`, text/embedding field names, `numCandidates`, `createIndexIfNotExists`). See the [mongo-vector sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/mongo-vector).

## Session store

Construct a `MongoSessionStore` from a `com.mongodb.client.MongoClient` and pass it to an agent's `.store(...)` to persist server-managed sessions in MongoDB:

```java
import com.google.genkit.plugins.mongodb.session.MongoSessionStore;
import com.google.genkit.plugins.mongodb.session.MongoSessionStoreOptions;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

MongoClient client = MongoClients.create("mongodb://localhost:27017");

MongoSessionStore<Map<String, Object>> store =
    new MongoSessionStore<>(client); // uses database "genkit", collection "genkit_sessions"

Agent<Map<String, Object>> agent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("myAgent")
        .system("You are a helpful assistant.")
        .store(store)
        .build());
```

All records live in a single collection whose `_id` is `<prefix>::<recordId>`; the database and collection are created automatically on first write. It uses the same sharded checkpoint + RFC-6902 diff + pointer layout as the Firestore, DynamoDB, Cosmos DB, and PostgreSQL backends, and supports `onSnapshotStateChange` (via polling), so `chat.abort()` works.

See [Session Stores](../../agents/session-stores#mongosessionstore) for options.

## Sample

See the [agents-mongo-session sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/agents-mongo-session) and the [mongo-vector sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/mongo-vector).
