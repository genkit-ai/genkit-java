---
title: Serve over HTTP
description: Expose defined agents as HTTP endpoints with the Jetty or Spring plugins, and call them from any client that speaks the wire format.
---

Any agent you define can be served over HTTP by the [Jetty](../../plugins/jetty) or [Spring](../../plugins/spring) plugin. Both plugins speak the **same wire format**, so the same Java client works unchanged against either one.

## Serving your agents

Agent endpoints are mounted automatically for every registered agent when the plugin starts — there's no separate opt-in beyond adding the plugin and starting it:

```java
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

Genkit genkit = Genkit.builder()
    .options(GenkitOptions.builder().experimental(true).build())
    .plugin(jetty)
    .build();

// Define your agent(s) before starting.
Agent<Map<String, Object>> echoAgent = genkit.beta().defineCustomAgent(
    CustomAgentConfig.<Map<String, Object>>builder()
        .name("echoAgent")
        .store(new FileSessionStore<>("./.snapshots"))
        .build(),
    (sess, fnCtx) -> AgentResult.builder()
        .message(Message.model("echo: " + sess.getMessages().get(sess.getMessages().size() - 1).getText()))
        .finishReason(AgentFinishReason.STOP)
        .build());

jetty.start(); // mounts /echoAgent (and its companions) alongside any flows
```

Swap in `SpringPlugin.create()` for the same result. Agent endpoints are mounted at the root path (`/<agentName>`), separate from flow endpoints, on the same port. See [Jetty Server](../../plugins/jetty) and [Spring Boot](../../plugins/spring) for plugin-specific setup.

## Endpoints

For an agent named `<name>`, the server mounts:

| Endpoint | Mounted when | Purpose |
|----------|--------------|---------|
| `POST /<name>` | Always | Runs one turn |
| `POST /<name>/getSnapshot` | Server-managed agents | Reads back a stored snapshot by `snapshotId` or `sessionId` |
| `POST /<name>/abort` | The store supports change notifications | Marks a pending snapshot aborted |

A client-managed agent (no store) only mounts the turn endpoint, since there's no server-side snapshot to fetch or abort.

## Running a turn

`POST /<name>` runs exactly one turn. The JSON body is an envelope with the turn input, optional resume/session `init`, and an optional `context` map:

```json
{"data": { /* AgentInput */ }, "init": { /* AgentInit */ }, "context": { /* optional */ }}
```

```bash
curl -X POST http://localhost:8080/echoAgent \
  -H "Content-Type: application/json" \
  -d '{"data":{"message":{"role":"user","content":[{"text":"Hello, agent!"}]}}}'
```

```json
{"result":{"sessionId":"876d78e6-…","snapshotId":"ca350aa8-…","message":{"text":"echo: Hello, agent!","role":"model","content":[{"text":"echo: Hello, agent!"}]},"finishReason":"stop"}}
```

### Streaming

Request Server-Sent Events with `Accept: text/event-stream` or `?stream=true`:

```bash
curl -N -X POST "http://localhost:8080/echoAgent?stream=true" \
  -H "Content-Type: application/json" \
  -d '{"data":{"message":{"role":"user","content":[{"text":"Hello again"}]}}}'
```

The response is `text/event-stream`: zero or more chunk frames, then exactly one terminal frame carrying the final result:

```
data: {"message": <chunk>}

data: {"result": <AgentOutput>}

```

A failed turn sends `data: {"error": {...}}` as the terminal frame instead. How much each frame carries depends on the agent — a model-backed agent streams model text as it's generated, while a custom agent streams only what it pushes through `ctx.sendChunk()`.

## Reading a snapshot

`POST /<name>/getSnapshot` (server-managed agents only) reads back a stored snapshot. This is the main way to poll a [detached turn](../background-execution) over HTTP.

```bash
curl -X POST http://localhost:8080/echoAgent/getSnapshot \
  -H "Content-Type: application/json" \
  -d '{"data":{"snapshotId":"ca350aa8-…"}}'
```

```json
{"result":{"snapshotId":"ca350aa8-…","sessionId":"876d78e6-…","status":"completed","finishReason":"stop","state":{"messages":[/* … */],"artifacts":[]}}}
```

## Aborting a turn

`POST /<name>/abort` marks a pending snapshot aborted:

```bash
curl -X POST http://localhost:8080/echoAgent/abort \
  -H "Content-Type: application/json" \
  -d '{"data":{"snapshotId":"29b02672-…"}}'
```

```json
{"result":{"snapshotId":"29b02672-…","status":"aborted"}}
```

This is most useful for [detached turns](../background-execution): a background turn that checks `ctx.isAborted()` can stop early, and once aborted the snapshot stays aborted even if the work finishes. The endpoint needs a store that supports change notifications (`InMemorySessionStore` does not; see [Session Stores](../session-stores)). See [Sessions](../sessions#aborting-a-turn) for the full abort behavior.

## Error responses

Errors use a structured envelope:

```json
{"error":{"status":"INVALID_ARGUMENT","message":"…","details":{"stack":"…"}}}
```

`status` maps to an HTTP code the same way on both plugins:

| `status` | HTTP code |
|----------|-----------|
| `INVALID_ARGUMENT`, `FAILED_PRECONDITION`, `OUT_OF_RANGE` | 400 |
| `UNAUTHENTICATED` | 401 |
| `PERMISSION_DENIED` | 403 |
| `NOT_FOUND` | 404 |
| `ALREADY_EXISTS`, `ABORTED` | 409 |
| `RESOURCE_EXHAUSTED` | 429 |
| `UNIMPLEMENTED` | 501 |
| `UNAVAILABLE` | 503 |
| `DEADLINE_EXCEEDED` | 504 |
| anything else | 500 |

See [Error Handling](../error-handling) for where these statuses come from.

## Calling a served agent from Java

`RemoteAgent.chat(...)` gives you an `AgentChat` backed by HTTP — the same client works against a Jetty- or Spring-backed server:

```java
import com.google.genkit.client.RemoteAgent;
import com.google.genkit.client.RemoteAgentOptions;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentResponse;

AgentChat<Map<String, Object>> chat = RemoteAgent.chat(
    RemoteAgentOptions.builder()
        .url("http://localhost:8080/echoAgent")
        .build());

AgentResponse<Map<String, Object>> resp = chat.send("hello");
System.out.println(resp.text());       // "echo: hello"
System.out.println(chat.snapshotId()); // the next send() resumes from here automatically

chat.abort(); // POSTs to /echoAgent/abort with the tracked snapshotId
```

For a client-managed agent, set `serverManaged(false)` so state round-trips in the request instead of being stored server-side:

```java
RemoteAgentOptions opts = RemoteAgentOptions.builder()
    .url("http://localhost:8080/counterAgent")
    .serverManaged(false)
    .build();
```

### Passing headers

Headers you set with `RemoteAgentOptions.headers(...)` are sent as real HTTP request headers, and the server makes them available to your `AgentFn` and tools through the request context under a `"headers"` key (standard framing headers like `content-type` and `host` are excluded). This is a natural place to carry an auth token:

```java
// Client
RemoteAgentOptions opts = RemoteAgentOptions.builder()
    .url("http://localhost:8080/echoAgent")
    .headers(Map.of("Authorization", "Bearer sk-…"))
    .build();
```

```java
// Server — inside an AgentFn or tool handler
Map<String, String> headers =
    (Map<String, String>) fnCtx.context().getContext().get("headers");
String token = headers != null ? headers.get("Authorization") : null;
```

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Run and Stream](../run-and-stream) — The `send`/`sendStream` semantics `AgentChat` wraps over HTTP
- [Sessions](../sessions) — Snapshot lifecycle and aborting
- [Session Stores](../session-stores) — Which stores support the `/abort` endpoint
- [Background Execution](../background-execution) — Detaching turns and polling over HTTP
- [Error Handling](../error-handling) — The status codes behind the error envelope
- [Jetty Server](../../plugins/jetty) — Jetty-specific setup and flow endpoints
- [Spring Boot](../../plugins/spring) — Spring-specific setup and flow endpoints
