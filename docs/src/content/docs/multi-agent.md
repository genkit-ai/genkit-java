---
title: Multi-Agent
description: Build multi-agent AI orchestration patterns.
---

Multi-agent systems coordinate multiple AI agents, each with specialized capabilities, to solve complex tasks. In Genkit, agents are defined with their own system prompt, model, and tools. A triage agent routes user requests to the right specialist by calling that agent as a tool, triggering an automatic handoff.

## Defining agents

Use `genkit.defineAgent()` to create specialized agents. Each agent has its own system prompt, model, tools, and optional generation config:

```java
import com.google.genkit.ai.Agent;
import com.google.genkit.ai.AgentConfig;

// Define tools for the reservation agent
Tool<ReservationInput, String> makeReservationTool = genkit.defineTool(
    "makeReservation", "Makes a restaurant reservation",
    ReservationInput.class, String.class,
    (ctx, input) -> "Reserved table for " + input.getPartySize()
        + " at " + input.getTime());

Tool<String, String> cancelReservationTool = genkit.defineTool(
    "cancelReservation", "Cancels a reservation",
    String.class, String.class,
    (ctx, id) -> "Reservation " + id + " cancelled");

// Create a specialized agent
Agent reservationAgent = genkit.defineAgent(
    AgentConfig.builder()
        .name("reservationAgent")
        .description("Handles restaurant reservations. "
            + "Transfer here when the customer wants to book, "
            + "modify, or cancel a reservation.")
        .system("You are a reservation specialist. Help customers "
            + "book and manage restaurant reservations.")
        .model("openai/gpt-4o-mini")
        .tools(List.of(makeReservationTool, cancelReservationTool))
        .config(GenerationConfig.builder().temperature(0.3).build())
        .build());
```

## Creating the triage agent

The triage (orchestrator) agent has sub-agents listed in its config. Genkit automatically registers each sub-agent as a tool that the triage agent can call:

```java
Agent menuAgent = genkit.defineAgent(
    AgentConfig.builder()
        .name("menuAgent")
        .description("Provides menu information and recommendations.")
        .system("You are a menu expert. Help customers explore "
            + "the menu and make recommendations.")
        .model("openai/gpt-4o-mini")
        .tools(List.of(getMenuTool, searchMenuTool))
        .build());

// Triage agent routes to specialists
Agent triageAgent = genkit.defineAgent(
    AgentConfig.builder()
        .name("triageAgent")
        .description("Routes customer requests to the right specialist")
        .system("You are the main customer service agent. Route requests:\n"
            + "- reservationAgent: for booking/modifying/cancelling reservations\n"
            + "- menuAgent: for menu questions and recommendations\n"
            + "Transfer to the appropriate specialist using their tools.")
        .model("openai/gpt-4o-mini")
        .agents(List.of(
            reservationAgent.getConfig(),
            menuAgent.getConfig()))
        .build());
```

## Running a multi-agent chat

Use `genkit.getAllToolsForAgent()` to get the triage agent's own tools plus all sub-agent tools, then start a chat session:

```java
List<Tool<?, ?>> allTools = genkit.getAllToolsForAgent(triageAgent);

Session<Void> session = genkit.createSession();
Chat<Void> chat = session.chat(
    ChatOptions.<Void>builder()
        .model(triageAgent.getModel())
        .system(triageAgent.getSystem())
        .tools(allTools)
        .build());

// User asks about the menu → routes to menuAgent
ModelResponse r1 = chat.send("What appetizers do you have?");

// User wants to book → routes to reservationAgent
ModelResponse r2 = chat.send("Great, book a table for 4 at 7pm");
```

## How agent handoff works

When the triage agent decides to delegate, it calls a sub-agent's tool. This triggers an `AgentHandoffException` internally, which Genkit handles automatically:

1. The triage agent calls the sub-agent tool (e.g., `reservationAgent`)
2. Genkit catches the handoff and switches the chat's active system prompt, model, and tools to the target agent
3. Subsequent messages are handled by the new agent until another handoff occurs

You can check which agent is currently active:

```java
String currentAgent = chat.getCurrentAgentName();
System.out.println("Now talking to: " + currentAgent);
```

## Stateful multi-agent conversations

Combine agents with session state to track context across handoffs:

```java
Session<CustomerState> session = genkit.createSession(
    SessionOptions.<CustomerState>builder()
        .store(sessionStore)
        .initialState(new CustomerState("John"))
        .build());

Chat<CustomerState> chat = session.chat(
    ChatOptions.<CustomerState>builder()
        .model(triageAgent.getModel())
        .system(triageAgent.getSystem())
        .tools(allTools)
        .build());

// State persists across agent handoffs
CustomerState state = session.getState();
```

## Agent registry

All defined agents are registered in a global agent registry. You can look up agents by name:

```java
Agent agent = genkit.getAgent("reservationAgent");
Map<String, Agent> allAgents = genkit.getAgentRegistry();
```

## Patterns

### Orchestrator pattern
A central triage agent delegates to specialized agents based on the user's intent. Best for customer service, help desks, and general-purpose assistants.

### Pipeline pattern
Agents process data sequentially, each contributing to the result. Define each stage as an agent and chain them in your flow logic.

### Debate pattern
Multiple agents propose different solutions, then a judge agent selects the best one. Useful for creative tasks or complex decision-making.

## Sample

See the [multi-agent sample](https://github.com/xavidop/genkit-java/tree/main/samples/multi-agent) for a complete restaurant assistant with triage, reservation, and menu agents.
