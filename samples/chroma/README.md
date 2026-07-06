# Chroma RAG Sample

A Retrieval-Augmented Generation sample that indexes film descriptions into **Chroma** via `ChromaPlugin` and answers questions with a **Gemini** model.

## Prerequisites

- Java 21+ and Maven 3.6+
- A `GEMINI_API_KEY` — get one from [Google AI Studio](https://aistudio.google.com/apikey)
- A reachable Chroma server (see the Docker command below)

## Run Chroma locally with Docker

```bash
docker run --detach \
  --name genkit-chroma \
  --publish 8000:8000 \
  chromadb/chroma

# wait until it's accepting connections
until curl -sf http://localhost:8000/api/v2/heartbeat >/dev/null 2>&1; do sleep 1; done
echo "chroma ready"
```

The collection is created automatically on first use. Stop and remove the container later with:

```bash
docker rm -f genkit-chroma
```

## Configure

```bash
export GEMINI_API_KEY=<your-key>
export CHROMA_URL=http://localhost:8000        # optional (default)
export CHROMA_COLLECTION=genkit_films          # optional (default)
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

## Configuration

`ChromaPlugin` talks to the Chroma v2 REST API. Tune per-collection settings with `ChromaCollectionConfig` (collection name, embedder, distance function, `createCollectionIfNotExists`, additional metadata).
