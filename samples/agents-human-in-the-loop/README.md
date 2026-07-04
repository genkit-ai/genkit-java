# Agents: Human-in-the-Loop (Interrupts) Sample

A command-line banking assistant agent that **pauses on a sensitive action** — a money transfer — and waits for you to approve or reject it before continuing.

It demonstrates the agent-level interrupt/resume flow:

- A tool created with `genkit.defineInterrupt(...)` pauses the turn instead of executing.
- The turn finishes with `AgentFinishReason.INTERRUPTED`, and `AgentResponse.interrupts()` surfaces the pending tool request.
- The caller resolves it with `tool.respond(interrupt.part(), output)` and resumes the turn with `AgentChat.resume(...)`.

Conversation state is persisted server-side with `FileSessionStore` (under `./.snapshots`).

## Prerequisites

- Java 21+ and Maven 3.6+
- `GEMINI_API_KEY` (for live model calls)

## Run

```bash
export GEMINI_API_KEY=your-key
mvn -q exec:java
```

The demo asks the agent to transfer money, prints the pending confirmation, prompts you for `yes`/`no` on the command line, then resumes the turn with your decision. Without `GEMINI_API_KEY` set, the agent is defined but live calls are skipped.

## How it works

1. `send("Transfer $150 to Alice for dinner")` → the model calls `confirmTransfer`, which interrupts the turn.
2. `response.finishReason()` is `INTERRUPTED`; `response.interrupts().get(0)` is the pending tool request.
3. `confirmTransfer.respond(interrupt.part(), new ConfirmationOutput(approved, ...))` builds the response part.
4. `chat.resume(List.of(respond))` resumes the turn; the agent finishes with a normal (`STOP`) response.
