# Qdrant RAG Sample

A Retrieval-Augmented Generation sample that indexes film descriptions into **Qdrant** via `QdrantPlugin` and answers questions with a **Gemini** model.

## Prerequisites

- Java 21+ and Maven 3.6+
- A `GEMINI_API_KEY` — get one from [Google AI Studio](https://aistudio.google.com/apikey)
- A reachable Qdrant server (see the Docker command below)

## Run Qdrant locally with Docker

```bash
docker run --detach \
  --name genkit-qdrant \
  --publish 6333:6333 \
  qdrant/qdrant

# wait until it's accepting connections
until curl -sf http://localhost:6333/readyz >/dev/null 2>&1; do sleep 1; done
echo "qdrant ready"
```

The collection is created automatically on first use (dimension 768, cosine distance). Stop and remove the container later with:

```bash
docker rm -f genkit-qdrant
```

## Configure

```bash
export GEMINI_API_KEY=<your-key>
export QDRANT_URL=http://localhost:6333        # optional (default)
export QDRANT_API_KEY=<your-key>               # optional (required for Qdrant Cloud)
export QDRANT_COLLECTION=genkit_films          # optional (default)
```

## Run

```bash
mvn -q exec:java
```

Then open the Genkit Dev UI at http://localhost:4000 (or run under `genkit start -- mvn -q exec:java`) and exercise the flows:

- `indexDocuments` — index the sample film descriptions
- `retrieveDocuments` — return the films matching a query
- `ragQuery` — answer a question using retrieved context

Or via curl:

```bash
curl -X POST http://localhost:4000/api/flows/indexDocuments -H 'Content-Type: application/json' -d '{}'
curl -X POST http://localhost:4000/api/flows/ragQuery -H 'Content-Type: application/json' \
  -d '{"data": "What Christopher Nolan films are mentioned?"}'
```

Inspect stored points in the Qdrant dashboard at http://localhost:6333/dashboard.

## Configuration

`QdrantPlugin` talks to the Qdrant REST API. Tune per-collection settings with `QdrantCollectionConfig` (collection name, embedder, dimension, distance function, text payload key, `createCollectionIfNotExists`, additional metadata).
