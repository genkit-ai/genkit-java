---
title: Interrupts
description: Pause an agent turn for human-in-the-loop confirmation, then resume it with the caller's real response.
---

An **interrupt** pauses a turn so a human (or another system) can approve, reject, or supply data before the agent continues. A turn ends with `AgentFinishReason.INTERRUPTED` when a tool the model called needs input instead of returning a result. You inspect the pending interrupts, gather the answer, and then call `chat.resume(...)` to feed the tool's response back so the model can finish its work.

This works the same way whether the agent is prompt-backed (`defineAgent`/`definePromptAgent`) or a custom `AgentFn`.

## A complete example: a confirmation gate

Define an interrupt tool with `genkit.defineInterrupt(...)`. When the model calls it, the turn pauses instead of executing:

```java
import com.google.genkit.ai.InterruptConfig;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentConfig;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentInterrupt;
import com.google.genkit.ai.agent.AgentResponse;
import java.util.List;
import java.util.Map;

Tool<TransferRequest, ConfirmationOutput> confirmTransfer =
    genkit.defineInterrupt(
        InterruptConfig.<TransferRequest, ConfirmationOutput>builder()
            .name("confirmTransfer")
            .description("Ask for confirmation before transferring money.")
            .inputType(TransferRequest.class)
            .outputType(ConfirmationOutput.class)
            .inputSchema(Map.of("type", "object", "properties", Map.of(
                "recipient", Map.of("type", "string"),
                "amount", Map.of("type", "number"))))
            .build());

Agent<Map<String, Object>> bankingAgent = genkit.beta().defineAgent(
    AgentConfig.<Map<String, Object>>builder()
        .name("bankingAgent")
        .system("You are a banking assistant. Use confirmTransfer before any transfer.")
        .tools(confirmTransfer)
        .model("openai/gpt-4o-mini")
        .build());

AgentChat<Map<String, Object>> chat = bankingAgent.chat();

// 1. The turn pauses when the model calls confirmTransfer.
AgentResponse<Map<String, Object>> res = chat.send("Transfer $150 to Alice for dinner");

if (res.finishReason() == AgentFinishReason.INTERRUPTED) {
    AgentInterrupt interrupt = res.interrupts().get(0);
    System.out.println("Tool: " + interrupt.name());    // "confirmTransfer"
    System.out.println("Input: " + interrupt.input());   // {recipient=Alice, amount=150.0}

    // 2. Ask the human, then build the tool's response and resume.
    ConfirmationOutput approved = new ConfirmationOutput(true, "User approved");
    Part responsePart = confirmTransfer.respond(interrupt.part(), approved);

    AgentResponse<Map<String, Object>> resumed = chat.resume(List.of(responsePart));
    System.out.println(resumed.text());          // e.g. "Done — I've transferred $150 to Alice."
    System.out.println(resumed.finishReason());   // STOP
}
```

## Inspecting interrupts

When `finishReason() == AgentFinishReason.INTERRUPTED`, `res.interrupts()` returns one `AgentInterrupt` per tool the model wanted to run but couldn't. Each one exposes:

| Method | Returns |
|--------|---------|
| `name()` | The interrupted tool's name |
| `input()` | The input the model wanted to call the tool with |
| `part()` | The originating tool-request part — pass this to `respond(...)` / `restart(...)` |

A single turn can raise several interrupts at once; collect a response for each and pass them all to `chat.resume(List.of(...))` together.

## Resuming with a response

`chat.resume(responseParts)` supplies the tool's output and continues the turn. Build each part with the interrupted tool's `respond(...)` helper:

```java
Part responsePart = confirmTransfer.respond(interrupt.part(), new ConfirmationOutput(true, "ok"));
AgentResponse<Map<String, Object>> resumed = chat.resume(List.of(responsePart));
```

The model genuinely sees the tool response and reasons about it — it continues the same conversation rather than starting over, and the tool response is recorded in the session history.

## Restarting a tool call

Sometimes you don't want to answer the tool — you want to run it again with corrected input or an approval stamp. `chat.restart(restartParts)` re-executes the interrupted tool call. Build each part with the tool's `restart(...)` helper, optionally passing replacement input:

```java
// Retry the transfer with a corrected amount and an "approved" marker.
Part restartPart = confirmTransfer.restart(
    interrupt.part(),
    Map.of("approved", true),               // resumed metadata
    new TransferRequest("Alice", 120.0));   // replacement input

AgentResponse<Map<String, Object>> resumed = chat.restart(List.of(restartPart));
```

Inside the tool handler, a restart-aware tool can tell it's being re-invoked and read the metadata you supplied via the tool context:

```java
(ctx, input) -> {
    if (ctx.isResumed()) {
        // ctx.getResumed() holds the resumed metadata (e.g. {"approved": true}).
        // input is the replacement input if you passed one to restart(...).
        return doTransfer(input);
    }
    throw new ToolInterruptException(); // fresh call → pause for confirmation
};
```

Use `respond(...)` when you're answering the tool's question; use `restart(...)` when you're re-running it with new input or an approval decision.

## Limitations

- A synchronous `chat.send(...)` / `chat.resume(...)` turn runs to completion — you can't cancel it midway. Cooperative cancellation applies to [detached (background) turns](../background-execution).
- Referencing a tool call that isn't actually pending, or restarting with input that doesn't match the schema, fails cleanly with an `INVALID_ARGUMENT` error rather than crashing the turn — see [Error Handling](../error-handling).

## See also

- [Agents Overview](../overview) — Defining agents and the chat API
- [Define Agents](../define-agents) — `AgentConfig`, `CustomAgentConfig`, and the `AgentFn` contract
- [Run and Stream](../run-and-stream) — `AgentResponse`, `finishReason()`, and the response surface
- [Multi-agent Delegation](../multi-agent-delegation) — How an interrupted sub-agent surfaces to a parent
- [Error Handling](../error-handling) — How `FAILED` turns differ from `INTERRUPTED` ones
- [Custom Orchestration](../custom-orchestration) — Reading resume data in your own `AgentFn`
