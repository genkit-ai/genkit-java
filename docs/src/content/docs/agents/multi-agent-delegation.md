---
title: Multi-agent Delegation
description: Let one agent delegate self-contained tasks to specialized sub-agents by exposing them as tools.
---

Delegation lets a *parent* agent hand a self-contained task to a specialized *sub-agent*. The middleware plugin's `Agents` helper turns any set of already-registered agents into ordinary tools the parent can call — the parent model decides when to delegate, and the sub-agent's answer flows back into the parent's reasoning like any other tool result.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-middleware</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

## Setting up delegation

Register your sub-agents first, then build delegation tools from their names and pass them to the parent:

```java
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentConfig;
import com.google.genkit.plugins.middleware.Agents;
import com.google.genkit.plugins.middleware.AgentsOptions;
import java.util.List;
import java.util.Map;

// researcher and writer are already registered via genkit.beta().defineAgent(...) etc.
AgentsOptions options = AgentsOptions.builder()
    .agents("researcher", "writer")
    .build();

List<Tool<?, ?>> delegationTools = Agents.delegationTools(options);
String subAgentsFragment = Agents.systemPromptFragment(options);

Agent<Map<String, Object>> orchestrator = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("orchestrator")
        .system("You coordinate a research task.\n\n" + subAgentsFragment)
        .tools(delegationTools.toArray(new Tool<?, ?>[0]))
        .model("openai/gpt-4o-mini")
        .build());
```

Each sub-agent becomes a tool named `<toolPrefix>_<agentName>` — with the default prefix that's `delegate_to_researcher`, `delegate_to_writer`, and so on. The parent calls a delegation tool with a `{"task": "..."}` payload; the sub-agent runs one turn against that task and returns its answer. `Agents.systemPromptFragment(options)` gives you a ready-made description of the available sub-agents to splice into the parent's system prompt.

## Options

| Field | Default | Description |
|-------|---------|-------------|
| `agents` | *(required)* | Sub-agent names to expose, at least one |
| `toolPrefix` | `"delegate_to"` | Tool-name prefix; an empty string uses the bare agent name |
| `maxDelegations` | `0` (unlimited) | Cap on total delegation calls across all tools from one `delegationTools(...)` call |
| `historyLength` | `0` (task only) | How many trailing prior messages of this sub-agent's own conversation to forward on each call |
| `artifactStrategy` | `ArtifactStrategy.INLINE` | Whether a delegated artifact's content is inlined in the tool output or referenced by name only |

## Accumulating sub-agent history

By default each delegation forwards only the current `task`. Set `historyLength` to let a sub-agent build up its own conversation across repeated delegations *within the same parent conversation*:

```java
AgentsOptions options = AgentsOptions.builder()
    .agents("researcher")
    .historyLength(10) // forward up to 10 trailing messages of prior delegation context
    .build();
```

With this set, the second time the parent delegates to `researcher` it sees the previous task and answer plus the new task, and so on — trimmed to the last `historyLength` messages. Each (parent conversation, sub-agent) pair keeps its own isolated history, so delegating to `researcher` never leaks into `writer`, and a different parent conversation starts fresh.

History trimming applies to client-managed sub-agents (no `SessionStore`). A server-managed sub-agent resumes its full stored history on each call; if you need bounded context there, have each `task` restate what the sub-agent needs.

## Interrupted and failed sub-agents

A sub-agent isn't a black box — its outcome surfaces to the parent's tool loop as a real event, not swallowed text:

- **Interrupt.** If a sub-agent pauses for confirmation (see [Interrupts](../interrupts)), the delegation tool raises that interrupt to the parent. The parent's own turn finishes `INTERRUPTED`, and `AgentResponse.interrupts()` reports it — exactly as if the parent had called an interrupting tool directly.
- **Failure.** If a sub-agent's turn fails, the delegation tool raises a real error carrying the sub-agent's message, which propagates through the parent's tool loop like any other tool exception.

One thing to keep in mind: resolving the *parent's* interrupt with `chat.resume(...)` acknowledges that a sub-agent is paused, but does not automatically re-drive the sub-agent's own paused turn. If a delegated sub-agent might interrupt and you need the human's answer to reach it, have the sub-agent resolve its own confirmation (for example against a default policy or its own tracked state) rather than expecting the answer to thread all the way down.

## Artifacts across the delegation boundary

Any artifact a sub-agent produces is namespaced (as `<invocationId>/<originalName>`) and merged into the parent's artifacts, so it shows up in the parent's own `AgentResponse.artifacts()` and — for a server-managed parent — in its session store. With the default `ArtifactStrategy.INLINE`, the artifact's content is also folded into the delegation tool's result so the parent model can read it directly. Switch to `ArtifactStrategy.SESSION` to return only the namespaced name and read the content back from the parent's session yourself:

```java
AgentsOptions options = AgentsOptions.builder()
    .agents("writer")
    .artifactStrategy(ArtifactStrategy.SESSION) // names only, no inline content
    .build();
```

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Define Agents](../define-agents) — `AgentConfig`, `CustomAgentConfig`, and the `AgentFn` contract
- [Interrupts](../interrupts) — What "interrupted" means for a single agent
- [Sessions](../sessions) — Session lifecycle, snapshots, and the turn model
- [Custom Orchestration](../custom-orchestration) — Driving multiple agents yourself instead of via delegation tools
- [Error Handling](../error-handling) — The general `FAILED`/error contract delegation builds on
