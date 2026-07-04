---
title: Spring Boot
description: Expose Genkit flows as REST endpoints with Spring Boot.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-spring</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

```java
import com.google.genkit.plugins.spring.SpringPlugin;

SpringPlugin spring = SpringPlugin.create();

Genkit genkit = Genkit.builder()
    .plugin(spring)
    .build();

// Define your flows here...

// Start the server (blocks until stopped)
spring.start();
```

:::caution
You must call `spring.start()` after building the `Genkit` instance and defining your flows. Without it, the Spring Boot server will not start and your endpoints won't be accessible.
:::

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Health check |
| `GET /api/flows` | List all registered flows |
| `POST /api/flows/{flowName}` | Execute a flow |

## Serving agents

Any agent defined with `genkit.beta().defineAgent(...)` / `defineCustomAgent(...)` is mounted automatically once the server starts — just define it before calling `spring.start()`. Agents are served at the **root path** (`/<agentName>`), separate from flows' `/api/flows/...` base:

```bash
curl -X POST http://localhost:8080/echoAgent \
  -H "Content-Type: application/json" \
  -d '{"data":{"message":{"role":"user","content":[{"text":"Hello, agent!"}]}}}'
```

For server-managed agents (those configured with a `SessionStore`), the companion endpoints `POST /<agentName>/getSnapshot` and `POST /<agentName>/abort` are mounted too. Streaming turns are available via `Accept: text/event-stream` or `?stream=true`.

The same Java client (`RemoteAgent` / `AgentChat`) works against a Spring or Jetty server unchanged. See [Serve over HTTP](../../agents/serve-over-http) for the full request/response contract, calling a served agent from Java, and how request headers reach your agent — and [Background Execution](../../agents/background-execution) for detached (background) turns.

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `port` | 8080 | Server port |
| `host` | 0.0.0.0 | Bind address |
| `basePath` | `/api` | Base path for endpoints |
| `contextPath` | `/` | Application context path |

## Features

Full Spring Boot ecosystem integration, including dependency injection, configuration, and middleware.

## Sample

See the [spring sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/spring).
