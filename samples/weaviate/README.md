# Weaviate RAG Sample

This sample demonstrates how to use Weaviate as a vector database for RAG (Retrieval Augmented Generation) workflows with Genkit.

## Prerequisites

1. **Weaviate Instance**: Either local or cloud-hosted
2. **Google GenAI API Key**: For embeddings and LLM

### Running Weaviate Locally

```bash
docker run -d \
  --name weaviate \
  -p 8080:8080 \
  -p 50051:50051 \
  -e QUERY_DEFAULTS_LIMIT=25 \
  -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true \
  -e PERSISTENCE_DATA_PATH=/var/lib/weaviate \
  -e DEFAULT_VECTORIZER_MODULE=none \
  -e CLUSTER_HOSTNAME=node1 \
  cr.weaviate.io/semitechnologies/weaviate:1.27.0
```

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `GEMINI_API_KEY` | Yes | Google GenAI API key |
| `WEAVIATE_HOST` | No | Weaviate host (default: localhost) |
| `WEAVIATE_PORT` | No | Weaviate HTTP port (default: 8080) |
| `WEAVIATE_GRPC_PORT` | No | Weaviate gRPC port (default: 50051) |
| `WEAVIATE_API_KEY` | No | API key for Weaviate Cloud |

## Running the Sample

### Using .env file (Recommended)

1. Copy the example environment file:
   ```bash
   cp .env.example .env
   ```

2. Edit `.env` and set your API key:
   ```
   GEMINI_API_KEY=your-api-key
   ```

3. Run the sample:
   ```bash
   ./run.sh
   ```

### Using Environment Variables

```bash
# Set required environment variables
export GEMINI_API_KEY="your-api-key"

# Optional: For Weaviate Cloud
# export WEAVIATE_HOST="your-cluster.weaviate.network"
# export WEAVIATE_API_KEY="your-weaviate-api-key"

# Run the sample
./run.sh
```

Or with Maven directly:

```bash
mvn exec:java -Dexec.mainClass="com.google.genkit.samples.weaviate.WeaviateRAGSample"
```

## Available Flows

### indexDocuments
Indexes sample documents about famous films into Weaviate.

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

1. Start the sample application
2. Index the sample documents:
   ```bash
   curl -X POST http://localhost:4000/api/flows/indexDocuments -H 'Content-Type: application/json' -d '{}'
   ```
3. Query for relevant documents:
   ```bash
   curl -X POST http://localhost:4000/api/flows/retrieveDocuments -H 'Content-Type: application/json' -d '{"data": "movies about dreams"}'
   ```
4. Ask questions using RAG:
   ```bash
   curl -X POST http://localhost:4000/api/flows/ragQuery -H 'Content-Type: application/json' -d '{"data": "Which films were directed by Christopher Nolan and what are they about?"}'
   ```

## Configuration

The sample uses:
- **Embedder**: `googleai/text-embedding-004` (768 dimensions)
- **LLM**: `googleai/gemini-2.0-flash`
- **Distance Metric**: Cosine similarity
- **Collection Name**: `Films`

You can modify these settings in `WeaviateRAGSample.java`.
