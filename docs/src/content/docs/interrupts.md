---
title: Interrupts
description: Pause AI generation for human-in-the-loop interactions.
---

Interrupts allow you to pause execution when an AI model calls a tool, request input from a human or external system, and then resume processing with the response. This enables human-in-the-loop patterns like approval workflows, confirmation dialogs, and data collection.

## Defining an interrupt tool

Use `genkit.defineInterrupt()` to create a tool that always pauses execution when called by the model. You specify the input/output types and an optional metadata extractor:

```java
import com.google.genkit.ai.InterruptConfig;

Tool<TransferRequest, ConfirmationOutput> confirmTransferTool =
    genkit.defineInterrupt(
        InterruptConfig.<TransferRequest, ConfirmationOutput>builder()
            .name("confirmTransfer")
            .description("Request user confirmation before executing a money transfer.")
            .inputType(TransferRequest.class)
            .outputType(ConfirmationOutput.class)
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "recipient", Map.of("type", "string"),
                    "amount", Map.of("type", "number"),
                    "reason", Map.of("type", "string")),
                "required", new String[]{"recipient", "amount"}))
            .requestMetadata(input -> Map.of(
                "type", "transfer_confirmation",
                "recipient", input.getRecipient(),
                "amount", input.getAmount()))
            .build());
```

When the model decides to call this tool, a `ToolInterruptException` is thrown internally — you don't need to handle this yourself. Genkit captures the interrupt and makes it available for you to respond to.

## Using interrupts with Chat

The most common way to use interrupts is through `Chat` sessions:

### 1. Send a message and check for interrupts

```java
Chat<Void> chat = session.chat(
    ChatOptions.<Void>builder()
        .model("openai/gpt-4o-mini")
        .system("You are a banking assistant. Use confirmTransfer for transfers.")
        .tools(List.of(confirmTransferTool))
        .build());

ModelResponse response = chat.send("Transfer $250 to John for dinner");

if (chat.hasPendingInterrupts()) {
    List<InterruptRequest> interrupts = chat.getPendingInterrupts();
    // Present to user for confirmation...
}
```

### 2. Inspect the interrupt

Each `InterruptRequest` contains the original `ToolRequest` (what the model wanted to do) and any metadata you configured:

```java
InterruptRequest interrupt = interrupts.get(0);
ToolRequest toolRequest = interrupt.getToolRequest();
Map<String, Object> metadata = interrupt.getMetadata();

System.out.println("Tool: " + toolRequest.getName());
System.out.println("Recipient: " + metadata.get("recipient"));
System.out.println("Amount: " + metadata.get("amount"));
```

### 3. Resume with a response

After getting user input, create a response and resume the conversation:

```java
// User approved the transfer
ConfirmationOutput userResponse = new ConfirmationOutput(true, "User approved");
ToolResponse toolResponse = interrupt.respond(userResponse);

ModelResponse resumed = chat.send(
    "User confirmed the transfer.",
    Chat.SendOptions.builder()
        .resumeOptions(ResumeOptions.builder()
            .respond(List.of(toolResponse))
            .build())
        .build());
```

### 4. Or restart with different input

If the user wants to modify the request, you can restart the tool call with updated input:

```java
ToolRequest restartRequest = interrupt.restart(
    Map.of("modified", true),          // updated metadata
    new TransferRequest("John", 200)   // new input (changed amount)
);

ModelResponse resumed = chat.send(
    "User changed the amount to $200.",
    Chat.SendOptions.builder()
        .resumeOptions(ResumeOptions.builder()
            .restart(List.of(restartRequest))
            .build())
        .build());
```

## Using interrupts with generate()

You can also use interrupts directly with `genkit.generate()` without a Chat session:

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("openai/gpt-4o-mini")
        .prompt("Transfer $150 to Alice")
        .tools(List.of(confirmTransferTool))
        .build());

if (response.isInterrupted()) {
    Part interruptPart = response.getInterrupts().get(0);

    // Respond using the tool helper
    Part responsePart = confirmTransferTool.respond(
        interruptPart,
        new ConfirmationOutput(true, "Approved"),
        Map.of());

    // Resume generation with the previous messages
    ModelResponse resumed = genkit.generate(
        GenerateOptions.builder()
            .model("openai/gpt-4o-mini")
            .messages(response.getMessages())
            .tools(List.of(confirmTransferTool))
            .resume(ResumeOptions.builder()
                .respond(responsePart.getToolResponse())
                .build())
            .build());
}
```

## Multiple interrupts

A single model turn can trigger multiple interrupt tools. Handle them all before resuming:

```java
if (chat.hasPendingInterrupts()) {
    List<InterruptRequest> interrupts = chat.getPendingInterrupts();
    List<ToolResponse> responses = new ArrayList<>();

    for (InterruptRequest interrupt : interrupts) {
        // Present each to user and collect responses
        ToolResponse response = interrupt.respond(getUserInput(interrupt));
        responses.add(response);
    }

    ModelResponse resumed = chat.send("User responded to all prompts.",
        Chat.SendOptions.builder()
            .resumeOptions(ResumeOptions.builder()
                .respond(responses)
                .build())
            .build());
}
```

## InterruptConfig options

| Option | Description |
|--------|-------------|
| `name` | Tool name the model will call |
| `description` | Description to help the model decide when to use the tool |
| `inputType` | Java class for the tool's input |
| `outputType` | Java class for the expected response |
| `inputSchema` | JSON Schema for the input (helps the model) |
| `outputSchema` | JSON Schema for the output |
| `requestMetadata` | Function to extract metadata from the input for display |

## Flow

1. You define interrupt tools and provide them to the model
2. The model calls an interrupt tool → execution pauses
3. You inspect `InterruptRequest` (what the model wanted to do)
4. You collect user input and create either a `respond()` or `restart()`
5. You resume the conversation with `ResumeOptions`
6. The model continues with the tool's response

## Sample

See the [interrupts sample](https://github.com/xavidop/genkit-java/tree/main/samples/interrupts) for a complete banking transfer confirmation example.
