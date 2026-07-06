# Agents: MongoDB Session Sample

A server-managed assistant agent whose conversation state is persisted in **MongoDB** via `MongoSessionStore`, using a **Gemini** model for generation.

## Prerequisites

- Java 21+ and Maven 3.6+
- A `GEMINI_API_KEY` (for live model calls) — get one from [Google AI Studio](https://aistudio.google.com/apikey)
- A reachable MongoDB instance (see the Docker command below)

## Run MongoDB locally with Docker

Start a throwaway MongoDB container:

```bash
docker run --detach \
  --name genkit-mongo \
  --publish 27017:27017 \
  mongo:8

# wait until it's accepting connections
until docker exec genkit-mongo mongosh --quiet --eval 'db.runCommand({ ping: 1 })' >/dev/null 2>&1; do sleep 1; done
echo "mongo ready"
```

The store creates its database (`genkit`) and collection (`genkit_sessions`) automatically on first write — no setup needed. Stop and remove the container later with:

```bash
docker rm -f genkit-mongo
```

## Configure

```bash
export MONGO_URI=mongodb://localhost:27017
export GEMINI_API_KEY=<your-key>
```

### Inspecting the stored data

```bash
docker exec -it genkit-mongo mongosh genkit \
  --quiet --eval 'db.genkit_sessions.find({}, { _id: 1 }).toArray()'
```

Documents are keyed by `_id` = `<prefix>::<recordId>` (prefix defaults to `global`): `SNAP_<snapshotId>` (snapshot metadata), `SHARD_<checkpointId>_<index>` (the state JSON), and `PTR_<sessionId>` (the current-leaf pointer). Each document carries a `version` field used for optimistic concurrency.

## Run

**Serve over HTTP (default):**

```bash
mvn -q exec:java
# assistant -> POST http://localhost:8084/assistant
```

**Genkit Dev UI:**

```bash
genkit start -- mvn -q exec:java
```

## Configuration

`MongoSessionStore` uses a sharded checkpoint + diff + pointer layout in a single collection. Tune it with `MongoSessionStoreOptions` (database/collection names, checkpoint interval, shard size — default 1 MiB, under the 16 MB document cap — per-tenant prefix).
