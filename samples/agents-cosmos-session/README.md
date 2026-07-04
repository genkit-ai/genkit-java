# Agents: Cosmos DB Session Sample

A server-managed assistant agent whose conversation state is persisted in **Azure Cosmos DB** via `CosmosSessionStore`, using an **Azure AI Foundry** model for generation — a self-contained, single-cloud sample.

## Prerequisites

- Java 21+ and Maven 3.6+
- A Cosmos DB backend — the emulator for dev, or an Azure account
- Azure AI Foundry endpoint + credentials (for live model calls)

### Run the Cosmos DB emulator (dev)

Use the **next-gen Linux emulator** (`vnext-latest`) — it's ARM-native and runs on Apple Silicon. (The older `azure-cosmos-emulator:latest` image is x86-only with no `arm64` build, so it fails to pull on M-series Macs.)

**1. Start the emulator with HTTPS enabled.** It defaults to HTTP, but the Java Cosmos SDK requires HTTPS. We keep the Data Explorer on HTTP so it opens in the browser without a cert warning:

```bash
docker run --detach \
  --publish 8081:8081 --publish 8080:8080 --publish 1234:1234 \
  --name cosmos-emulator \
  mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:vnext-latest \
  --protocol https --explorer-protocol http

# wait until ready (8080 is the health probe)
until curl -sf http://localhost:8080/ready >/dev/null; do sleep 1; done; echo "ready"
```

Ports: `8081` = Cosmos endpoint (HTTPS), `1234` = Data Explorer, `8080` = health probe. The emulator supports the NoSQL API in **gateway mode**, which is exactly what `CosmosSessionStore` uses.

**2. Trust the emulator's TLS certificate in your JVM** (required — the Java SDK rejects the self-signed cert otherwise):

```bash
openssl s_client -connect localhost:8081 </dev/null \
  | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > /tmp/cosmos_emulator.cert
keytool -cacerts -importcert -alias cosmos_emulator -file /tmp/cosmos_emulator.cert \
  -storepass changeit -noprompt
# remove later with: keytool -cacerts -delete -alias cosmos_emulator -storepass changeit
```

`keytool -cacerts` targets the truststore of the `keytool` on your `PATH` — make sure it's the same JDK you run the sample with (`which java`).

**3. Point the sample at it** (the key below is the emulator's fixed well-known key — not a secret):

```bash
export COSMOS_ENDPOINT=https://localhost:8081
export COSMOS_KEY='<your-key>'

# For live model calls (Azure OpenAI). Use the *.openai.azure.com endpoint (NOT a
# services.ai.azure.com /openai/v1/... URL — the plugin speaks legacy chat/completions):
export AZURE_AI_FOUNDRY_ENDPOINT=https://<your-resource>.openai.azure.com
export AZURE_API_KEY=<your-key>
# Your Azure OpenAI *deployment* name (Azure routes by deployment, not model name):
export AZURE_MODEL=<your-deployment>
# Optional: override the api-version if your resource rejects the plugin default
# (2024-10-01-preview) with 400 "API version not supported" — e.g. a GA resource:
export AZURE_API_VERSION=2024-10-21
```

The agent's model is `azure-foundry/${AZURE_MODEL}` (default `gpt-4o-mini`). `AZURE_MODEL` must be an existing **deployment** name in your Azure OpenAI resource — the sample registers it automatically.

With `COSMOS_ENDPOINT`/`COSMOS_KEY` set, the database (`genkit`) and container (`genkit-sessions`, partition key `/pk`) are created automatically.

### Inspecting the stored data

Open the **Data Explorer** at `http://localhost:1234` → database `genkit` → container `genkit-sessions`. Documents are keyed by `id`: `SNAP_<snapshotId>` (snapshot metadata), `SHARD_<checkpointId>_<index>` (the state JSON), and `PTR_<sessionId>` (the current-leaf pointer). Real Azure accounts show the same under the portal's Data Explorer.

## Run

**Serve over HTTP (default):**

```bash
mvn -q exec:java
# assistant -> POST http://localhost:8082/assistant
```

**Genkit Dev UI:**

```bash
genkit start -- mvn -q exec:java
```

**Persistence demo** (requires Cosmos DB + Azure AI Foundry credentials):

```bash
mvn -q exec:java -Dexec.args=demo
```

The demo runs a two-turn conversation, then reads the latest snapshot **back from Cosmos DB** by session id and prints how many messages were persisted.

## Configuration

`CosmosSessionStore` uses a sharded checkpoint + diff + pointer layout in a single container. Tune it with `CosmosSessionStoreOptions` (database/container names, checkpoint interval, shard size — default 1 MiB, under the 2 MB document cap — per-tenant prefix, `createIfNotExists`).
