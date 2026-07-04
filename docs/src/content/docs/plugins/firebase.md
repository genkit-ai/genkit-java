---
title: Firebase
description: Firebase integration with Firestore vector search, Cloud Functions deployment, and Google Cloud telemetry.
---

The Firebase plugin provides these key capabilities:

- **[Firestore Vector Store](/genkit-java/plugins/firebase-vector-store/)** — Native vector similarity search for RAG applications.
- **[Cloud Functions Deployment](/genkit-java/plugins/firebase-functions/)** — Deploy Genkit flows as scalable Cloud Functions with auth and streaming.
- **Firestore Session Store** — Persist server-managed agent sessions in Google Cloud Firestore.
- **Telemetry** — Export traces and metrics to Google Cloud observability.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-firebase</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Telemetry

Export traces and metrics to Google Cloud observability (Cloud Trace, Monitoring, Logging):

```java
FirebasePlugin.builder()
    .projectId("my-project")
    .enableTelemetry(true)
    .forceDevExport(true)  // Also export in dev mode
    .build()
```

### Required GCP APIs

Enable these APIs in your Google Cloud project:

- Cloud Trace API
- Cloud Monitoring API

## Firestore Session Store

`FirestoreSessionStore` persists server-managed agent sessions in Google Cloud Firestore, making it a good fit for production deployments on Cloud Run or Firebase Functions. Pass it to `.store(...)` when defining an agent:

```java
import com.google.genkit.plugins.firebase.session.FirestoreSessionStore;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;

Firestore db = FirestoreClient.getFirestore();

FirestoreSessionStore<Map<String, Object>> store = new FirestoreSessionStore<>(db);

Agent<Map<String, Object>> agent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("myAgent")
        .system("You are a helpful assistant.")
        .store(store)
        .build());
```

### Options

Use `FirestoreSessionStoreOptions` to customize the collection name and checkpointing behavior:

```java
import com.google.genkit.plugins.firebase.session.FirestoreSessionStoreOptions;

FirestoreSessionStore<Map<String, Object>> store = new FirestoreSessionStore<>(
    db,
    FirestoreSessionStoreOptions.builder()
        .collection("agent-sessions")   // top-level collection (default "genkit-sessions")
        .checkpointInterval(25)         // turns between full checkpoints (default 25)
        .shardSize(512 * 1024)          // checkpoint shard size in bytes (default 512 KiB)
        .build());
```

The store derives three collections from the configured name: `<collection>` for snapshot metadata, `<collection>-shards` for checkpoint state, and `<collection>-pointers` for the current leaf snapshot per session.

See [Session Stores](/genkit-java/agents/session-stores/) for the full comparison of available stores.

## Requirements

- Firebase project on the Blaze (pay-as-you-go) plan
- Application Default Credentials or a service account JSON
- `GCLOUD_PROJECT` or `GOOGLE_CLOUD_PROJECT` environment variable (auto-detected)

## Sample

See the [firebase sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/firebase) for a complete RAG pipeline with Firestore vector search and a Cloud Functions deployment example.
