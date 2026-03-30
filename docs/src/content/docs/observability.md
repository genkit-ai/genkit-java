---
title: Observability
description: Comprehensive observability with OpenTelemetry tracing and metrics.
---

Genkit Java provides comprehensive observability through OpenTelemetry integration. All actions (models, tools, flows) are automatically instrumented.

## Tracing

All actions are automatically traced with rich metadata:

| Attribute | Description |
|-----------|-------------|
| `genkit:name` | Action name (e.g., `openai/gpt-4o-mini`) |
| `genkit:type` | `action`, `flow`, `flowStep`, `util` |
| `genkit:metadata:subtype` | `model`, `tool`, `flow`, `embedder`, etc. |
| `genkit:path` | Full execution path |
| `genkit:input` | Full request data |
| `genkit:output` | Full response data |
| `genkit:sessionId` | Session identifier for multi-turn conversations |

Example trace span:

```
genkit:name = "openai/gpt-4o-mini"
genkit:type = "action"
genkit:metadata:subtype = "model"
genkit:path = "/{myFlow,t:flow}/{openai/gpt-4o-mini,t:action,s:model}"
genkit:sessionId = "user-123"
```

## Metrics

The SDK exposes OpenTelemetry metrics for monitoring:

### Model generation metrics

| Metric | Description |
|--------|-------------|
| `genkit/ai/generate/requests` | Request count |
| `genkit/ai/generate/latency` | Latency (ms) |
| `genkit/ai/generate/input/tokens` | Input token count |
| `genkit/ai/generate/output/tokens` | Output token count |
| `genkit/ai/generate/input/characters` | Input character count |
| `genkit/ai/generate/output/characters` | Output character count |
| `genkit/ai/generate/input/images` | Input image count |
| `genkit/ai/generate/output/images` | Output image count |
| `genkit/ai/generate/thinking/tokens` | Thinking/reasoning token count |

### Tool and flow metrics

| Metric | Description |
|--------|-------------|
| `genkit/tool/requests` | Tool execution request count |
| `genkit/tool/latency` | Tool execution latency (ms) |
| `genkit/feature/requests` | Flow request count |
| `genkit/feature/latency` | Flow latency (ms) |
| `genkit/action/requests` | General action request count |
| `genkit/action/latency` | General action latency (ms) |

All metrics include attributes: `modelName`, `featureName`, `path`, `status` (success/failure), and `source` (java).

## Usage tracking

Model responses include detailed usage statistics:

```java
ModelResponse response = genkit.generate(options);
Usage usage = response.getUsage();

System.out.println("Input tokens: " + usage.getInputTokens());
System.out.println("Output tokens: " + usage.getOutputTokens());
System.out.println("Latency: " + response.getLatencyMs() + "ms");
```

## Firebase Telemetry

For production deployments, the Firebase plugin can export telemetry to Google Cloud:

```java
FirebasePlugin.builder()
    .projectId("my-project")
    .enableTelemetry(true)      // Cloud Trace, Monitoring, Logging
    .forceDevExport(true)       // Also export in dev mode
    .build()
```
