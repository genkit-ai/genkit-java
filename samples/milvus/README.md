# Milvus RAG Sample

A Retrieval-Augmented Generation sample that indexes film descriptions into **Milvus** via `MilvusPlugin` and answers questions with a **Gemini** model.

## Prerequisites

- Java 21+ and Maven 3.6+
- A `GEMINI_API_KEY` — get one from [Google AI Studio](https://aistudio.google.com/apikey)
- A reachable Milvus server (see the Docker command below)

## Run Milvus locally with Docker

Milvus ships a helper script that runs a single standalone container (with embedded etcd + local storage):

```bash
curl -sfL https://raw.githubusercontent.com/milvus-io/milvus/master/scripts/standalone_embed.sh -o standalone_embed.sh
bash standalone_embed.sh start

# wait until it's accepting connections (health probe on 9091)
until curl -sf http://localhost:9091/healthz >/dev/null 2>&1; do sleep 1; done
echo "milvus ready"
```

Milvus serves both gRPC and the REST API on port `19530`. The collection is created automatically on first use. Stop and remove it later with:

```bash
bash standalone_embed.sh stop && bash standalone_embed.sh delete
```

## Configure

```bash
export GEMINI_API_KEY=<your-key>
export MILVUS_URL=http://localhost:19530     # optional (default)
export MILVUS_TOKEN=<user:password>          # optional (required for auth-enabled servers / Zilliz Cloud)
export MILVUS_COLLECTION=genkit_films        # optional (default)
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

`MilvusPlugin` talks to the Milvus v2 REST API. Tune per-collection settings with `MilvusCollectionConfig` (collection name, embedder, dimension, metric — `COSINE`/`L2`/`INNER_PRODUCT`, `createCollectionIfNotExists`, additional metadata). Collections are created in quick-setup mode; the document text is stored under `text` and metadata as a JSON string under `metadata`.
