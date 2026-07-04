---
title: Error Handling
description: When a turn returns a FAILED response versus when chat.send() throws, for both foreground and background turns.
---

Agents draw a clear line between two kinds of error, and knowing which is which tells you whether to check a return value or wrap a call in `try`/`catch`:

- **A turn that fails while running** — your `AgentFn` or a tool it calls throws — never propagates out of `chat.send(...)`. It comes back as a normal `AgentResponse` with `finishReason() == FAILED` and an error attached.
- **Malformed input** — misusing the API, e.g. building an `AgentInput` with the wrong shape — throws a `GenkitException` before any turn runs.

The rule of thumb: **a running turn reports failure in its response; API misuse throws.**

## A failing turn returns FAILED

You don't need a `try`/`catch` around `chat.send(...)` to catch a turn that throws — check `finishReason()` instead:

```java
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.RuntimeError;

AgentFn<Map<String, Object>> throwingFn = (sess, ctx) -> {
    throw new RuntimeException("boom: downstream service unavailable");
};

Agent<Map<String, Object>> agent = genkit.beta().defineCustomAgent(
    CustomAgentConfig.<Map<String, Object>>builder()
        .name("flaky")
        .store(new InMemorySessionStore<>())
        .build(),
    throwingFn);

// No try/catch needed — this returns normally.
AgentResponse<Map<String, Object>> resp = agent.chat().send("do something");

if (resp.finishReason() == AgentFinishReason.FAILED) {
    RuntimeError err = resp.raw().getError();
    System.out.println("Turn failed: " + err.getMessage());  // "boom: downstream service unavailable"
    // decide whether to retry, surface to the user, etc.
}
```

`RuntimeError` carries a short `status` code (such as `"INTERNAL"`), a `message`, and optional `details`. This is the same for prompt-backed agents: if the model call or a tool fails, the turn comes back `FAILED` rather than throwing.

## What does throw: malformed input

Building input by hand with an invalid shape is treated as a programming error and throws right away, before any turn runs:

```java
// A tool-response part on a user message (those must go through chat.resume(...)):
Part badPart = new Part();
badPart.setToolResponse(new ToolResponse());
Message badMessage = new Message(Role.USER, List.of(badPart));

// Throws GenkitException (INVALID_ARGUMENT) — not a FAILED response.
chat.send(AgentInput.builder().message(badMessage).build());
```

The rejected cases are: a message with a role other than `USER`, and a user message that carries `toolRequest`/`toolResponse` parts (those belong in `chat.resume(...)`). You only need to guard against this if you construct `AgentInput` yourself from untrusted shapes — the plain `chat.send(String)` / `chat.sendStream(String, ...)` overloads always build valid input.

## Resume that references a stale tool call

Resolving an interrupt that isn't actually pending — resuming a tool call that was never interrupted, or restarting with input that doesn't match the tool's schema — fails cleanly with an `INVALID_ARGUMENT` error rather than crashing the turn. Treat it the same as any other malformed-input case. See [Interrupts](../interrupts) for the normal resume/restart flow.

## Background (detached) turn failures

A [detached turn](../background-execution) has already returned to the caller before it runs, so a failure can't come back through the original call — it surfaces on the persisted snapshot instead. The immediate response is `DETACHED`; poll the snapshot and check its status:

```java
AgentResponse<Map<String, Object>> immediate = chat.send(
    AgentInput.builder()
        .message(Message.user("start the long job"))
        .detach(true)
        .build());

String snapshotId = immediate.snapshotId();

SessionSnapshot<Map<String, Object>> snap;
do {
    Thread.sleep(100);
    snap = agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
} while (snap.getStatus() == SnapshotStatus.PENDING);

if (snap.getStatus() == SnapshotStatus.FAILED) {
    System.out.println("Detached turn failed: " + snap.getError().getMessage());
}
```

So background failures are handled where background successes are — at the poll site, by checking the snapshot status.

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Define Agents](../define-agents) — `AgentConfig`, `CustomAgentConfig`, and the `AgentFn` contract
- [Run and Stream](../run-and-stream) — `AgentResponse`, `finishReason()`, and the response surface
- [Sessions](../sessions) — Session lifecycle, snapshots, and abort
- [Interrupts](../interrupts) — How `INTERRUPTED` differs from `FAILED`
- [Background Execution](../background-execution) — Detached turns and how failures surface on a snapshot
- [Custom Orchestration](../custom-orchestration) — Writing the `AgentFn` whose failures this page describes
