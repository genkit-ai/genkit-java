---
title: Background Execution
description: Run slow agent turns in the background and poll for completion instead of blocking the caller.
---

Some agent turns are slow — a long tool chain, a batch job, a multi-minute model call. **Detaching** a turn starts the work in the background and returns to the caller immediately, so you can poll for the result later instead of blocking. This is useful for long-running jobs, HTTP requests that shouldn't hold a connection open, and any turn you'd rather not wait on inline.

Detach requires a **server-managed** agent — one configured with a `SessionStore` (via `AgentConfig.store(...)` / `CustomAgentConfig.store(...)`) — because there needs to be somewhere to persist the in-progress work and its eventual result.

## Detaching a turn

Set `detach(true)` on the input:

```java
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.Message;
import java.util.Map;

AgentResponse<Map<String, Object>> resp = chat.send(
    AgentInput.builder()
        .message(Message.user("Run the long report"))
        .detach(true)
        .build());

resp.finishReason();   // AgentFinishReason.DETACHED
String snapshotId = resp.snapshotId();   // poll this for the real result
```

The call returns almost immediately with `finishReason() == DETACHED` and a `snapshotId`. There's no message or state yet — those only exist once the background work finishes. Over HTTP the same shape comes back as JSON with a `"detached"` finish reason and a `snapshotId` (see [Serve over HTTP](../serve-over-http)).

A heartbeat keeps a long-running detached turn marked as alive, so a reader can tell a still-running turn apart from one whose process died.

## Polling for completion

Poll `getSnapshotData` (in-process) or `POST /<name>/getSnapshot` (over HTTP) until the snapshot leaves `PENDING`:

```java
import com.google.genkit.ai.agent.GetSnapshotRequest;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SnapshotStatus;

SessionSnapshot<Map<String, Object>> snap;
do {
    Thread.sleep(200);
    snap = agent.getSnapshotData(
        GetSnapshotRequest.builder().snapshotId(snapshotId).build());
} while (snap.getStatus() == SnapshotStatus.PENDING);

if (snap.getStatus() == SnapshotStatus.COMPLETED) {
    System.out.println(snap.getState().getMessages());
} else if (snap.getStatus() == SnapshotStatus.FAILED) {
    System.out.println("Failed: " + snap.getError().getMessage());
}
```

Once terminal, the snapshot carries the turn's real outcome — `finishReason` becomes `stop` (or whatever the turn actually produced), never `detached`. The `detached` reason only ever appears on the immediate response to the detach call itself.

### Snapshot statuses

| Status | Meaning |
|--------|---------|
| `PENDING` | Reserved and (usually) still running in the background |
| `COMPLETED` | The turn finished normally; `getState()` holds the result |
| `FAILED` | The turn threw; `getError()` holds the error |
| `ABORTED` | The turn was aborted while still pending — see below |
| `EXPIRED` | Reserved for store-specific expiry policies |

## When a background turn fails

A background turn that throws doesn't crash anything — the runtime records a `FAILED` snapshot with the error, which you see on your next poll:

```java
if (snap.getStatus() == SnapshotStatus.FAILED) {
    System.err.println("Detached turn failed: " + snap.getError().getMessage());
}
```

So you handle a background failure exactly where you handle a background success: at the poll site, by checking the status. See [Error Handling](../error-handling) for the full error contract.

## Aborting a detached turn

`chat.abort()` (or `POST /<name>/abort` over HTTP) cancels a still-running detached turn. It marks the snapshot `ABORTED` — so a subsequent poll sees `ABORTED` — and also signals the running turn to stop. A custom `AgentFn` that checks `ctx.isAborted()` in its loop observes this and can stop early:

```java
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.AgentFinishReason;

AgentFn<Map<String, Object>> longJob = (sess, ctx) -> {
    for (int i = 0; i < 1000 && !ctx.isAborted(); i++) {
        // do one unit of work per iteration
    }
    return AgentResult.builder()
        .finishReason(ctx.isAborted() ? AgentFinishReason.ABORTED : AgentFinishReason.STOP)
        .build();
};
```

A turn that never checks `ctx.isAborted()` still runs to the end (an abort can't force-kill work in flight), but the caller reliably sees `ABORTED` either way. Cooperative abort is specific to detached turns — a synchronous `chat.send(...)` turn always runs to completion.

Abort needs a store that supports live signalling (`FileSessionStore`, `FirestoreSessionStore`); with `InMemorySessionStore` there's nothing to poll or signal, so `abort()` is a no-op. See [Session Stores](../session-stores).

## Client-managed agents

Detach has no effect on a client-managed agent (no `SessionStore`): the turn just runs inline and returns its result normally, as if `detach` hadn't been set. Check `finishReason()` if your code needs to confirm a detach actually took effect.

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Run and Stream](../run-and-stream) — The normal (non-detached) `send`/`sendStream` turn model
- [Sessions](../sessions) — Snapshot lifecycle and the turn model
- [Session Stores](../session-stores) — Which stores support abort
- [Serve over HTTP](../serve-over-http) — Driving detach / getSnapshot / abort over the wire
- [Error Handling](../error-handling) — How errors surface on a `FAILED` snapshot
