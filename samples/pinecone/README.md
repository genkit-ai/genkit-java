# Pinecone RAG Sample

This sample demonstrates how to use Pinecone as a vector database for RAG (Retrieval Augmented Generation) workflows with Genkit.

## Prerequisites

1. **Pinecone Account**: Sign up at [pinecone.io](https://www.pinecone.io/)
2. **Pinecone API Key**: Get from the Pinecone console
3. **Google GenAI API Key**: For embeddings and LLM

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GEMINI_API_KEY` | Yes | - | Google GenAI API key |
| `PINECONE_API_KEY` | Yes | - | Pinecone API key |
| `PINECONE_INDEX_NAME` | No | genkit-films | Name of the Pinecone index |
| `PINECONE_CLOUD` | No | aws | Cloud provider (aws, gcp, azure) |
| `PINECONE_REGION` | No | us-east-1 | Region for the index |

## Running the Sample

### Using .env file (Recommended)

1. Copy the example environment file:
   ```bash
   cp .env.example .env
   ```

2. Edit `.env` and set your API keys:
   ```
   GEMINI_API_KEY=your-gemini-api-key
   PINECONE_API_KEY=your-pinecone-api-key
   ```

3. Run the sample:
   ```bash
   ./run.sh
   ```

### Using Environment Variables

```bash
# Set required environment variables
export GEMINI_API_KEY="your-gemini-api-key"
export PINECONE_API_KEY="your-pinecone-api-key"

# Optional: Custom index name
# export PINECONE_INDEX_NAME="my-custom-index"

# Run the sample
./run.sh
```

Or with Maven directly:

```bash
mvn exec:java -Dexec.mainClass="com.google.genkit.samples.pinecone.PineconeRAGSample"
```

## Index Setup

The sample is configured to **NOT** auto-create indexes by default. You have two options:

### Option 1: Create Index in Pinecone Console

1. Go to [Pinecone Console](https://app.pinecone.io/)
2. Create a new index with:
   - Name: `genkit-films` (or your custom name)
   - Dimensions: `768`
   - Metric: `cosine`
   - Cloud: Your preferred cloud provider

### Option 2: Enable Auto-Creation

Modify the sample code to set `createIndexIfNotExists(true)`:
```java
.addIndex(PineconeIndexConfig.builder()
    .indexName(indexName)
    .createIndexIfNotExists(true)  // Enable auto-creation
    // ...
    .build())
```

## Available Flows

### indexDocuments
Indexes sample documents about famous films into Pinecone.

```bash
curl -X POST http://localhost:4000/api/flows/indexDocuments \
  -H 'Content-Type: application/json' \
  -d '{}'
```

### retrieveDocuments
Retrieves documents matching a semantic query.

```bash
curl -X POST http://localhost:4000/api/flows/retrieveDocuments \
  -H 'Content-Type: application/json' \
  -d '{"data": "sci-fi movies"}'
```

### ragQuery
Answers questions using RAG with retrieved context.

```bash
curl -X POST http://localhost:4000/api/flows/ragQuery \
  -H 'Content-Type: application/json' \
  -d '{"data": "What Christopher Nolan films are mentioned?"}'
```

## Example Workflow

1. Create an index in Pinecone (see Index Setup above)
2. Start the sample application
3. Index the sample documents:
   ```bash
   curl -X POST http://localhost:4000/api/flows/indexDocuments -H 'Content-Type: application/json' -d '{}'
   ```
4. Query for relevant documents:
   ```bash
   curl -X POST http://localhost:4000/api/flows/retrieveDocuments -H 'Content-Type: application/json' -d '{"data": "movies about dreams"}'
   ```
5. Ask questions using RAG:
   ```bash
   curl -X POST http://localhost:4000/api/flows/ragQuery -H 'Content-Type: application/json' -d '{"data": "Which films were directed by Christopher Nolan and what are they about?"}'
   ```

## Configuration

The sample uses:
- **Embedder**: `googleai/text-embedding-004` (768 dimensions)
- **LLM**: `googleai/gemini-2.0-flash`
- **Metric**: Cosine similarity
- **Index Type**: Serverless (AWS us-east-1)

You can modify these settings in `PineconeRAGSample.java`.

## Using Namespaces

Pinecone supports namespaces for multi-tenant applications. To use namespaces:

```java
.addIndex(PineconeIndexConfig.builder()
    .indexName("my-index")
    .namespace("production")  // Add namespace
    .embedderName("googleai/text-embedding-004")
    .build())
```

The retriever/indexer will be available at `/retriever/pinecone/my-index/production`.

## Troubleshooting

### Index not found

```
Index genkit-films does not exist and createIndexIfNotExists is false
```

Either create the index in the Pinecone console or enable auto-creation.

### Dimension mismatch

```
Vector dimension does not match index dimension
```

Ensure your index was created with 768 dimensions to match `text-embedding-004`.

### Rate limiting

Pinecone has rate limits on operations. For high-volume applications, implement retry logic with exponential backoff.

## Cost Considerations

Pinecone charges based on:
- Number of vectors stored
- Number of queries
- Pod type (for pod-based indexes)

For development, serverless indexes on the free tier are recommended.
