---
title: Google GenAI (Gemini)
description: Use Google Gemini models, embeddings, Imagen image generation, TTS, and Veo video generation.
---

The Google GenAI plugin provides access to Google's Gemini models, text embeddings, Imagen image generation, text-to-speech, and Veo video generation.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-google-genai</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export GOOGLE_GENAI_API_KEY=your-api-key
```

Get an API key from [Google AI Studio](https://aistudio.google.com/apikey).

### Vertex AI

To use Vertex AI instead of the Google AI Developer API:

```bash
export GOOGLE_GENAI_USE_VERTEXAI=true
export GOOGLE_CLOUD_PROJECT=my-project
export GOOGLE_CLOUD_LOCATION=us-central1  # optional, defaults to us-central1
```

## Usage

```java
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;

Genkit genkit = Genkit.builder()
    .plugin(GoogleGenAIPlugin.create(System.getenv("GOOGLE_GENAI_API_KEY")))
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("googleai/gemini-2.5-flash")
        .prompt("Tell me about AI")
        .build());
```

## Embeddings

Generate text embeddings for RAG, semantic search, and similarity tasks:

```java
import com.google.genkit.ai.EmbedResponse;
import com.google.genkit.ai.Document;

List<Document> documents = List.of(
    Document.fromText("Genkit is a framework for building AI apps"),
    Document.fromText("Firebase provides cloud services")
);

EmbedResponse response = genkit.embed("googleai/gemini-embedding-001", documents);

// Access embedding vectors
float[] vector = response.getEmbeddings().get(0).getValues();
// vector.length == 768
```

Embeddings are used automatically by vector store plugins (Firebase, Pinecone, pgvector, etc.) when you configure an embedder name. You can also use them directly for custom similarity search.

### Embedding options

You can pass task-specific options to optimize embedding quality:

```java
Map<String, Object> embedOptions = Map.of(
    "taskType", "RETRIEVAL_DOCUMENT",  // or "RETRIEVAL_QUERY", "SEMANTIC_SIMILARITY"
    "title", "Document title",
    "outputDimensionality", 256  // reduce dimensions if needed
);
```

## Text-to-Speech (TTS)

Generate natural-sounding speech from text using Gemini TTS models (`gemini-3.1-flash-tts-preview`, `gemini-2.5-flash-preview-tts`, `gemini-2.5-pro-preview-tts`):

```java
Map<String, Object> ttsOptions = Map.of("voiceName", "Zephyr");
GenerationConfig config = GenerationConfig.builder()
    .custom(ttsOptions)
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("googleai/gemini-2.5-flash-preview-tts")
        .prompt("Hello! Welcome to Genkit Java.")
        .config(config)
        .build());

// The response contains audio as a media part (WAV format, base64-encoded)
String audioDataUrl = response.getMessage().getParts().get(0).getMedia().getUrl();
// "data:audio/wav;base64,..."
```

The plugin automatically frames your prompt with a synthesis preamble so the TTS model treats it as a transcript to voice. Without this, TTS models reject "vague" prompts with a `400` error (`Model tried to generate text, but it should only be used for TTS`). To supply your own framing (e.g. `"Say cheerfully: ..."`), set a `ttsInstruction` custom option — a preamble string, or an empty string to send the prompt verbatim:

```java
Map<String, Object> ttsOptions = Map.of("voiceName", "Zephyr", "ttsInstruction", "");
```

### Saving audio to a file

```java
String dataUrl = response.getMessage().getParts().get(0).getMedia().getUrl();
String base64Data = dataUrl.substring(dataUrl.indexOf(",") + 1);
byte[] audioBytes = Base64.getDecoder().decode(base64Data);
Files.write(Path.of("output.wav"), audioBytes);
```

## Video Generation (Veo)

Generate videos from text prompts or images using Google's Veo models:

```java
Map<String, Object> veoOptions = Map.of(
    "numberOfVideos", 1,
    "durationSeconds", 8,
    "aspectRatio", "16:9",
    "timeoutMs", 600000  // 10 min — video generation can take a while
);
GenerationConfig config = GenerationConfig.builder()
    .custom(veoOptions)
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("googleai/veo-3.1-generate-preview")
        .prompt("A serene Japanese garden with cherry blossoms falling")
        .config(config)
        .build());

// The response contains video as a media part (base64-encoded)
String videoDataUrl = response.getMessage().getParts().get(0).getMedia().getUrl();
```

:::note
Video generation is an asynchronous operation. The plugin automatically polls until the video is ready. This can take several minutes depending on the model and settings.
:::


## Image Generation (Imagen)

Generate images with Imagen:

```java
Map<String, Object> imagenOptions = Map.of(
    "numberOfImages", 1,
    "aspectRatio", "1:1"
);
GenerationConfig config = GenerationConfig.builder()
    .custom(imagenOptions)
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("googleai/imagen-4.0-fast-generate-001")
        .prompt("A cat wearing a space suit")
        .config(config)
        .build());
```


## Video generation and editing (Gemini Omni)

Gemini Omni (`googleai/gemini-omni-flash-preview`) generates and iteratively edits video through the Gemini Interactions API. It supports **conversational editing** — each turn can build on the previous result while preserving elements you did not mention.

```java
// First turn — generate a video
ModelResponse first = genkit.generate(
    GenerateOptions.builder()
        .model("googleai/gemini-omni-flash-preview")
        .prompt("A marble rolling down a chain-reaction track")
        .build());

// The returned interaction id lets you continue editing
String interactionId = (String) first.getCustom().get("interactionId");

// Follow-up turn — edit the previous result
ModelResponse edited = genkit.generate(
    GenerateOptions.builder()
        .model("googleai/gemini-omni-flash-preview")
        .prompt("Brighten the background and add a slow push-in on the logo")
        .config(GenerationConfig.builder()
            .custom(Map.of("previousInteractionId", interactionId))
            .build())
        .build());

// The generated video is returned as a media part (data URL by default)
String videoUrl = edited.getMessage().getParts().get(0).getMedia().getUrl();
```

Config options: `previousInteractionId` (continue/edit a prior interaction), `aspectRatio` (e.g. `"16:9"`), `duration` (e.g. `"10s"`), `delivery` (`"inline"` (default, base64) | `"uri"`), `task` (`"text_to_video"` | `"image_to_video"`), `thinkingLevel`, and `maxOutputTokens`. Only the Gemini Developer API (API key) is supported — not Vertex AI. The Interactions API is in preview.

## Sample

See the [google-genai sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/google-genai) for complete examples of text generation, tool calling, embeddings, image generation, TTS, and video generation.
