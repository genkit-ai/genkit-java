---
title: Firebase
description: Firebase integration with Firestore vector search, Cloud Functions deployment, and Google Cloud telemetry.
---

The Firebase plugin provides three key capabilities:

- **[Firestore Vector Store](/genkit-java/plugins/firebase-vector-store/)** — Native vector similarity search for RAG applications.
- **[Cloud Functions Deployment](/genkit-java/plugins/firebase-functions/)** — Deploy Genkit flows as scalable Cloud Functions with auth and streaming.
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

## Requirements

- Firebase project on the Blaze (pay-as-you-go) plan
- Application Default Credentials or a service account JSON
- `GCLOUD_PROJECT` or `GOOGLE_CLOUD_PROJECT` environment variable (auto-detected)

## Sample

See the [firebase sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/firebase) for a complete RAG pipeline with Firestore vector search and a Cloud Functions deployment example.
