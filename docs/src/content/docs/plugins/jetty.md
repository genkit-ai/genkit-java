---
title: Jetty Server
description: Expose Genkit flows as HTTP endpoints using Jetty 12.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-jetty</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

```java
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder()
    .port(8080)
    .build());

Genkit genkit = Genkit.builder()
    .plugin(jetty)
    .build();

// Define your flows here...

// Start the server (blocks until stopped)
jetty.start();
```

:::caution
You must call `jetty.start()` after building the `Genkit` instance and defining your flows. Without it, the HTTP server will not start and your flows won't be accessible.
:::

All defined flows are automatically exposed as HTTP endpoints.

## Calling flows via HTTP

```bash
curl -X POST http://localhost:8080/api/flows/tellJoke \
  -H "Content-Type: application/json" \
  -d '{"data": "pirates"}'
```

## Serving agents

Any agent defined with `genkit.beta().defineAgent(...)` / `defineCustomAgent(...)` is mounted automatically — just define it before calling `jetty.start()`. Agents are served at the **root path** (`/<agentName>`), separate from flows' `/api/flows/...` base:

```bash
curl -X POST http://localhost:8080/echoAgent \
  -H "Content-Type: application/json" \
  -d '{"data":{"message":{"role":"user","content":[{"text":"Hello, agent!"}]}}}'
```

For server-managed agents (those configured with a `SessionStore`), the companion endpoints `POST /<agentName>/getSnapshot` and `POST /<agentName>/abort` are mounted too. Streaming turns are available via `Accept: text/event-stream` or `?stream=true`.

The same Java client (`RemoteAgent` / `AgentChat`) works against a Jetty or Spring server unchanged. See [Serve over HTTP](../../agents/serve-over-http) for the full request/response contract, calling a served agent from Java, and how request headers reach your agent — and [Background Execution](../../agents/background-execution) for detached (background) turns.
