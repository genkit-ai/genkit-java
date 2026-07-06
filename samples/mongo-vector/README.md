# MongoDB Vector RAG Sample

A Retrieval-Augmented Generation sample that indexes film descriptions into **MongoDB Atlas Vector Search** via `MongoPlugin` and answers questions with a **Gemini** model.

## Prerequisites

- Java 21+ and Maven 3.6+
- A `GEMINI_API_KEY` — get one from [Google AI Studio](https://aistudio.google.com/apikey)
- A MongoDB deployment that supports Atlas Vector Search — a MongoDB Atlas cluster or the local Atlas image (see below). A plain `mongo` server does **not** support `$vectorSearch`.

## Run MongoDB Atlas locally with Docker

Use the official Atlas Local image, which bundles the search/vector engine:

```bash
docker run --detach \
  --name genkit-atlas \
  --publish 27017:27017 \
  mongodb/mongodb-atlas-local:latest

# wait until it's accepting connections
until docker exec genkit-atlas mongosh --quiet --eval 'db.runCommand({ ping: 1 })' >/dev/null 2>&1; do sleep 1; done
echo "atlas-local ready"
```

The database (`genkit`), collection (`films`), and the Atlas Vector Search index are created automatically on first use. The index takes a few seconds to become queryable. Stop and remove the container later with:

```bash
docker rm -f genkit-atlas
```

## Configure

```bash
export GEMINI_API_KEY=<your-key>
export MONGO_URI="mongodb://localhost:27017/?directConnection=true"   # optional (default)
export MONGO_DATABASE=genkit                                          # optional (default)
export MONGO_COLLECTION=films                                         # optional (default)
```

For a MongoDB Atlas cluster, set `MONGO_URI` to your `mongodb+srv://...` connection string.

## Run

```bash
mvn -q exec:java
```

Then open the Genkit Dev UI at http://localhost:4000 (or run under `genkit start -- mvn -q exec:java`) and exercise the flows:

- `indexDocuments` — index the sample film descriptions (also creates the vector index on first run)
- `retrieveDocuments` — return the films matching a query
- `ragQuery` — answer a question using retrieved context

Or via curl:

```bash
curl -X POST http://localhost:8080/api/flows/indexDocuments -H 'Content-Type: application/json' -d '{}'
curl -X POST http://localhost:8080/api/flows/ragQuery -H 'Content-Type: application/json' \
  -d '{"data": "What Christopher Nolan films are mentioned?"}'
```

## Configuration

`MongoPlugin` uses the `$vectorSearch` aggregation stage. Tune per-collection settings with `MongoVectorStoreConfig` (database/collection names, embedder, index name, dimension, similarity, text/embedding field names, `numCandidates`, `createIndexIfNotExists`).
