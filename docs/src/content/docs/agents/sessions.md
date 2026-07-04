---
title: Sessions
description: Understand the agent session model, snapshots, and how to resume conversations.
---

Every agent conversation is backed by a **session** — a named container for conversation history, custom application state, and (optionally) artifacts. Each completed turn produces a **snapshot**: an immutable record of the session at that point. Snapshots are what let you resume a conversation later, or branch it in a new direction.

## Session and snapshot IDs

| Identifier | Scope | Purpose |
|------------|-------|---------|
| `sessionId` | The whole conversation | Groups all snapshots in one thread |
| `snapshotId` | A single turn | Identifies one turn's state, so you can resume from an exact point |

A new session is created on the first `send` when you don't supply either ID. The server assigns both, and you can read them back from `AgentResponse.sessionId()` and `AgentResponse.snapshotId()`.

## What happens on a turn

For each `chat.send(text)` call:

1. `AgentChat` adds the user message to the session history.
2. The agent's turn logic runs with that history. For a model-backed agent this is one model call with your system prompt, tools, and history.
3. The model may call tools (up to `maxTurns`).
4. The reply is written back as the assistant message.
5. If the agent has a `SessionStore`, the updated state is saved as a new snapshot and the `snapshotId` advances.
6. `AgentChat` updates its tracked state and returns an `AgentResponse`.

## Resuming a session

### By snapshot ID

Resume from an exact turn:

```java
import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.AgentChat;

AgentChat<Map<String, Object>> chat = weatherAgent.chat(ctx,
    AgentInit.<Map<String, Object>>builder()
        .snapshotId("f3a7-…")
        .build());

chat.send("Continue from where we left off");
```

### By session ID

Resume from a session's latest snapshot:

```java
AgentChat<Map<String, Object>> chat = weatherAgent.chat(ctx,
    AgentInit.<Map<String, Object>>builder()
        .sessionId("session-abc-123")
        .build());
```

### With `loadChat`

`loadChat` reads the snapshot from the store and hydrates the chat in one call:

```java
import com.google.genkit.ai.agent.GetSnapshotRequest;

AgentChat<Map<String, Object>> chat = weatherAgent.loadChat(ctx,
    GetSnapshotRequest.builder()
        .sessionId("session-abc-123")
        .build());

chat.send("Next turn");
```

## Client-managed sessions

When an agent has no `SessionStore`, `AgentChat` carries the full session state itself and passes the accumulated messages and custom state along on every `send`. This avoids any server-side persistence, at the cost of larger requests.

```java
AgentChat<Map<String, Object>> chat = statelessAgent.chat(ctx);
chat.send("Hello");          // fresh session
chat.send("And in French?"); // history round-tripped automatically
```

## Snapshots chain and branch

Each turn's snapshot descends from the one before it, forming a chain that is your conversation history. Because a snapshot is immutable, you can **branch** by resuming from an earlier one — the new turn starts a fresh chain from that point while the original chain stays intact.

```java
// Resume from an earlier snapshot to take a different path
AgentChat<Map<String, Object>> branch = weatherAgent.chat(ctx,
    AgentInit.<Map<String, Object>>builder()
        .snapshotId("snap-turn-2")
        .build());

branch.send("Take a different path");
```

## Aborting a turn

`AgentChat.abort()` marks the chat's latest snapshot as `ABORTED` in the session store:

```java
SnapshotStatus status = chat.abort();
System.out.println("Status after abort: " + status);
```

Abort applies to **background (detached) turns** — a detached turn that cooperatively checks `ctx.isAborted()` can stop early, and once a turn is aborted it stays aborted even if the background work later finishes. A synchronous `chat.send(...)` turn runs to completion regardless, so `abort()` on a foreground turn only records that the caller gave up rather than stopping work in progress. Abort needs a store that supports change notifications — `FileSessionStore` and the Firestore store do; `InMemorySessionStore` does not. See [Background Execution](../background-execution) for detached-turn mechanics.

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Define Agents](../define-agents) — `AgentFn` and `AgentFnContext`
- [Run and Stream](../run-and-stream) — `send`/`sendStream` and resuming after an interrupt
- [Session Stores](../session-stores) — Persistence backends
- [Background Execution](../background-execution) — Detached turns and their lifecycle
- [Error Handling](../error-handling) — Failure modes and how they surface
