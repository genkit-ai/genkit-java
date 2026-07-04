---
title: Session Stores
description: Persist agent session state with in-memory, file-based, Firestore, DynamoDB, or Cosmos DB session stores.
---

A `SessionStore<S>` is where a server-managed agent keeps its sessions. Pass one to `.store(...)` when you define an agent to persist snapshots; omit it to run the agent client-managed (stateless). Several implementations ship out of the box, and you can write your own.

| Store | Persistence | Good for |
|-------|-------------|----------|
| `InMemorySessionStore` | In-process; lost on restart | Tests and short-lived processes |
| `FileSessionStore` | JSON files on local disk | Local development and single-process deployments |
| `FirestoreSessionStore` | Google Cloud Firestore | Production on Google Cloud (Cloud Run, Firebase Functions) |
| `DynamoDbSessionStore` | Amazon DynamoDB | Production on AWS |
| `CosmosSessionStore` | Azure Cosmos DB | Production on Azure |

If you plan to use `chat.abort()` or the `/abort` HTTP endpoint, pick a store that supports change notifications: `FileSessionStore`, `FirestoreSessionStore`, `DynamoDbSessionStore`, and `CosmosSessionStore` do; `InMemorySessionStore` does not, so `abort()` is a no-op there. See [Sessions](../sessions#aborting-a-turn).

## InMemorySessionStore

Holds snapshots in memory. State is not shared across processes and is discarded on exit.

```java
import com.google.genkit.ai.agent.InMemorySessionStore;

Agent<Map<String, Object>> agent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("myAgent")
        .system("You are a helpful assistant.")
        .store(new InMemorySessionStore<>())
        .build());
```

## FileSessionStore

Persists each snapshot as a JSON file under a directory you choose.

```java
import com.google.genkit.ai.agent.FileSessionStore;

Agent<Map<String, Object>> agent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("myAgent")
        .system("You are a helpful assistant.")
        .store(new FileSessionStore<>("./.snapshots"))
        .build());
```

Writes are atomic — a snapshot is written to a temporary file and then renamed — so a process interrupted mid-write never leaves a partial snapshot behind.

### Options

Use the builder for finer control:

```java
FileSessionStore<Map<String, Object>> store =
    FileSessionStore.<Map<String, Object>>builder("./.snapshots")
        .prefix("prod")                   // subdirectory to group sessions (default "global")
        .maxPersistedChainLength(10)      // prune older snapshots in a chain; 0 = keep all
        .rejectBranchingSessions(true)    // fail if a session ends up with more than one branch
        .snapshotWatchPollIntervalMs(500) // how often change subscribers are polled (default 2000)
        .build();
```

Snapshots for a session are stored under the prefix subdirectory, with a small pointer file tracking the latest snapshot per session.

### Watching for changes

`FileSessionStore` can notify you when a snapshot's status changes — useful for tracking a background turn:

```java
try (AutoCloseable sub = store.onSnapshotStateChange(
        snapshotId,
        snap -> System.out.println("Status changed: " + snap.getStatus()),
        null)) {
    // ... long-running turn ...
}
```

## FirestoreSessionStore

Stores snapshots in Google Cloud Firestore. It lives in the Firebase plugin, so add the dependency:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-firebase</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
import com.google.genkit.plugins.firebase.session.FirestoreSessionStore;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;

Firestore db = FirestoreClient.getFirestore();
FirestoreSessionStore<Map<String, Object>> store =
    new FirestoreSessionStore<>(db); // uses the default "genkit-sessions" collection

Agent<Map<String, Object>> agent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("myAgent")
        .system("You are a helpful assistant.")
        .store(store)
        .build());
```

To customize the collection name or checkpointing behavior, pass `FirestoreSessionStoreOptions` as the second constructor argument. See the [Firebase plugin](../../plugins/firebase#firestore-session-store) for details.

## DynamoDbSessionStore

Stores snapshots in Amazon DynamoDB. It lives in the AWS Bedrock plugin, so add the dependency:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-aws-bedrock</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
import com.google.genkit.plugins.awsbedrock.session.DynamoDbSessionStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

DynamoDbClient db = DynamoDbClient.create(); // uses the default AWS credential chain and region
DynamoDbSessionStore<Map<String, Object>> store =
    new DynamoDbSessionStore<>(db); // uses the default "genkit-sessions" table

Agent<Map<String, Object>> agent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("myAgent")
        .system("You are a helpful assistant.")
        .store(store)
        .build());
```

All records live in a single table with a partition key `pk` and sort key `sk` (both strings). Create the table ahead of time, or let the store create it on first use:

```java
DynamoDbSessionStore<Map<String, Object>> store =
    new DynamoDbSessionStore<>(
        db,
        DynamoDbSessionStoreOptions.builder()
            .tableName("genkit-sessions")     // table name (default "genkit-sessions")
            .createTableIfNotExists(true)     // auto-create the table on first use (default false)
            .pollIntervalMs(500)              // how often change subscribers are polled (default 2000)
            .build());
```

The default shard size is 350 KiB, kept under DynamoDB's 400 KB item-size limit. `DynamoDbSessionStore` supports `onSnapshotStateChange` (via polling), so `chat.abort()` works.

## CosmosSessionStore

Stores snapshots in Azure Cosmos DB. It lives in the Azure AI Foundry plugin, so add the dependency:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-azure-foundry</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

```java
import com.google.genkit.plugins.azurefoundry.session.CosmosSessionStore;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;

CosmosClient client = new CosmosClientBuilder()
    .endpoint(System.getenv("COSMOS_ENDPOINT"))
    .key(System.getenv("COSMOS_KEY"))
    .buildClient();

CosmosSessionStore<Map<String, Object>> store =
    new CosmosSessionStore<>(client); // uses database "genkit", container "genkit-sessions"

Agent<Map<String, Object>> agent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("myAgent")
        .system("You are a helpful assistant.")
        .store(store)
        .build());
```

All records live in a single container partitioned by `/pk`. Create the database and container ahead of time, or let the store create them on first use:

```java
CosmosSessionStore<Map<String, Object>> store =
    new CosmosSessionStore<>(
        client,
        CosmosSessionStoreOptions.builder()
            .databaseName("genkit")           // database name (default "genkit")
            .containerName("genkit-sessions") // container name (default "genkit-sessions")
            .createIfNotExists(true)          // auto-create database + container (default false)
            .pollIntervalMs(500)              // how often change subscribers are polled (default 2000)
            .build());
```

The default shard size is 1 MiB, kept under Cosmos DB's 2 MB document-size limit. `CosmosSessionStore` supports `onSnapshotStateChange` (via polling), so `chat.abort()` works.

## Writing your own store

Implement `SessionStore<S>` to use any backend — a database, a cache, a cloud object store:

```java
import com.google.genkit.ai.agent.SessionStore;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.GetSnapshotOptions;
import com.google.genkit.ai.agent.SessionStoreOptions;
import com.google.genkit.ai.agent.SnapshotMutator;

public class RedisSessionStore<S> implements SessionStore<S> {

    @Override
    public SessionSnapshot<S> getSnapshot(GetSnapshotOptions opts) {
        // Read by snapshotId or sessionId.
        ...
    }

    @Override
    public String saveSnapshot(String snapshotId, SnapshotMutator<S> mutator,
                               SessionStoreOptions options) {
        // Apply the mutator to the existing snapshot, persist the result,
        // and return the final snapshotId.
        ...
    }
}
```

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Sessions](../sessions) — Session lifecycle, snapshots, and resuming
- [Serve over HTTP](../serve-over-http) — Exposing a store-backed agent through Jetty or Spring
- [Background Execution](../background-execution) — Detached turns and the store's role in polling
