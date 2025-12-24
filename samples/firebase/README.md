# Firebase Plugin Sample

This sample demonstrates the Firebase plugin for Genkit Java, showcasing:

- **Firestore Vector Search**: Index and retrieve documents using Firestore's native vector similarity search
- **RAG (Retrieval Augmented Generation)**: Build AI-powered Q&A with context from your documents
- **Firebase Cloud Functions**: Deploy Genkit flows as callable Firebase functions

## Prerequisites

1. **Google Cloud Project** with Firestore enabled
2. **Gemini API Key** from [Google AI Studio](https://aistudio.google.com/)
3. **Google Cloud credentials** configured:
   ```bash
   gcloud auth application-default login
   ```

## Environment Setup

```bash
export GEMINI_API_KEY=your-gemini-api-key
export GCLOUD_PROJECT=your-gcp-project-id
```

## Firestore Setup

Before running the sample, create a composite index for vector search in Firestore:

1. Go to the [Firestore Console](https://console.firebase.google.com/)
2. Navigate to **Firestore Database** > **Indexes**
3. Create a new composite index:
   - Collection: `films` (or your collection name)
   - Field: `embedding` with **Vector** configuration
   - Vector config: 768 dimensions, COSINE distance

Alternatively, the index will be created automatically when you first run a vector query (may take a few minutes).

## Running the Sample

### Local Development

```bash
# Run the Firestore RAG sample
./run.sh
```

This starts a local Genkit server with three flows:

1. **indexFilms**: Populates Firestore with sample film documents
2. **retrieveFilms**: Retrieves films matching a search query
3. **ragQuery**: Answers questions about films using RAG

### Testing the Flows

```bash
# Index sample documents
curl -X POST http://localhost:4000/api/flows/indexFilms \
  -H 'Content-Type: application/json' \
  -d '{}'

# Search for sci-fi movies
curl -X POST http://localhost:4000/api/flows/retrieveFilms \
  -H 'Content-Type: application/json' \
  -d '{"data": "sci-fi movie"}'

# Ask a question with RAG
curl -X POST http://localhost:4000/api/flows/ragQuery \
  -H 'Content-Type: application/json' \
  -d '{"data": "What Christopher Nolan films are mentioned?"}'
```

## Deploying as Firebase Cloud Functions

### Project Structure

```
samples/firebase/
├── src/main/java/com/google/genkit/samples/firebase/
│   ├── FirestoreRAGSample.java       # Local development sample
│   └── functions/
│       ├── GeneratePoemFunction.java  # Simple generation function
│       └── RAGFunction.java           # RAG function with auth
```

### Deploy to Firebase

1. Initialize Firebase Functions (if not already done):
   ```bash
   firebase init functions
   ```

2. Configure your `firebase.json`:
   ```json
   {
     "functions": {
       "runtime": "java21",
       "source": "."
     }
   }
   ```

3. Set environment variables:
   ```bash
   firebase functions:secrets:set GEMINI_API_KEY
   ```

4. Deploy:
   ```bash
   firebase deploy --only functions
   ```

### Deploy to Google Cloud Functions

```bash
# Deploy the poem generator
gcloud functions deploy generatePoem \
  --runtime java21 \
  --trigger-http \
  --allow-unauthenticated \
  --entry-point com.google.genkit.samples.firebase.functions.GeneratePoemFunction \
  --set-env-vars GEMINI_API_KEY=$GEMINI_API_KEY

# Deploy the RAG function (with authentication)
gcloud functions deploy ragAnswer \
  --gen2 \
  --runtime java21 \
  --trigger-http \
  --entry-point com.google.genkit.samples.firebase.functions.RAGFunction \
  --set-env-vars GEMINI_API_KEY=$GEMINI_API_KEY,GCLOUD_PROJECT=$GCLOUD_PROJECT \
  --region us-central1 \
  --memory 1024MB \
  --timeout 300s
```

## Code Examples

### Basic Retriever Setup

```java
Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(apiKey))
    .plugin(FirebasePlugin.builder()
        .projectId(projectId)
        .addRetriever(FirestoreRetrieverConfig.builder()
            .name("my-docs")
            .collection("documents")
            .embedderName("googleai/text-embedding-004")
            .vectorField("embedding")
            .contentField("content")
            .distanceMeasure(FirestoreRetrieverConfig.DistanceMeasure.COSINE)
            .defaultLimit(10)
            .build())
        .build())
    .build();
```

### Indexing Documents

```java
List<Document> docs = List.of(
    Document.fromText("First document content"),
    Document.fromText("Second document content")
);

genkit.index("firebase/my-docs", docs);
```

### Retrieving with Vector Search

```java
RetrieverResponse response = genkit.retrieve(
    "firebase/my-docs",
    Document.fromText("search query"),
    Map.of("limit", 5)
);

for (Document doc : response.getDocuments()) {
    System.out.println(doc.text());
}
```

### Creating a Cloud Function

The `FirebasePlugin` is optional for simple Cloud Functions that don't need Firestore. You can use it just for generation:

```java
public class MyFunction implements HttpFunction {
    private final OnCallGenkit genkitFunction;

    public MyFunction() {
        // Simple setup - no Firestore required
        Genkit genkit = Genkit.builder()
            .plugin(GoogleGenAIPlugin.create(System.getenv("GEMINI_API_KEY")))
            .plugin(FirebasePlugin.builder().build())  // Optional: enables telemetry
            .build();

        genkit.defineFlow("myFlow", String.class, String.class, 
            (ctx, input) -> genkit.generate("Process: " + input).text());

        this.genkitFunction = OnCallGenkit.fromFlow(genkit, "myFlow");
    }

    @Override
    public void service(HttpRequest request, HttpResponse response) throws IOException {
        genkitFunction.service(request, response);
    }
}
```

## Telemetry

The sample enables Firebase telemetry, which exports:
- **Traces** to Google Cloud Trace
- **Metrics** to Google Cloud Monitoring
- **Logs** to Google Cloud Logging

View telemetry in the [Google Cloud Console](https://console.cloud.google.com/):
- **Trace**: Operations > Trace
- **Monitoring**: Operations > Monitoring > Metrics Explorer
- **Logging**: Operations > Logging > Logs Explorer

## Troubleshooting

### "Vector index not found"

Create the composite index in Firestore Console or wait for auto-creation.

### "Permission denied"

Ensure your Google Cloud credentials have Firestore read/write access:
```bash
gcloud auth application-default login
```

### "GEMINI_API_KEY not set"

Export the environment variable:
```bash
export GEMINI_API_KEY=your-api-key
```
