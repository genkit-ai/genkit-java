# Genkit Firebase Plugin

Firebase integration for Genkit Java, providing:

- **Firestore Vector Search** - RAG (Retrieval Augmented Generation) with Cloud Firestore's native vector search
- **Firebase Cloud Functions** - Deploy Genkit flows as Firebase Cloud Functions
- **Firebase Telemetry** - Google Cloud observability integration (traces and metrics)

## Installation

Add the Firebase plugin to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-firebase</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

### Minimal Setup (Cloud Functions only)

For simple Cloud Functions that don't need Firestore, you can use the plugin with minimal configuration:

```java
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(FirebasePlugin.builder().build())  // No project ID required
    .build();
```

### Full Setup (with Firestore)

For RAG workflows with Firestore vector search:

```java
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(FirebasePlugin.builder()
        .projectId("my-project")
        .enableTelemetry(true)
        .addRetriever(FirestoreRetrieverConfig.builder()
            .name("myDocs")
            .collection("documents")
            .embedderName("googleai/text-embedding-004")
            .vectorField("embedding")
            .contentField("content")
            .build())
        .build())
    .build();
```

## Prerequisites

### Firebase Project Setup (for Firestore features)

1. Create a new Firebase project using the [Firebase console](https://console.firebase.google.com/) or choose an existing one.
2. Upgrade to the Blaze plan (required for Cloud Functions and Firestore vector search).
3. Enable Cloud Firestore in your project.

### Credentials

The plugin uses Application Default Credentials. Set up credentials by:

1. Running in a Google Cloud environment (Cloud Functions, Cloud Run, etc.) - credentials are automatic
2. Using a service account key file:
   ```bash
   export GOOGLE_APPLICATION_CREDENTIALS="/path/to/service-account-key.json"
   ```
3. Using `gcloud` CLI authentication:
   ```bash
   gcloud auth application-default login
   ```

## Firestore Vector Search

### Define a Firestore Retriever

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.firebase.FirebasePlugin;
import com.google.genkit.plugins.firebase.retriever.FirestoreRetrieverConfig;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(FirebasePlugin.builder()
        .projectId("my-project")
        .addRetriever(FirestoreRetrieverConfig.builder()
            .name("myDocs")
            .collection("documents")
            .embedderName("googleai/text-embedding-004")
            .vectorField("embedding")
            .contentField("content")
            .distanceMeasure(FirestoreRetrieverConfig.DistanceMeasure.COSINE)
            .build())
        .build())
    .build();
```

### Retrieve Documents

```java
// Retrieve relevant documents
RetrieverResponse response = genkit.retrieve(
    "firebase/myDocs",
    Document.fromText("What is the meaning of life?"),
    Map.of("limit", 5)
);

// Use in RAG workflow
List<Document> docs = response.getDocuments();
String context = docs.stream()
    .map(Document::text)
    .collect(Collectors.joining("\n"));

GenerateResponse answer = genkit.generate(
    "Based on this context: " + context + "\n\nAnswer: What is the meaning of life?"
);
```

### Index Documents

```java
// Index documents with embeddings
genkit.index("firebase/myDocs", List.of(
    Document.fromText("The meaning of life is 42"),
    Document.fromText("Life is what you make of it")
));
```

### Available Retrieval Options

| Option | Type | Description |
|--------|------|-------------|
| `limit` | int | Maximum number of documents to retrieve (default: 10) |
| `k` | int | Alias for `limit` |
| `where` | Map | Field-value pairs for filtering |
| `collection` | String | Override the default collection |
| `distanceMeasure` | String | COSINE, EUCLIDEAN, or DOT_PRODUCT |
| `distanceThreshold` | double | Filter results by distance |

### Create Vector Index

Before performing vector search, create an index on your Firestore collection:

```bash
gcloud alpha firestore indexes composite create --project=your-project-id \
  --collection-group=documents --query-scope=COLLECTION \
  --field-config=vector-config='{"dimension":"768","flat": "{}"}',field-path=embedding
```

The dimension should match your embedder's output dimension.

## Firebase Cloud Functions

### Simple Cloud Function (No Firestore)

For Cloud Functions that only need AI generation without Firestore:

```java
import com.google.genkit.plugins.firebase.functions.OnCallGenkit;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;

public class GeneratePoemFunction implements HttpFunction {
    private final OnCallGenkit genkitFunction;

    public GeneratePoemFunction() {
        // No project ID or Firestore needed
        Genkit genkit = Genkit.builder()
            .plugin(GoogleGenAIPlugin.create(System.getenv("GEMINI_API_KEY")))
            .plugin(FirebasePlugin.builder().build())
            .build();

        genkit.defineFlow("generatePoem", String.class, String.class,
            (ctx, topic) -> genkit.generate("Write a poem about: " + topic).text());

        this.genkitFunction = OnCallGenkit.fromFlow(genkit, "generatePoem");
    }

    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {
        genkitFunction.service(request, response);
    }
}
```

### Cloud Function with Firestore RAG

```java
import com.google.genkit.plugins.firebase.functions.OnCallGenkit;
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;

public class RAGFunction implements HttpFunction {
    
    private final Genkit genkit = Genkit.builder()
        .plugin(GoogleGenAIPlugin.create(System.getenv("GEMINI_API_KEY")))
        .plugin(FirebasePlugin.builder()
            .projectId(System.getenv("GCLOUD_PROJECT"))
            .addRetriever(FirestoreRetrieverConfig.builder()
                .name("docs")
                .collection("documents")
                .embedderName("googleai/text-embedding-004")
                .vectorField("embedding")
                .contentField("content")
                .build())
            .build())
        .build();
    
    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {
        OnCallGenkit.fromFlow(genkit, "ragQuery")
            .withAuthPolicy(OnCallGenkit.signedIn())
            .service(request, response);
    }
}
```

### Define the Flow

```java
Flow<PoemRequest, PoemResponse> generatePoem = genkit.defineFlow(
    "generatePoem",
    PoemRequest.class,
    PoemResponse.class,
    (ctx, request) -> {
        GenerateResponse response = genkit.generate(
            "Compose a poem about " + request.getSubject()
        );
        return new PoemResponse(response.text());
    }
);
```

### Authorization Policies

```java
// Allow unauthenticated access
OnCallGenkit.fromFlow(genkit, "myFlow")

// Require signed-in user
.withAuthPolicy(OnCallGenkit.signedIn())

// Require specific claim
.withAuthPolicy(OnCallGenkit.hasClaim("email_verified"))

// Custom policy
.withAuthPolicy(auth -> auth != null && auth.getEmail().endsWith("@company.com"))
```

### Deploy

```bash
gcloud functions deploy generatePoem \
  --gen2 \
  --runtime java21 \
  --trigger-http \
  --allow-unauthenticated \
  --entry-point com.example.GeneratePoemFunction \
  --set-env-vars GEMINI_API_KEY=$GEMINI_API_KEY \
  --region us-central1 \
  --memory 1024MB \
  --timeout 300s
```

## Firebase Telemetry

### Enable Telemetry

Via environment variable:
```bash
export ENABLE_FIREBASE_MONITORING=true
```

Or programmatically:
```java
FirebasePlugin.builder()
    .projectId("my-project")
    .enableTelemetry(true)
    .forceDevExport(true)  // Enable in dev mode
    .metricExportIntervalMillis(60000)
    .build()
```

### Required APIs

Enable these APIs in your Google Cloud project:

- [Cloud Logging API](https://console.cloud.google.com/apis/library/logging.googleapis.com)
- [Cloud Trace API](https://console.cloud.google.com/apis/library/cloudtrace.googleapis.com)
- [Cloud Monitoring API](https://console.cloud.google.com/apis/library/monitoring.googleapis.com)

### Required IAM Roles

Grant these roles to your service account:

- `roles/monitoring.metricWriter` - Monitoring Metric Writer
- `roles/cloudtrace.agent` - Cloud Trace Agent
- `roles/logging.logWriter` - Logs Writer

### View Metrics

After deployment, view metrics in the [Firebase Genkit Monitoring dashboard](https://console.firebase.google.com/project/_/genai_monitoring).

Metrics include:
- Feature request counts and latency
- Action execution metrics
- Model generation stats (latency, token usage)

## Complete Example

```java
import com.google.genkit.Genkit;
import com.google.genkit.ai.*;
import com.google.genkit.plugins.firebase.FirebasePlugin;
import com.google.genkit.plugins.firebase.retriever.FirestoreRetrieverConfig;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;

public class FirebaseRAGExample {
    
    public static void main(String[] args) {
        // Initialize Genkit with Firebase plugin
        Genkit genkit = Genkit.builder()
            .plugin(GoogleGenAIPlugin.create(System.getenv("GEMINI_API_KEY")))
            .plugin(FirebasePlugin.builder()
                .projectId("my-project")
                .enableTelemetry(true)
                .addRetriever(FirestoreRetrieverConfig.builder()
                    .name("knowledge-base")
                    .collection("documents")
                    .embedderName("googleai/text-embedding-004")
                    .vectorField("embedding")
                    .contentField("content")
                    .build())
                .build())
            .build();
        
        // Define RAG flow
        genkit.defineFlow("ragQuery", String.class, String.class, (ctx, query) -> {
            // Retrieve relevant documents
            RetrieverResponse retrieval = genkit.retrieve(
                "firebase/knowledge-base",
                Document.fromText(query),
                Map.of("limit", 5)
            );
            
            // Build context
            String context = retrieval.getDocuments().stream()
                .map(Document::text)
                .collect(Collectors.joining("\n\n"));
            
            // Generate answer
            GenerateResponse response = genkit.generate(
                "Based on the following context:\n\n" + context + 
                "\n\nAnswer this question: " + query
            );
            
            return response.text();
        });
        
        // Start server
        genkit.startServer();
    }
}
```

## Learn More

- [Firestore Vector Search Documentation](https://firebase.google.com/docs/firestore/vector-search)
- [Cloud Functions for Firebase](https://firebase.google.com/docs/functions)
- [Genkit Monitoring](https://genkit.dev/docs/observability/getting-started/)
- [RAG with Genkit](https://genkit.dev/docs/rag/)

## License

Apache 2.0 - See [LICENSE](../../LICENSE) for details.
