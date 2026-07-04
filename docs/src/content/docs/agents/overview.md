---
title: Agents Overview
description: Build stateful, multi-turn AI agents with tool-calling and session persistence using the Genkit Agents API.
---

Agents are stateful, multi-turn AI actors. Unlike a single `generate` call, an agent carries conversation history across turns, calls tools, and can persist its session so a conversation survives across requests, processes, or machines. The Agents API is in **beta**, so you opt in explicitly.

## Enabling the beta API

Enable experimental features when you build `Genkit`, then reach the agent factories through `genkit.beta()`:

```java
Genkit genkit = Genkit.builder()
    .options(GenkitOptions.builder().experimental(true).build())
    .plugin(OpenAIPlugin.create())
    .build();
```

You can also set `GENKIT_EXPERIMENTAL=true` in the environment before your process starts. Without the flag, calling `genkit.beta()` throws.

## Defining an agent

Use `defineAgent` to register a model-backed agent. The only required field is `name`; everything else is optional.

```java
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.FileSessionStore;

Agent<Map<String, Object>> weatherAgent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("weatherAgent")
        .description("A helpful weather assistant")
        .system("You are a helpful weather assistant. Use the getWeather tool.")
        .tools(getWeather)                              // Tool objects from genkit.defineTool
        .model("openai/gpt-4o-mini")
        .store(new FileSessionStore<>("./.snapshots"))  // persist the session; omit to keep it client-side
        .build());
```

The options you'll reach for most often:

| Field | Description |
|-------|-------------|
| `name` | Registered name; also how the agent appears in the Dev UI |
| `system` | System prompt sent at the start of every turn |
| `tools` | Tools the model may call during a turn |
| `model` | Model ID; falls back to your Genkit default model |
| `config` | Generation settings such as temperature and max tokens |
| `maxTurns` | Cap on tool-calling turns within a single `send` |
| `store` | A `SessionStore` to persist the session server-side; omit for client-managed state |
| `stateType` | Java class for your custom session state |

See [Define agents](../define-agents) for the full configuration surface and for writing your own turn logic.

## Starting a chat

`Agent.chat(...)` returns an `AgentChat` that manages turn-by-turn history for you.

```java
AgentChat<Map<String, Object>> chat = weatherAgent.chat();
```

To continue an earlier conversation, seed the chat with a `snapshotId` or `sessionId`:

```java
import com.google.genkit.ai.agent.AgentInit;

AgentChat<Map<String, Object>> chat = weatherAgent.chat(
    AgentInit.<Map<String, Object>>builder()
        .snapshotId("existing-snapshot-id")
        .build());
```

## Sending messages

Call `send` for a blocking turn, or `sendStream` to observe chunks as the turn runs. Both return the same `AgentResponse`, and both automatically carry state forward to the next turn.

```java
AgentResponse<Map<String, Object>> res = chat.send("What is the weather in London?");
System.out.println(res.text()); // "It is sunny and 22°C in London."

// History carries forward automatically:
AgentResponse<Map<String, Object>> res2 = chat.send("Now say that in French");
System.out.println(res2.text());
```

```java
chat.sendStream("Summarize in one sentence", chunk -> {
    if (chunk.text() != null) {
        System.out.print(chunk.text()); // incremental tokens
    }
});
```

`AgentResponse` gives you everything about the completed turn:

| Method | Returns |
|--------|---------|
| `text()` | The assistant message's text |
| `finishReason()` | Why the turn ended (`STOP`, `LENGTH`, `INTERRUPTED`, …) |
| `snapshotId()` | This turn's snapshot ID (server-managed) |
| `sessionId()` | The session ID |
| `custom()` | Your custom session state after the turn |
| `artifacts()` | Any artifacts produced this turn |
| `interrupts()` | Pending interrupts when the turn paused for input |

See [Run and stream](../run-and-stream) for the full `AgentResponse` surface and every chunk shape.

## Where session state lives

You choose where an agent's session is stored:

| Mode | How to enable | Where state lives |
|------|---------------|-------------------|
| **Server-managed** | Pass a `SessionStore` to `.store(...)` | The store (memory, disk, Firestore, …) |
| **Client-managed** | Omit `.store(...)` | Carried by `AgentChat`, round-tripped each turn |

Server-managed sessions are the norm when you want conversations to persist or to run across processes. Client-managed sessions are handy for stateless servers or when you'd rather own the conversation history yourself — `AgentChat` carries the messages and custom state along on every turn, so the model always sees the full context.

```java
// Client-managed (stateless) agent — no .store(...)
Agent<Map<String, Object>> statelessAgent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("weatherAgentStateless")
        .system("You are a helpful weather assistant.")
        .tools(getWeather)
        .model("openai/gpt-4o-mini")
        .build());

AgentChat<Map<String, Object>> statelessChat = statelessAgent.chat(ctx);
statelessChat.send("What is the weather in Tokyo?");
statelessChat.send("Compare that to Paris"); // history round-trips automatically
```

## Custom agents (no model required)

`defineAgent` builds a model-backed turn for you. When you want full control over what happens on each turn — a deterministic workflow, or an agent that only sometimes calls a model — use `defineCustomAgent` and write the turn logic yourself as an `AgentFn`:

```java
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.Message;

AgentFn<Map<String, Object>> echoFn = (sess, fnCtx) -> {
    String userText = sess.getMessages().get(sess.getMessages().size() - 1).getText();
    return AgentResult.builder()
        .message(Message.model("echo: " + userText))
        .finishReason(AgentFinishReason.STOP)
        .build();
};

Agent<Map<String, Object>> echoAgent = genkit.beta().defineCustomAgent(
    CustomAgentConfig.<Map<String, Object>>builder()
        .name("echoAgent")
        .store(new InMemorySessionStore<>())
        .build(),
    echoFn);
```

See [Define agents](../define-agents) for the full `AgentFn` and `SessionRunner` API.

## Full example

See [`samples/agents-weather`](https://github.com/genkit-ai/genkit-java/tree/main/samples/agents-weather) for a complete, runnable example that combines both modes and demonstrates streaming.

## See also

- [Define Agents](../define-agents) — `AgentConfig`, `CustomAgentConfig`, and writing an `AgentFn`
- [Run and Stream](../run-and-stream) — `send`/`sendStream`, `AgentResponse`, and chunk shapes
- [Sessions](../sessions) — Session lifecycle, snapshots, and resuming
- [Session Stores](../session-stores) — `InMemorySessionStore`, `FileSessionStore`, Firestore
- [Interrupts](../interrupts) — Pausing agents for human-in-the-loop confirmation
- [Multi-agent Delegation](../multi-agent-delegation) — Composing multiple agents together
- [Custom Orchestration](../custom-orchestration) — Driving agents outside of `AgentChat`
- [Error Handling](../error-handling) — Failure modes and how they surface
