# Agents: Firestore Session Sample

A server-managed assistant agent whose conversation state is persisted in **Firestore** via `FirestoreSessionStore`. Because the state lives in Firestore rather than in the process, the conversation survives restarts and can be resumed from any instance.

## Prerequisites

- Java 21+ and Maven 3.6+
- `GEMINI_API_KEY` (for live model calls in demo mode)
- A Firestore backend — the emulator for local dev, or a real project

### Point at the Firestore emulator (local dev)

```bash
gcloud emulators firestore start --host-port=localhost:8080
export FIRESTORE_EMULATOR_HOST=localhost:8080
export GCLOUD_PROJECT=demo-genkit
```

Or use a real project by setting `GCLOUD_PROJECT` (with application default credentials configured).

## Run

**Serve over HTTP (default):**

```bash
mvn -q exec:java
# assistant -> POST http://localhost:8080/assistant
```

**Genkit Dev UI:**

```bash
genkit start -- mvn -q exec:java
```

**Persistence demo** (requires `GEMINI_API_KEY` + a configured Firestore):

```bash
export GEMINI_API_KEY=your-key
mvn -q exec:java -Dexec.args=demo
```

The demo runs a two-turn conversation, then reads the latest snapshot **back from Firestore** by session id and prints how many messages were persisted — proving the state is stored server-side.

## Configuration

`FirestoreSessionStore` uses a sharded checkpoint + diff + pointer layout. Tune it with `FirestoreSessionStoreOptions` (collection name, checkpoint interval, shard size, per-tenant prefix).
