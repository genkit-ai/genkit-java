---
title: Run and Stream
description: Drive an agent turn-by-turn with AgentChat.send and sendStream, and read the AgentResponse and stream chunks.
---

The [Agents Overview](../overview) shows the basic `send` and `sendStream` calls. This page covers both methods in depth, the full `AgentResponse` surface, and every kind of chunk `sendStream` can deliver.

## `send` vs. `sendStream`

Both methods run exactly **one turn** and return an `AgentResponse<S>`. The only difference is whether you observe chunks as the turn runs — `send` gives you the final result, `sendStream` also fires a callback for each chunk along the way.

```java
// Blocking: you only see the final response.
AgentResponse<Map<String, Object>> res = chat.send("What is the weather in London?");

// Streaming: a callback fires per chunk, and you still get the same final response.
AgentResponse<Map<String, Object>> res2 = chat.sendStream(
    "What is the weather in Paris?",
    chunk -> {
        if (chunk.text() != null) {
            System.out.print(chunk.text());
        }
    });
```

Both accept either a plain `String` (wrapped into a user message) or a fully-formed `AgentInput`:

```java
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.Message;

AgentResponse<Map<String, Object>> res = chat.send(
    AgentInput.builder().message(Message.user("Custom-built input")).build());
```

## Reading the result — `AgentResponse<S>`

Both methods return the same `AgentResponse<S>` once the turn completes:

| Method | Returns |
|--------|---------|
| `text()` | The assistant message's text (`""` if none) |
| `message()` | The raw assistant `Message`, or `null` |
| `toolRequests()` | Tool-request parts on the assistant message |
| `interrupts()` | Pending interrupts — populated when `finishReason()` is `INTERRUPTED` |
| `finishReason()` | Why the turn ended: `STOP`, `LENGTH`, `BLOCKED`, `INTERRUPTED`, `ABORTED`, `DETACHED`, `FAILED`, `OTHER`, `UNKNOWN` |
| `snapshotId()` | This turn's snapshot ID (server-managed); `null` for client-managed |
| `sessionId()` | The session ID |
| `custom()` | Your custom state after the turn (populated for both modes) |
| `state()` | The full `SessionState<S>` — only for client-managed agents; server-managed agents return `null` here (use `snapshotId()` with a store lookup instead) |
| `artifacts()` | Artifacts produced this turn |
| `raw()` | The underlying `AgentOutput<S>` |

```java
AgentResponse<Map<String, Object>> res = chat.send("What is 2+2?");
System.out.println(res.text());          // "The answer is 4."
System.out.println(res.finishReason());  // stop
System.out.println(res.snapshotId());    // "1a2b3c…" (server-managed) or null (client-managed)
System.out.println(res.custom());        // whatever your agent tracked, e.g. {"turns": 1}
```

## Streaming chunks

The callback you pass to `sendStream` receives an `AgentChunk<S>` for each chunk, in order:

| Method | Returns |
|--------|---------|
| `text()` | Streamed model text, or `null` if this chunk carries no model text |
| `modelChunk()` | The raw model chunk, or `null` |
| `artifact()` | An artifact update, or `null` |
| `custom()` | Your custom state with this chunk's change applied, or `null` |
| `raw()` | The underlying chunk (exposes the turn-end signal) |

A single turn can produce a mix of chunks. Each chunk also carries a role via its underlying model chunk, so you can tell model output apart from other content.

### Model chunks

The agent streams model output as it's generated. For model-backed agents this is every token; for custom agents it's whatever you push through `ctx.sendChunk()`.

```java
chat.sendStream("Summarize this in one sentence", chunk -> {
    if (chunk.text() != null) {
        System.out.print(chunk.text()); // incremental tokens
    }
});
```

### Tool-response chunks

When the agent calls a tool during a turn, the tool's response streams too, so a client can show tool activity as it happens rather than waiting for the final message.

### Custom-state chunks

Each `updateCustom(...)` call inside an `AgentFn` streams the updated custom state. You read the result — the new state — directly through `chunk.custom()`.

```java
chat.sendStream("Do the thing", chunk -> {
    if (chunk.custom() != null) {
        System.out.println("Custom state now: " + chunk.custom());
    }
});
```

### Artifact chunks

When an `AgentFn` pushes an artifact update mid-turn, it arrives as an artifact chunk. (Artifacts added with `sess.addArtifacts(...)` instead attach to the final response rather than streaming.)

```java
chat.sendStream("Generate a report", chunk -> {
    if (chunk.artifact() != null) {
        System.out.println("Artifact update: " + chunk.artifact().getName());
    }
});
```

### Turn-end chunk

Every turn ends with a single chunk carrying a turn-end signal — your cue that no more chunks are coming — with the turn's final `snapshotId` and `finishReason`. This fires even when the turn failed, which is useful for a UI that needs to know exactly when to stop showing a "thinking" indicator.

```java
import com.google.genkit.ai.agent.TurnEnd;

chat.sendStream("hi", chunk -> {
    TurnEnd end = chunk.raw().getTurnEnd();
    if (end != null) {
        System.out.println("Turn ended: " + end.getFinishReason() + " @ " + end.getSnapshotId());
    }
});
```

## A custom agent that emits every chunk kind

A no-model `AgentFn` that emits all four chunk kinds in one turn:

```java
AgentFn<Map<String, Object>> fn = (sess, fnCtx) -> {
    // Model-style chunks
    fnCtx.sendChunk().accept(AgentStreamChunk.builder()
        .modelChunk(ModelResponseChunk.text("Hel")).build());
    fnCtx.sendChunk().accept(AgentStreamChunk.builder()
        .modelChunk(ModelResponseChunk.text("lo!")).build());

    // Custom-state change (streams to the caller)
    sess.updateCustom(cur -> {
        Map<String, Object> next = cur != null ? new HashMap<>(cur) : new HashMap<>();
        next.put("status", "thinking");
        return next;
    });

    // Artifact chunk (streamed mid-turn)
    fnCtx.sendChunk().accept(AgentStreamChunk.builder()
        .artifact(Artifact.builder().name("draft").parts(List.of()).build()).build());

    // The turn-end chunk is emitted for you after this returns.
    return AgentResult.builder()
        .message(Message.model("Hello!"))
        .finishReason(AgentFinishReason.STOP)
        .build();
};
```

Driving it with `chat.sendStream("say hello", ...)` delivers, in order: two model-text chunks (`"Hel"`, `"lo!"`), a chunk whose `custom()` shows `status = "thinking"`, an artifact chunk named `"draft"`, and a final turn-end chunk carrying `STOP` and the same `snapshotId()` as the returned response.

## Resuming after an interrupt

When a turn pauses for input (`finishReason() == INTERRUPTED`), `AgentChat.resume(List<Part>)` sends the caller's response and runs the next turn. The response is genuinely fed back to the model as part of the conversation, so the agent continues where it left off.

```java
AgentResponse<Map<String, Object>> interrupted = chat.send("please confirm");
if (interrupted.finishReason() == AgentFinishReason.INTERRUPTED) {
    Part respondPart = new Part();
    respondPart.setText("confirmed");
    AgentResponse<Map<String, Object>> resumed = chat.resume(List.of(respondPart));
}
```

See [Interrupts](../interrupts) for the full human-in-the-loop flow.

## See also

- [Define Agents](../define-agents) — `AgentConfig`, `CustomAgentConfig`, and writing an `AgentFn`
- [Agents Overview](../overview) — Quick-start and the beta opt-in flag
- [Sessions](../sessions) — Session lifecycle, snapshots, and resuming
- [Session Stores](../session-stores) — `InMemorySessionStore`, `FileSessionStore`, Firestore
- [Interrupts](../interrupts) — Pausing agents for human-in-the-loop confirmation
- [Background Execution](../background-execution) — Detached turns
- [Error Handling](../error-handling) — Failure modes and how they surface
