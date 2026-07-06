# Agents: PostgreSQL Session Sample

A server-managed assistant agent whose conversation state is persisted in **PostgreSQL** via `PostgresSessionStore`, using a **Gemini** model for generation.

## Prerequisites

- Java 21+ and Maven 3.6+
- A `GEMINI_API_KEY` (for live model calls) — get one from [Google AI Studio](https://aistudio.google.com/apikey)
- A reachable PostgreSQL instance (see the Docker command below)

## Run PostgreSQL locally with Docker

Start a throwaway Postgres container:

```bash
docker run --detach \
  --name genkit-postgres \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  --env POSTGRES_DB=genkit \
  --publish 5432:5432 \
  postgres:18

# wait until it's accepting connections
until docker exec genkit-postgres pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
echo "postgres ready"
```

The store creates its table (`genkit_sessions`) automatically on first use — no schema setup needed. Stop and remove the container later with:

```bash
docker rm -f genkit-postgres
```

## Configure

```bash
export POSTGRES_URL=jdbc:postgresql://localhost:5432/genkit
export POSTGRES_USER=postgres       # optional (default: postgres)
export POSTGRES_PASSWORD=postgres   # optional (default: postgres)
export GEMINI_API_KEY=<your-key>
```

### Inspecting the stored data

```bash
docker exec -it genkit-postgres psql -U postgres -d genkit \
  -c "SELECT id FROM genkit_sessions;"
```

Rows are keyed by `id`: `SNAP_<snapshotId>` (snapshot metadata), `SHARD_<checkpointId>_<index>` (the state JSON), and `PTR_<sessionId>` (the current-leaf pointer). Each row carries a JSONB `doc` payload and a `version` counter used for optimistic concurrency.

## Run

**Serve over HTTP (default):**

```bash
mvn -q exec:java
# assistant -> POST http://localhost:8083/assistant
```

**Genkit Dev UI:**

```bash
genkit start -- mvn -q exec:java
```

## Configuration

`PostgresSessionStore` uses a sharded checkpoint + diff + pointer layout in a single table. Tune it with `PostgresSessionStoreOptions` (table name, checkpoint interval, shard size — default 1 MiB — per-tenant prefix, `createTableIfNotExists`).
