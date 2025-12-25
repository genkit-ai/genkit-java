# Genkit Pinecone Plugin

A [Genkit](https://github.com/firebase/genkit) plugin that provides vector database functionality using [Pinecone](https://www.pinecone.io/).

## Features

- **Serverless & Pod-based Indexes**: Support for both Pinecone deployment models
- **Namespace Support**: Multi-tenant applications with namespace isolation
- **Metadata Filtering**: Store and query with rich metadata
- **Batch Operations**: Efficient batch upsert for large document sets
- **Automatic Index Creation**: Optionally create indexes on startup

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-pinecone</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Prerequisites

1. A [Pinecone account](https://www.pinecone.io/)
2. A Pinecone API key
3. Java 21+

## Usage

### Basic Setup

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.pinecone.PineconePlugin;
import com.google.genkit.plugins.pinecone.PineconeIndexConfig;

// Initialize Genkit with Pinecone plugin
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(System.getenv("GOOGLE_GENAI_API_KEY")))
    .plugin(PineconePlugin.builder()
        .apiKey(System.getenv("PINECONE_API_KEY"))
        .addIndex(PineconeIndexConfig.builder()
            .indexName("my-index")
            .embedderName("googleai/text-embedding-004")
            .dimension(768)
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
    "/indexer/pinecone/my-index"
);

// Create documents
List<Document> documents = List.of(
    Document.builder()
        .content(List.of(DocumentPart.fromText("Pinecone is a vector database.")))
        .metadata(Map.of(
            "id", "doc-1",
            "source", "docs",
            "category", "databases"
        ))
        .build(),
    Document.builder()
        .content(List.of(DocumentPart.fromText("Vector search enables semantic similarity.")))
        .metadata(Map.of(
            "id", "doc-2",
            "source", "wiki",
            "category", "search"
        ))
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
    "/retriever/pinecone/my-index"
);

// Search for similar documents
RetrieverRequest request = RetrieverRequest.builder()
    .content("What is a vector database?")
    .options(RetrieverOptions.builder()
        .k(5)  // Return top 5 results
        .build())
    .build();

RetrieverResponse response = retriever.retrieve(request);

for (Document doc : response.getDocuments()) {
    System.out.println("Content: " + doc.getContent().get(0).getText());
    System.out.println("Score: " + doc.getMetadata().get("score"));
}
```

### Using Namespaces

```java
// Configure multiple namespaces
PineconePlugin plugin = PineconePlugin.builder()
    .apiKey(apiKey)
    .addIndex(PineconeIndexConfig.builder()
        .indexName("my-index")
        .namespace("production")
        .embedderName("googleai/text-embedding-004")
        .build())
    .addIndex(PineconeIndexConfig.builder()
        .indexName("my-index")
        .namespace("staging")
        .embedderName("googleai/text-embedding-004")
        .build())
    .build();

// Access by namespace
// /retriever/pinecone/my-index/production
// /retriever/pinecone/my-index/staging
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
String answer = ragFlow.run("What is Pinecone?");
```

## Configuration Options

### PineconeIndexConfig

| Option | Default | Description |
|--------|---------|-------------|
| `indexName` | (required) | Name of the Pinecone index |
| `embedderName` | (required) | Name of the embedder for vector generation |
| `namespace` | "" | Namespace for document isolation |
| `dimension` | 768 | Dimension of the embedding vectors |
| `metric` | COSINE | Similarity metric (COSINE, EUCLIDEAN, DOT_PRODUCT) |
| `cloud` | AWS | Cloud provider for serverless (AWS, GCP, AZURE) |
| `region` | "us-east-1" | Region for serverless index |
| `createIndexIfNotExists` | false | Auto-create index on startup |
| `textField` | "text" | Metadata field for document content |

### Similarity Metrics

```java
// Cosine similarity (default) - best for normalized vectors
PineconeIndexConfig.Metric.COSINE

// Euclidean distance - best for comparing actual distances
PineconeIndexConfig.Metric.EUCLIDEAN

// Dot product - best for maximum inner product search
PineconeIndexConfig.Metric.DOT_PRODUCT
```

### Cloud Providers

```java
// AWS (default)
PineconeIndexConfig.Cloud.AWS

// Google Cloud Platform
PineconeIndexConfig.Cloud.GCP

// Microsoft Azure
PineconeIndexConfig.Cloud.AZURE
```

## Creating Indexes

### Automatic Creation

```java
PineconeIndexConfig.builder()
    .indexName("new-index")
    .embedderName("googleai/text-embedding-004")
    .dimension(768)
    .metric(PineconeIndexConfig.Metric.COSINE)
    .cloud(PineconeIndexConfig.Cloud.AWS)
    .region("us-east-1")
    .createIndexIfNotExists(true)  // Enable auto-creation
    .build()
```

### Manual Creation

Create the index in the Pinecone console or using the Pinecone CLI before starting your application.

## Environment Variables

| Variable | Description |
|----------|-------------|
| `PINECONE_API_KEY` | Your Pinecone API key |

## Advanced Usage

### Direct Vector Store Access

```java
// Get the plugin
PineconePlugin plugin = // ...

// Access vector store directly
PineconeVectorStore vectorStore = plugin.getVectorStore("my-index");

// Delete specific documents
vectorStore.deleteByIds(List.of("doc-1", "doc-2"));

// Delete all documents in namespace
vectorStore.deleteAll();
```

### Custom Pinecone Client

```java
import io.pinecone.clients.Pinecone;

// Create custom client
Pinecone customClient = new Pinecone.Builder(apiKey)
    // Custom configuration
    .build();

// Use with plugin
PineconePlugin.builder()
    .client(customClient)
    .addIndex(...)
    .build()
```

## Best Practices

1. **Choose the Right Metric**: Use cosine for text embeddings, Euclidean for spatial data, dot product for recommendation systems.

2. **Namespaces**: Use namespaces to organize data by tenant, environment, or category.

3. **Batch Size**: The plugin automatically batches upserts at 100 vectors for optimal performance.

4. **Dimensions**: Match the dimension to your embedding model (e.g., 768 for text-embedding-004).

5. **Index Creation**: Set `createIndexIfNotExists` to `true` for development, but create indexes manually in production.

## Troubleshooting

### Index Not Found

```
Index my-index does not exist and createIndexIfNotExists is false
```

Solution: Create the index in Pinecone console or set `createIndexIfNotExists(true)`.

### Dimension Mismatch

```
Vector dimension does not match index dimension
```

Solution: Ensure the `dimension` in your config matches your embedding model and index.

### Rate Limiting

Pinecone has rate limits on operations. Implement backoff and retry logic for high-volume applications.

## License

Apache 2.0
