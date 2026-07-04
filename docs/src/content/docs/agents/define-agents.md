---
title: Define Agents
description: Build model-backed agents with AgentConfig, or write your own per-turn logic with CustomAgentConfig and AgentFn.
---

The [Agents Overview](../overview) shows the quick path with `defineAgent`. This page covers the two ways to define an agent, the full configuration surface, and how to write your own turn logic with `AgentFn` when you need more control.

## Two ways to define an agent

| Factory | You provide | Per-turn logic |
|---------|-------------|----------------|
| `defineAgent(AgentConfig)` | A system prompt, model, and tools | Built for you: one model call per turn, with your history and tools |
| `defineCustomAgent(CustomAgentConfig, AgentFn)` | An `AgentFn` | You write it: your function runs once per turn |

Both register the same kind of `Agent<S>`, so everything in [Run and Stream](../run-and-stream), [Sessions](../sessions), and [Session Stores](../session-stores) applies to agents defined either way. The only difference is what runs during a turn.

## Model-backed agents: `defineAgent`

Pass an `AgentConfig`. Only `name` is required.

```java
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.FileSessionStore;

Agent<Map<String, Object>> weatherAgent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("weatherAgent")
        .description("A helpful weather assistant")
        .system("You are a helpful weather assistant. Use the getWeather tool.")
        .tools(getWeather)
        .model("openai/gpt-4o-mini")
        .maxTurns(5)
        .store(new FileSessionStore<>("./.snapshots"))
        .build());
```

Each turn runs one model call with your system prompt, tools, and the full conversation history. The model may call tools during the turn (up to `maxTurns`), and the request context flows into those tool calls, so a tool can read caller info such as auth.

### `AgentConfig` fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` (required) | Registered name; appears as `/agent/<name>` in the Dev UI |
| `description` | `String` | Human-readable description surfaced in agent metadata |
| `model` | `String` | Model ID; falls back to your Genkit default model |
| `system` | `String` | System prompt sent to the model every turn |
| `tools` | `List<Tool<?, ?>>` / varargs | Tools the model may call during a turn |
| `config` | `GenerationConfig` | Temperature, max output tokens, and other generation settings |
| `maxTurns` | `Integer` | Cap on tool-calling turns within a single turn |
| `store` | `SessionStore<S>` | Persist the session server-side; omit for client-managed |
| `stateType` | `Class<S>` | Java class for your custom session state |
| `clientTransform` | `ClientTransform<S>` | Adjusts outgoing state before it reaches the caller (client-managed) |
| `promptName` | `String` | Name of a registered prompt for `definePromptAgent` |
| `promptInput` | `Object` | Input interpolated into the prompt template each turn |

If you set `stateType` to a typed class, the agent's registered metadata includes a generated JSON schema for that state, which makes the shape discoverable to the Dev UI and schema-aware clients. A bare `Map<String, Object>` produces no schema, since an untyped map has no useful shape.

### Prompt-backed agents: `definePromptAgent`

`definePromptAgent` is a variant of `defineAgent` that draws its system instructions from a registered prompt instead of the `system` string. The prompt template is rendered **every turn**, interpolating `promptInput` and the current session state, so template variables like `{{topic}}` are filled in with live values for that turn. If no matching prompt is found (or its template is blank), it falls back to the `system` field.

```java
Agent<Map<String, Object>> supportAgent = genkit.beta().definePromptAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("supportAgent")
        .promptName("supportSystem")             // a registered prompt
        .promptInput(Map.of("product", "Genkit"))
        .model("openai/gpt-4o-mini")
        .store(new FileSessionStore<>("./.snapshots"))
        .build());
```

A multi-message prompt is rendered into a single system instruction: variable interpolation works, but a `.prompt` file with several message roles isn't split into separate messages.

## Custom agents: `defineCustomAgent` and `AgentFn`

When a model-backed turn isn't the right shape — a deterministic workflow, an agent that only sometimes calls a model, or one that calls a model in a way `defineAgent` doesn't expose — write the turn yourself.

```java
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Role;
import java.util.List;
import java.util.Map;

AgentFn<Map<String, Object>> echoFn = (sess, fnCtx) -> {
    List<Message> msgs = sess.getMessages();
    String userText = "";
    for (int i = msgs.size() - 1; i >= 0; i--) {
        if (msgs.get(i).getRole() == Role.USER) {
            userText = msgs.get(i).getText();
            break;
        }
    }
    return AgentResult.builder()
        .message(Message.model("echo: " + userText))
        .finishReason(AgentFinishReason.STOP)
        .build();
};

Agent<Map<String, Object>> echoAgent = genkit.beta().defineCustomAgent(
    CustomAgentConfig.<Map<String, Object>>builder()
        .name("docsCustomAgent")
        .description("A no-model custom agent")
        .store(new InMemorySessionStore<>())
        .build(),
    echoFn);
```

This agent never calls a model. An `AgentFn` is a plain function that runs once per turn and returns an `AgentResult`.

### `CustomAgentConfig` fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` (required) | Registered name |
| `description` | `String` | Human-readable description |
| `stateType` | `Class<S>` | Java class for your custom session state |
| `store` | `SessionStore<S>` | Persist the session server-side; omit for client-managed |
| `clientTransform` | `ClientTransform<S>` | Adjusts outgoing state before it reaches the caller (client-managed) |
| `storeOptions` | `SessionStoreOptions` | Options forwarded to every store operation (e.g. multi-tenant scoping) |

### Writing an `AgentFn`

```java
@FunctionalInterface
public interface AgentFn<S> {
  AgentResult run(SessionRunner<S> sess, AgentFnContext ctx) throws Exception;
}
```

Your function receives two objects. `SessionRunner<S>` lets you read and update the session for this turn; `AgentFnContext` gives you the stream sink, the abort signal, the request context, and any resume payload from the caller.

#### Reading and updating the session — `SessionRunner<S>`

| Method | Description |
|--------|-------------|
| `sessionId()` | The session's ID |
| `getMessages()` | The conversation so far, including this turn's user message |
| `addMessages(Message...)` | Append extra messages beyond the reply you return |
| `getCustom()` | The current custom state `S` |
| `updateCustom(UnaryOperator<S>)` | Apply a function to the custom state; also streams the change to streaming callers |
| `getArtifacts()` / `addArtifacts(Artifact...)` | Read or append turn artifacts |
| `turnIndex()` | Number of turns completed so far |
| `lastSnapshotId()` / `lastSnapshot()` | The most recently persisted snapshot (server-managed) |
| `lastTurnFinishReason()` / `lastTurnError()` | Outcome of the previous turn |

A typical `AgentFn` reads `sess.getMessages()` for context, does its work, optionally calls `sess.updateCustom(...)` or `sess.addArtifacts(...)`, and returns the reply message in an `AgentResult`. The runtime appends that reply to the session for you, so use `addMessages(...)` only when you need to add messages *beyond* the reply.

#### Streaming, abort, context, and resume — `AgentFnContext`

| Method | Description |
|--------|-------------|
| `sendChunk()` | The sink for pushing chunks (model text, artifacts, …) to a `sendStream` caller mid-turn |
| `isAborted()` | Whether this turn has been asked to stop (see [Background Execution](../background-execution)) |
| `context()` | The request context, carrying caller info such as auth and request headers |
| `resume()` | The caller's response to a pending interrupt, or `null` when this isn't a resume turn |

Tools and other code you call synchronously from inside an `AgentFn` can reach the active session through `AgentSessionContext.current()` without you threading it through every call, which is how the [multi-agent delegation](../multi-agent-delegation) tools cooperate.

### Returning an `AgentResult`

```java
AgentResult.builder()
    .message(assistantReply)   // appended to session history for you
    .artifacts(extraArtifacts) // optional; merged into the session
    .finishReason(AgentFinishReason.STOP) // defaults to STOP
    .build();
```

If your `AgentFn` throws instead of returning, the turn is recorded as failed rather than raising to the caller: `send`/`sendStream` return an `AgentResponse` whose `finishReason()` is `FAILED`. See [Error Handling](../error-handling) for the full picture.

## Where session state lives

Both `defineAgent` and `defineCustomAgent` choose server- vs. client-managed the same way: pass a `SessionStore<S>` to `.store(...)` to persist server-side, or omit it to round-trip the full state through `AgentChat` each turn. See [Agents Overview](../overview#where-session-state-lives) and [Session Stores](../session-stores) for the available stores.

## See also

- [Agents Overview](../overview) — Quick-start and the beta opt-in flag
- [Run and Stream](../run-and-stream) — `send`/`sendStream`, `AgentResponse`, and chunk shapes
- [Sessions](../sessions) — Session lifecycle, snapshots, and resuming
- [Session Stores](../session-stores) — `InMemorySessionStore`, `FileSessionStore`, Firestore
- [Interrupts](../interrupts) — Pausing agents for human-in-the-loop confirmation
- [Multi-agent Delegation](../multi-agent-delegation) — Composing multiple agents together
- [Custom Orchestration](../custom-orchestration) — Driving agents outside of `AgentChat`
- [Error Handling](../error-handling) — Failure modes and how they surface
