---
title: Firebase Cloud Functions
description: Deploy Genkit flows as Firebase Cloud Functions with authentication, App Check, and streaming support.
---

Expose Genkit flows as Firebase Cloud Functions using `OnCallGenkit`. This lets you deploy AI-powered functions that scale automatically on Google Cloud.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-firebase</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

You also need the Cloud Functions Framework:

```xml
<dependency>
    <groupId>com.google.cloud.functions</groupId>
    <artifactId>functions-framework-api</artifactId>
    <version>1.1.0</version>
    <scope>provided</scope>
</dependency>
```

## Creating a Cloud Function

```java
import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import com.google.genkit.Genkit;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.plugins.firebase.FirebasePlugin;
import com.google.genkit.plugins.firebase.functions.OnCallGenkit;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;

public class GeneratePoemFunction implements HttpFunction {
    private final OnCallGenkit genkitFunction;

    public GeneratePoemFunction() {
        Genkit genkit = Genkit.builder()
            .plugin(GoogleGenAIPlugin.create(System.getenv("GEMINI_API_KEY")))
            .plugin(FirebasePlugin.builder().build())
            .build();

        genkit.defineFlow("generatePoem", String.class, String.class,
            (ctx, topic) -> genkit.generate(GenerateOptions.builder()
                .model("googleai/gemini-2.0-flash")
                .prompt("Write a poem about: " + topic)
                .build()).getText());

        this.genkitFunction = OnCallGenkit.fromFlow(genkit, "generatePoem");
    }

    @Override
    public void service(HttpRequest request, HttpResponse response)
            throws IOException {
        genkitFunction.service(request, response);
    }
}
```

## Authentication and App Check

`OnCallGenkit` supports Firebase Authentication and App Check for securing your functions:

```java
this.genkitFunction = OnCallGenkit.fromFlow(genkit, "generatePoem")
    .withAuthPolicy(authContext -> {
        if (authContext.getUid() == null) {
            throw new RuntimeException("Authentication required");
        }
    })
    .enforceAppCheck(true)
    .consumeAppCheckToken(true)
    .withCors("https://myapp.example.com");
```

### Auth policy options

| Method | Description |
|--------|-------------|
| `withAuthPolicy(policy)` | Validate the Firebase Auth context before running the flow |
| `enforceAppCheck(true)` | Reject requests without a valid App Check token |
| `consumeAppCheckToken(true)` | Consume the App Check token (replay protection) |
| `withCors(origin)` | Set allowed CORS origins |

## Streaming responses

`OnCallGenkit` automatically supports streaming via Server-Sent Events (SSE). Clients that send requests with `Accept: text/event-stream` receive chunked responses.

## Deploying

Deploy with the Google Cloud CLI:

```bash
gcloud functions deploy generatePoem \
  --runtime java21 \
  --trigger-http \
  --entry-point=com.example.GeneratePoemFunction \
  --set-env-vars GEMINI_API_KEY=your-key
```

Or deploy with the Firebase CLI:

```bash
firebase deploy --only functions
```

## Requirements

- Firebase project on the Blaze (pay-as-you-go) plan
- Application Default Credentials or a service account JSON
- `GCLOUD_PROJECT` or `GOOGLE_CLOUD_PROJECT` environment variable (auto-detected)
- Java 21 runtime

## Sample

See the [firebase sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/firebase) for a Cloud Functions deployment example.
