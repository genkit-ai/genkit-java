# Genkit PostgreSQL Plugin

A [Genkit](https://github.com/firebase/genkit) plugin that provides vector database functionality using PostgreSQL with the [pgvector](https://github.com/pgvector/pgvector) extension.

## Features

- **Vector Similarity Search**: Full support for cosine, L2 (Euclidean), and inner product distance metrics
- **Automatic Schema Management**: Creates tables and indexes automatically
- **Connection Pooling**: Built-in HikariCP connection pool for optimal performance
- **Batch Indexing**: Efficient batch operations for indexing large document sets
- **Metadata Support**: Store and retrieve arbitrary JSON metadata with documents

## Prerequisites

1. **PostgreSQL 12+** with the pgvector extension installed
2. **Java 21+**

### Installing pgvector

```sql
-- Connect to PostgreSQL as superuser
CREATE EXTENSION IF NOT EXISTS vector;
```

For installation instructions, see the [pgvector documentation](https://github.com/pgvector/pgvector#installation).

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-postgresql</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

### Basic Setup

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.postgresql.PostgresPlugin;
import com.google.genkit.plugins.postgresql.PostgresTableConfig;

// Initialize Genkit with PostgreSQL plugin
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(System.getenv("GOOGLE_GENAI_API_KEY")))
    .plugin(PostgresPlugin.builder()
        .connectionString("jdbc:postgresql://localhost:5432/mydb")
        .username("user")
        .password("pass")
        .addTable(PostgresTableConfig.builder()
            .tableName("documents")
            .embedderName("googleai/text-embedding-004")
            .vectorDimension(768)
            .build())
        .build())
    .build();
```

### Indexing Documents

```java
import com.google.genkit.ai.model.Document;
import com.google.genkit.ai.model.DocumentPart;
import com.google.genkit.ai.retriever.Indexer;
import com.google.genkit.ai.retriever.IndexerRequest;

// Get the indexer
Indexer indexer = genkit.registry().lookupAction(
    Indexer.class, 
    "/indexer/postgresql/documents"
);

// Create documents
List<Document> documents = List.of(
    Document.builder()
        .content(List.of(DocumentPart.fromText("PostgreSQL is an advanced open source database.")))
        .metadata(Map.of("source", "wiki", "category", "databases"))
        .build(),
    Document.builder()
        .content(List.of(DocumentPart.fromText("pgvector adds vector similarity search to PostgreSQL.")))
        .metadata(Map.of("source", "docs", "category", "extensions"))
        .build()
);

// Index documents
IndexerRequest request = IndexerRequest.builder()
    .documents(documents)
    .build();

indexer.index(request);
```

### Retrieving Documents

```java
import com.google.genkit.ai.retriever.Retriever;
import com.google.genkit.ai.retriever.RetrieverRequest;
import com.google.genkit.ai.retriever.RetrieverResponse;
import com.google.genkit.ai.retriever.RetrieverOptions;

// Get the retriever
Retriever retriever = genkit.registry().lookupAction(
    Retriever.class,
    "/retriever/postgresql/documents"
);

// Search for similar documents
RetrieverRequest request = RetrieverRequest.builder()
    .content("What is pgvector?")
    .options(RetrieverOptions.builder()
        .k(5)  // Return top 5 results
        .build())
    .build();

RetrieverResponse response = retriever.retrieve(request);

for (Document doc : response.getDocuments()) {
    System.out.println("Content: " + doc.getContent().get(0).getText());
    System.out.println("Distance: " + doc.getMetadata().get("distance"));
}
```

### Using in a RAG Flow

```java
import com.google.genkit.ai.model.GenerateRequest;
import com.google.genkit.ai.model.GenerateResponse;

// Define a RAG flow
var ragFlow = genkit.defineFlow("ragFlow", String.class, String.class, (context, query) -> {
    // Retrieve relevant documents
    RetrieverRequest retrieverRequest = RetrieverRequest.builder()
        .content(query)
        .options(RetrieverOptions.builder().k(3).build())
        .build();
    
    RetrieverResponse retrieverResponse = retriever.retrieve(retrieverRequest);
    
    // Build context from retrieved documents
    StringBuilder contextBuilder = new StringBuilder();
    for (Document doc : retrieverResponse.getDocuments()) {
        contextBuilder.append(doc.getContent().get(0).getText()).append("\n\n");
    }
    
    // Generate response using LLM
    GenerateRequest generateRequest = GenerateRequest.builder()
        .model("googleai/gemini-2.0-flash")
        .messages(List.of(
            Message.builder()
                .role(Role.USER)
                .content(List.of(Part.fromText(
                    "Based on the following context, answer the question.\n\n" +
                    "Context:\n" + contextBuilder.toString() + "\n\n" +
                    "Question: " + query
                )))
                .build()
        ))
        .build();
    
    GenerateResponse generateResponse = genkit.generate(generateRequest);
    return generateResponse.getText();
});

// Execute the flow
String answer = ragFlow.run("How does pgvector work?");
```

## Configuration Options

### PostgresTableConfig

| Option | Default | Description |
|--------|---------|-------------|
| `tableName` | (required) | Name of the PostgreSQL table |
| `embedderName` | (required) | Name of the embedder for vector generation |
| `vectorDimension` | 768 | Dimension of the embedding vectors |
| `distanceStrategy` | COSINE | Distance metric (COSINE, L2, INNER_PRODUCT) |
| `idColumn` | "id" | Name of the ID column |
| `contentColumn` | "content" | Name of the content column |
| `embeddingColumn` | "embedding" | Name of the embedding column |
| `metadataColumn` | "metadata" | Name of the metadata column |
| `createTableIfNotExists` | true | Auto-create table on startup |
| `createIndexIfNotExists` | true | Auto-create vector index on startup |
| `indexLists` | 100 | Number of IVFFlat index lists |

### Distance Strategies

```java
// Cosine distance (default) - best for normalized vectors
PostgresTableConfig.DistanceStrategy.COSINE

// L2 (Euclidean) distance - best for comparing actual distances
PostgresTableConfig.DistanceStrategy.L2

// Inner product - best when vectors are not normalized
PostgresTableConfig.DistanceStrategy.INNER_PRODUCT
```

### Connection Pool Configuration

```java
PostgresPlugin.builder()
    .connectionString("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("pass")
    .hikariProperty("maximumPoolSize", 20)
    .hikariProperty("minimumIdle", 5)
    .hikariProperty("connectionTimeout", 30000)
    .hikariProperty("idleTimeout", 600000)
    .hikariProperty("maxLifetime", 1800000)
    .addTable(...)
    .build()
```

### Using External DataSource

```java
// Use your own DataSource (e.g., from Spring or another DI container)
DataSource myDataSource = // ... your DataSource

PostgresPlugin.builder()
    .dataSource(myDataSource)
    .addTable(...)
    .build()
```

## Table Schema

The plugin creates tables with the following schema:

```sql
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT,
    embedding vector(768),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX documents_embedding_idx ON documents 
USING ivfflat (embedding vector_cosine_ops) 
WITH (lists = 100);
```

## Environment Variables

| Variable | Description |
|----------|-------------|
| `POSTGRES_HOST` | PostgreSQL host |
| `POSTGRES_PORT` | PostgreSQL port |
| `POSTGRES_DB` | Database name |
| `POSTGRES_USER` | Database username |
| `POSTGRES_PASSWORD` | Database password |

## Best Practices

1. **Index Tuning**: Adjust `indexLists` based on your dataset size. Use `rows / 1000` as a starting point.

2. **Connection Pooling**: Configure pool size based on your workload. A good starting point is `2 * CPU cores + 1`.

3. **Batch Operations**: When indexing many documents, the plugin automatically batches operations for efficiency.

4. **Distance Metrics**: Choose the appropriate distance metric for your use case:
   - COSINE: General text similarity
   - L2: When actual distance matters
   - INNER_PRODUCT: For pre-normalized embeddings

## Troubleshooting

### Extension Not Found

```
ERROR: type "vector" does not exist
```

Solution: Install the pgvector extension:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### Connection Pool Exhausted

```
HikariPool-1 - Connection is not available
```

Solution: Increase `maximumPoolSize` or check for connection leaks.

### Index Not Used

If queries are slow, verify the index is being used:
```sql
EXPLAIN ANALYZE SELECT * FROM documents 
ORDER BY embedding <=> '[...]' 
LIMIT 10;
```

## License

Apache 2.0
