# PostgreSQL RAG Sample

This sample demonstrates how to use PostgreSQL with pgvector as a vector database for RAG (Retrieval Augmented Generation) workflows with Genkit.

## Prerequisites

1. **PostgreSQL 12+** with pgvector extension
2. **Google GenAI API Key**: For embeddings and LLM

### Running PostgreSQL with pgvector Locally

```bash
docker run -d \
  --name postgres-pgvector \
  -p 5432:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=genkit \
  pgvector/pgvector:pg16
```

The pgvector extension will be automatically enabled by the sample.

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GEMINI_API_KEY` | Yes | - | Google GenAI API key |
| `POSTGRES_HOST` | No | localhost | PostgreSQL host |
| `POSTGRES_PORT` | No | 5432 | PostgreSQL port |
| `POSTGRES_DB` | No | genkit | Database name |
| `POSTGRES_USER` | No | postgres | Database username |
| `POSTGRES_PASSWORD` | No | postgres | Database password |

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

# Optional: Custom PostgreSQL connection
# export POSTGRES_HOST="localhost"
# export POSTGRES_PORT="5432"
# export POSTGRES_DB="genkit"
# export POSTGRES_USER="postgres"
# export POSTGRES_PASSWORD="postgres"

# Run the sample
./run.sh
```

Or with Maven directly:

```bash
mvn exec:java -Dexec.mainClass="com.google.genkit.samples.postgresql.PostgresRAGSample"
```

## Available Flows

### indexDocuments
Indexes sample documents about famous films into PostgreSQL.

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

## Database Schema

The sample creates the following table structure:

```sql
CREATE TABLE films (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT,
    embedding vector(768),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX films_embedding_idx ON films 
USING ivfflat (embedding vector_cosine_ops) 
WITH (lists = 100);
```

## Configuration

The sample uses:
- **Embedder**: `googleai/text-embedding-004` (768 dimensions)
- **LLM**: `googleai/gemini-2.0-flash`
- **Distance Strategy**: Cosine distance
- **Table Name**: `films`
- **Index Type**: IVFFlat with 100 lists

You can modify these settings in `PostgresRAGSample.java`.

## Troubleshooting

### pgvector extension not found

```
ERROR: type "vector" does not exist
```

Enable the extension manually:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### Connection refused

Make sure PostgreSQL is running and accessible:
```bash
docker ps | grep postgres
```

### Permission denied

Ensure the database user has permissions to create extensions:
```sql
GRANT ALL ON DATABASE genkit TO postgres;
```
