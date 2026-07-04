# Agents: DynamoDB Session Sample

A server-managed assistant agent whose conversation state is persisted in **Amazon DynamoDB** via `DynamoDbSessionStore`, using an **AWS Bedrock** model for generation — a self-contained, single-cloud sample.

## Prerequisites

- Java 21+ and Maven 3.6+
- AWS credentials (for the Bedrock model, and for DynamoDB unless using DynamoDB Local)
- A DynamoDB backend — DynamoDB Local for dev, or real AWS

### Point at DynamoDB Local (dev)

```bash
docker run -p 8000:8000 amazon/dynamodb-local
export DYNAMODB_LOCAL_ENDPOINT=http://localhost:8000
export AWS_REGION=us-east-1
```

With `DYNAMODB_LOCAL_ENDPOINT` set, the table (`genkit-sessions`) is created automatically. Against real AWS, create the table beforehand (partition key `pk` string, sort key `sk` string) or run once with credentials that allow `CreateTable`.

## Run

**Serve over HTTP (default)** — starts without touching AWS:

```bash
mvn -q exec:java
# assistant -> POST http://localhost:8080/assistant
```

**Genkit Dev UI:**

```bash
genkit start -- mvn -q exec:java
```

**Persistence demo** (requires AWS credentials for Bedrock + a reachable DynamoDB):

```bash
export DYNAMODB_LOCAL_ENDPOINT=http://localhost:8000   # or use real AWS
mvn -q exec:java -Dexec.args=demo
```

The demo runs a two-turn conversation, then reads the latest snapshot **back from DynamoDB** by session id and prints how many messages were persisted.

## Configuration

`DynamoDbSessionStore` uses a sharded checkpoint + diff + pointer layout in a single table. Tune it with `DynamoDbSessionStoreOptions` (table name, checkpoint interval, shard size — default 350 KiB, under the 400 KB item cap — per-tenant prefix, `createTableIfNotExists`).
