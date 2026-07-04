---
title: Custom Orchestration
description: Write your own AgentFn for full control over each turn — session state, streaming, and finish reasons, with no model required.
---

Most agents are prompt-backed: you give `defineAgent` a system prompt, tools, and a model, and it runs the model loop for you. A **custom agent** is the opposite — you write the per-turn logic yourself as an `AgentFn`, so you control exactly what happens on each turn. It's the right tool for deterministic, testable orchestration (a state machine, a rules engine, a pipeline of non-LLM steps) that still lives behind the same `AgentChat`, session, and streaming machinery as any other agent.

## The `AgentFn` contract

An `AgentFn<S>` receives everything it needs through two parameters — a `SessionRunner<S>` for reading and writing session state, and an `AgentFnContext` for per-turn signals — and returns an `AgentResult`:

```java
import com.google.genkit.ai.Message;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.InMemorySessionStore;
import java.util.HashMap;
import java.util.Map;

AgentFn<Map<String, Object>> orchestratorFn = (sess, ctx) -> {
    // Read state, do work, optionally emit chunks...
    return AgentResult.builder()
        .message(Message.model("done"))
        .finishReason(AgentFinishReason.STOP)
        .build();
};

Agent<Map<String, Object>> orchestrator = genkit.beta().defineCustomAgent(
    CustomAgentConfig.<Map<String, Object>>builder()
        .name("orchestrator")
        .store(new InMemorySessionStore<>())
        .build(),
    orchestratorFn);
```

`AgentResult` has three fields — `message`, `artifacts`, and `finishReason` (defaults to `STOP`). Whatever you return is what the caller sees as `AgentResponse.message()` / `.artifacts()` / `.finishReason()`.

## Reading and writing session state

`sess` (the `SessionRunner<S>`) is your handle onto everything the session knows:

| Method | Use |
|--------|-----|
| `sess.getMessages()` | The conversation so far, including this turn's user message |
| `sess.addMessages(Message...)` | Append messages (e.g. a tool-request/tool-response pair) |
| `sess.getCustom()` | A copy of the current custom state `S` |
| `sess.updateCustom(UnaryOperator<S>)` | Atomically read-modify-write the custom state |
| `sess.getArtifacts()` / `sess.addArtifacts(Artifact...)` | Artifacts so far / add new ones |
| `sess.turnIndex()` | How many turns have run in this invocation |

State you write persists across calls. Here a turn counter is carried forward via custom state:

```java
AgentFn<Map<String, Object>> fn = (sess, ctx) -> {
    Map<String, Object> custom = sess.getCustom();
    int previous = custom != null && custom.get("turnCount") != null
        ? (Integer) custom.get("turnCount")
        : 0;
    int turnCount = previous + 1;

    sess.updateCustom(old -> {
        Map<String, Object> updated = old != null ? new HashMap<>(old) : new HashMap<>();
        updated.put("turnCount", turnCount);
        return updated;
    });

    return AgentResult.builder()
        .message(Message.model("turn #" + turnCount))
        .finishReason(AgentFinishReason.STOP)
        .build();
};
```

Two calls to `chat.send(...)` against this agent return `"turn #1"` then `"turn #2"` — the second call sees the state the first one wrote.

## Emitting chunks during a turn

`AgentFnContext.sendChunk()` returns a `Consumer<AgentStreamChunk>`. Call it as often as you like to push incremental output to a caller using `chat.sendStream(...)`:

```java
AgentFn<Map<String, Object>> fn = (sess, ctx) -> {
    for (String step : List.of("fetching", "processing", "done")) {
        ctx.sendChunk().accept(AgentStreamChunk.builder().build());
        // Set .modelChunk(...) or .customPatch(...) on the builder to carry a real payload.
    }
    return AgentResult.builder()
        .message(Message.model("finished"))
        .finishReason(AgentFinishReason.STOP)
        .build();
};
```

Chunks arrive at the caller's `onChunk` callback. If the caller used `chat.send(...)` (no callback), the chunks simply go nowhere — so emitting them is always safe.

## Resuming a paused turn

If your `AgentFn` returns `AgentFinishReason.INTERRUPTED`, the caller can resolve it with `chat.resume(...)` — and the resume payload comes back to you through `ctx.resume()`. Branch on it to distinguish a fresh turn from a resumed one:

```java
AgentFn<Map<String, Object>> approvalAgent = (sess, ctx) -> {
    if (ctx.resume() == null) {
        // First turn: ask for confirmation and pause.
        return AgentResult.builder()
            .message(Message.model("Please confirm: transfer $150 to Alice? (yes/no)"))
            .finishReason(AgentFinishReason.INTERRUPTED)
            .build();
    }

    // Resumed turn: ctx.resume() carries the parts passed to chat.resume(...).
    boolean approved = /* read the response from ctx.resume() */ true;
    return AgentResult.builder()
        .message(Message.model(approved ? "Transfer completed." : "Transfer cancelled."))
        .finishReason(AgentFinishReason.STOP)
        .build();
};
```

See [Interrupts](../interrupts) for the caller-side flow.

## Cooperative abort

For a [detached (background) turn](../background-execution), a long-running body can poll `ctx.isAborted()` and stop early when the caller aborts:

```java
AgentFn<Map<String, Object>> fn = (sess, ctx) -> {
    for (int i = 0; i < 100 && !ctx.isAborted(); i++) {
        // do one unit of work
    }
    return AgentResult.builder()
        .finishReason(ctx.isAborted() ? AgentFinishReason.ABORTED : AgentFinishReason.STOP)
        .build();
};
```

Cooperative abort applies only to detached turns — a synchronous turn runs to completion. If you need to stop a foreground turn partway through, wire up your own cancellation flag that your `AgentFn` polls.

## Detach-awareness

If you configure a `SessionStore` and the caller sends `detach(true)`, the runtime runs your same `AgentFn` on a background thread and returns `DETACHED` immediately — your function doesn't need to do anything special. Streaming is automatically suppressed for a detached turn (no one is listening), so you can keep emitting chunks unconditionally. See [Background Execution](../background-execution) and [Error Handling](../error-handling) for how a detached failure surfaces.

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Define Agents](../define-agents) — `AgentConfig`, `CustomAgentConfig`, and the `AgentFn` contract
- [Run and Stream](../run-and-stream) — `AgentChat.send`/`sendStream`, `AgentResponse`, and chunk shapes
- [Sessions](../sessions) — Session lifecycle, snapshots, and the turn model
- [Session Stores](../session-stores) — Server-managed persistence backends
- [Interrupts](../interrupts) — Reading `chat.resume(...)`'s payload via `ctx.resume()`
- [Multi-agent Delegation](../multi-agent-delegation) — Composing custom agents as sub-agents
- [Error Handling](../error-handling) — Failure modes and how they surface
