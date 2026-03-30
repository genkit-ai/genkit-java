---
title: Firebase Vector Store
description: Use Firestore native vector similarity search for RAG applications.
---

Firestore supports native vector similarity search, making it a fully managed vector store for RAG applications. The Firebase plugin automatically handles embedding generation, document indexing, and vector queries.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-firebase</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Setting up a retriever

```java
import com.google.genkit.plugins.firebase.FirebasePlugin;
import com.google.genkit.plugins.firebase.retriever.FirestoreRetrieverConfig;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(FirebasePlugin.builder()
        .projectId("my-project")
        .addRetriever(FirestoreRetrieverConfig.builder()
            .name("my-docs")
            .collection("documents")
            .embedderName("googleai/text-embedding-004")
            .vectorField("embedding")
            .contentField("content")
            .distanceMeasure(FirestoreRetrieverConfig.DistanceMeasure.COSINE)
            .defaultLimit(5)
            .build())
        .build())
    .build();
```

## Indexing documents

Documents are embedded automatically when indexed. The plugin generates embeddings using the configured embedder and stores them alongside the content in Firestore.

```java
List<Document> docs = List.of(
    Document.fromText("Genkit is a framework for building AI apps"),
    Document.fromText("Firebase provides cloud services for apps")
);
genkit.index("firebase/my-docs", docs);
```

## Retrieving with vector similarity

```java
List<Document> results = genkit.retrieve("firebase/my-docs", "What is Genkit?");
```

## Using results in RAG

```java
genkit.defineFlow("ragQuery", String.class, String.class, (ctx, question) -> {
    List<Document> context = genkit.retrieve("firebase/my-docs", question);
    return genkit.generate(GenerateOptions.builder()
        .model("googleai/gemini-2.0-flash")
        .prompt(question)
        .docs(context)
        .build()).getText();
});
```

## Auto-creating database and vector index

The plugin can automatically create the Firestore database and the required composite vector index:

```java
FirestoreRetrieverConfig.builder()
    .name("my-docs")
    .collection("documents")
    .embedderName("googleai/text-embedding-004")
    .vectorField("embedding")
    .contentField("content")
    .createDatabaseIfNotExists(true)
    .createVectorIndexIfNotExists(true)
    .build()
```

You can also create the vector index manually via the Firebase CLI:

```bash
gcloud firestore indexes composite create \
  --collection-group=documents \
  --field-config=field-path=embedding,vector-config='{"dimension":"768","flat":{}}' \
  --database="(default)"
```

## Configuration options

| Option | Default | Description |
|--------|---------|-------------|
| `name` | (required) | Retriever name — used as `firebase/{name}` |
| `collection` | (required) | Firestore collection name |
| `embedderName` | (required) | Embedder to use (e.g., `googleai/text-embedding-004`) |
| `vectorField` | `"embedding"` | Firestore field storing the vector |
| `contentField` | `"content"` | Firestore field storing the text content |
| `distanceMeasure` | `COSINE` | `COSINE`, `EUCLIDEAN`, or `DOT_PRODUCT` |
| `defaultLimit` | `10` | Number of results to return |
| `distanceThreshold` | none | Maximum distance threshold |
| `metadataFields` | none | Additional fields to include in document metadata |
| `embedderDimension` | `768` | Embedding vector dimension |
| `databaseId` | `"(default)"` | Firestore database ID |
| `createDatabaseIfNotExists` | `false` | Auto-create Firestore database |
| `createVectorIndexIfNotExists` | `false` | Auto-create composite vector index |

## Requirements

- Firebase project on the Blaze (pay-as-you-go) plan
- Application Default Credentials or a service account JSON
- `GCLOUD_PROJECT` or `GOOGLE_CLOUD_PROJECT` environment variable (auto-detected)

## Sample

See the [firebase sample](https://github.com/xavidop/genkit-java/tree/main/samples/firebase) for a complete RAG pipeline with Firestore vector search.
