# Weaviate Plugin for Genkit Java

The Weaviate plugin provides indexer and retriever implementations that use [Weaviate](https://weaviate.io/) vector database for similarity search in RAG (Retrieval Augmented Generation) workflows.

## Features

- Vector similarity search using Weaviate's native capabilities
- Automatic collection (class) creation with configurable schema
- Support for both local and Weaviate Cloud instances
- Configurable distance measures (COSINE, L2_SQUARED, DOT)
- Batch document indexing
- Flexible retrieval with limit and distance threshold options

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-weaviate</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

## Prerequisites

### Local Development

Run Weaviate locally using Docker:

```bash
docker run -d \
  -p 8080:8080 \
  -p 50051:50051 \
  --name weaviate \
  -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true \
  -e PERSISTENCE_DATA_PATH=/var/lib/weaviate \
  -e QUERY_DEFAULTS_LIMIT=25 \
  -e DEFAULT_VECTORIZER_MODULE=none \
  -e CLUSTER_HOSTNAME=node1 \
  cr.weaviate.io/semitechnologies/weaviate:latest
```

### Weaviate Cloud

For Weaviate Cloud, you'll need:
- A Weaviate Cloud cluster URL
- An API key for authentication

## Configuration

### Basic Local Configuration

```java
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(WeaviatePlugin.local()
        .addCollection(WeaviateCollectionConfig.builder()
            .name("documents")
            .embedderName("googleai/text-embedding-004")
            .build())
        .build())
    .build();
```

### Weaviate Cloud Configuration

```java
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(WeaviatePlugin.builder()
        .host("your-cluster.weaviate.network")
        .port(443)
        .secure(true)
        .apiKey(System.getenv("WEAVIATE_API_KEY"))
        .addCollection(WeaviateCollectionConfig.builder()
            .name("documents")
            .embedderName("googleai/text-embedding-004")
            .distanceMeasure(WeaviateCollectionConfig.DistanceMeasure.COSINE)
            .createCollectionIfMissing(true)
            .vectorDimension(768)
            .build())
        .build())
    .build();
```

### Collection Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `name` | Collection name (required) | - |
| `embedderName` | Embedder reference name | - |
| `embedder` | Direct embedder instance | - |
| `contentField` | Field name for document content | `content` |
| `metadataField` | Field name for metadata JSON | `metadata` |
| `distanceMeasure` | Distance measure (COSINE, L2_SQUARED, DOT) | `COSINE` |
| `defaultLimit` | Default number of results to return | `10` |
| `createCollectionIfMissing` | Auto-create collection if missing | `true` |
| `vectorDimension` | Vector dimension for embeddings | `768` |

## Usage

### Indexing Documents

```java
// Index documents
List<Document> documents = List.of(
    Document.fromText("Weaviate is a vector database"),
    Document.fromText("Genkit is an AI framework")
);

genkit.index("weaviate/documents", documents);
```

### Retrieving Documents

```java
// Basic retrieval
List<Document> results = genkit.retrieve("weaviate/documents", "vector database");

// With options
RetrieverResponse response = genkit.retrieve("weaviate/documents", 
    Document.fromText("vector database"),
    Map.of("k", 5, "distance", 0.7));
```

### RAG Workflow

```java
// Define a RAG flow
Flow<String, String, Void> ragFlow = genkit.defineFlow("ragQuery", 
    String.class, String.class, (ctx, question) -> {
    
    // Retrieve relevant documents
    List<Document> docs = genkit.retrieve("weaviate/documents", question);
    
    // Generate answer using context
    ModelResponse response = genkit.generate(GenerateOptions.builder()
        .model("googleai/gemini-2.0-flash")
        .prompt(question)
        .docs(docs)
        .build());
    
    return response.getText();
});
```

## Distance Measures

- **COSINE**: Cosine similarity (default, recommended for text embeddings)
- **L2_SQUARED**: Euclidean distance squared
- **DOT**: Dot product (use with normalized vectors)

## License

Apache 2.0 - See [LICENSE](../../LICENSE) for details.
