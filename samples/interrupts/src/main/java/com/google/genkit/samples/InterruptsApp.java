/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.genkit.samples;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.InterruptConfig;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Tool;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import com.google.genkit.prompt.ExecutablePrompt;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Human-in-the-Loop Application using Interrupts.
 *
 * <p>This sample demonstrates the interrupt pattern for human-in-the-loop scenarios using the
 * {@code generate()} and {@link ExecutablePrompt} APIs:
 *
 * <ul>
 *   <li>Tools that pause execution to request user confirmation
 *   <li>Handling interrupt requests and resuming with user input
 *   <li>Sensitive operations that require explicit approval
 * </ul>
 *
 * <p>To run:
 *
 * <ol>
 *   <li>Set the OPENAI_API_KEY environment variable
 *   <li>Run: mvn exec:java -pl samples/interrupts
 * </ol>
 */
public class InterruptsApp {

  /** Transfer request for the interrupt tool input. */
  public static class TransferRequest {
    private String recipient;
    private double amount;
    private String reason;

    public TransferRequest() {}

    public String getRecipient() {
      return recipient;
    }

    public void setRecipient(String recipient) {
      this.recipient = recipient;
    }

    public double getAmount() {
      return amount;
    }

    public void setAmount(double amount) {
      this.amount = amount;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }

  /** Confirmation output structure. */
  public static class ConfirmationOutput {
    private boolean confirmed;
    private String reason;

    public ConfirmationOutput() {}

    public ConfirmationOutput(boolean confirmed, String reason) {
      this.confirmed = confirmed;
      this.reason = reason;
    }

    public boolean isConfirmed() {
      return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
      this.confirmed = confirmed;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }

  /** Banking request input for the prompt. */
  public static class BankingInput {
    private String request;

    public BankingInput() {}

    public BankingInput(String request) {
      this.request = request;
    }

    public String getRequest() {
      return request;
    }

    public void setRequest(String request) {
      this.request = request;
    }
  }

  private final Genkit genkit;
  private final Scanner scanner;

  // Tools
  private Tool<?, ?> confirmTransferTool;

  public InterruptsApp() {
    this.genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().devMode(true).reflectionPort(3102).build())
            .plugin(OpenAIPlugin.create())
            .build();

    this.scanner = new Scanner(System.in);

    initializeTools();
  }

  private void initializeTools() {
    // Use defineInterrupt to create an interrupt tool that pauses for confirmation.
    // This is the preferred way to create interrupt tools - it automatically
    // handles throwing ToolInterruptException with the proper metadata.
    confirmTransferTool =
        genkit.defineInterrupt(
            InterruptConfig.<TransferRequest, ConfirmationOutput>builder()
                .name("confirmTransfer")
                .description(
                    "Request user confirmation before executing a money transfer. "
                        + "ALWAYS use this tool before transferring money.")
                .inputType(TransferRequest.class)
                .outputType(ConfirmationOutput.class)
                .inputSchema(
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                            "recipient",
                            Map.of("type", "string", "description", "Who to transfer to"),
                            "amount",
                            Map.of("type", "number", "description", "Amount to transfer"),
                            "reason",
                            Map.of("type", "string", "description", "Reason for transfer")),
                        "required",
                        new String[] {"recipient", "amount"}))
                // requestMetadata extracts info from input for the interrupt request
                .requestMetadata(
                    input ->
                        Map.of(
                            "type",
                            "transfer_confirmation",
                            "recipient",
                            input.getRecipient(),
                            "amount",
                            input.getAmount(),
                            "reason",
                            input.getReason() != null ? input.getReason() : ""))
                .build());
  }

  /**
   * Demo using generate() directly with interrupts.
   *
   * <p>This shows how to use interrupts at the lower level generate() API, which is useful when you
   * don't need session management.
   */
  public void runGenerateDemo() {
    System.out.println("╔════════════════════════════════════════════════════════════════╗");
    System.out.println("║    Interrupts with generate() - Low-Level API Demo             ║");
    System.out.println("╚════════════════════════════════════════════════════════════════╝");
    System.out.println();
    System.out.println("This demo shows how to use interrupts with the generate() method.");
    System.out.println("This is useful when you don't need Chat's session management.\n");

    String model = "openai/gpt-4o-mini";

    // Create a simple confirm transfer interrupt tool
    @SuppressWarnings("unchecked")
    Tool<TransferRequest, ConfirmationOutput> confirmTool =
        (Tool<TransferRequest, ConfirmationOutput>) confirmTransferTool;

    // Initial request - transfer money
    System.out.println("=== Step 1: Initial Generate Request ===\n");
    System.out.println("Prompt: Transfer $150 to Alice for dinner\n");

    ModelResponse response =
        genkit.generate(
            GenerateOptions.builder()
                .model(model)
                .prompt("Transfer $150 to Alice for dinner")
                .system(
                    "You are a banking assistant. Use the confirmTransfer tool for any transfers.")
                .tools(List.of(confirmTransferTool))
                .build());

    System.out.println("Response finish reason: " + response.getFinishReason());

    // Check if we got an interrupt
    if (response.isInterrupted()) {
      System.out.println("✓ Generation was interrupted!");
      System.out.println("  Number of interrupts: " + response.getInterrupts().size());

      Part interrupt = response.getInterrupts().get(0);
      Map<String, Object> metadata = interrupt.getMetadata();
      System.out.println("  Interrupt metadata: " + metadata);

      // Get user confirmation
      System.out.println("\n=== Step 2: Get User Confirmation ===\n");
      System.out.print("Confirm transfer of $150 to Alice? (yes/no): ");
      String userInput = scanner.nextLine().trim().toLowerCase();
      boolean confirmed = userInput.equals("yes") || userInput.equals("y");

      // Create the response to the interrupt
      ConfirmationOutput userResponse =
          new ConfirmationOutput(confirmed, confirmed ? "User approved" : "User declined");

      // Use the tool's respond helper
      Part responseData = confirmTool.respond(interrupt, userResponse);

      System.out.println("\n=== Step 3: Resume Generation ===\n");
      System.out.println(
          "Resuming with user " + (confirmed ? "confirmation" : "rejection") + "...\n");

      // Resume generation with the user's response
      ModelResponse resumedResponse =
          genkit.generate(
              GenerateOptions.builder()
                  .model(model)
                  .messages(response.getMessages()) // Include previous context
                  .tools(List.of(confirmTransferTool))
                  .resume(ResumeOptions.builder().respond(responseData.getToolResponse()).build())
                  .build());

      System.out.println("Final response: " + resumedResponse.getText());
      System.out.println("Finish reason: " + resumedResponse.getFinishReason());
    } else {
      System.out.println("Response (no interrupt): " + response.getText());
    }

    System.out.println("\n=== Generate Demo Complete ===");
  }

  /**
   * Demo using ExecutablePrompt with interrupts.
   *
   * <p>This shows how to use interrupts with the prompt() API, which allows you to load and execute
   * .prompt files with tool and interrupt support.
   */
  public void runPromptDemo() {
    System.out.println("╔════════════════════════════════════════════════════════════════╗");
    System.out.println("║    Interrupts with ExecutablePrompt - Prompt API Demo          ║");
    System.out.println("╚════════════════════════════════════════════════════════════════╝");
    System.out.println();
    System.out.println("This demo shows how to use interrupts with ExecutablePrompt.");
    System.out.println("It loads a .prompt file and adds tools with interrupt support.\n");

    // Load the prompt
    ExecutablePrompt<BankingInput> bankingPrompt =
        genkit.prompt("banking-assistant", BankingInput.class);

    // Create a simple confirm transfer interrupt tool
    @SuppressWarnings("unchecked")
    Tool<TransferRequest, ConfirmationOutput> confirmTool =
        (Tool<TransferRequest, ConfirmationOutput>) confirmTransferTool;

    // Initial request - transfer money
    System.out.println("=== Step 1: Execute Prompt with Tools ===\n");
    System.out.println("Using prompt: banking-assistant.prompt");
    System.out.println("Input: Transfer $200 to Bob for concert tickets\n");

    BankingInput input = new BankingInput("Transfer $200 to Bob for concert tickets");

    // Generate with tools - the prompt will use Genkit.generate() internally
    // which supports interrupts
    ModelResponse response =
        bankingPrompt.generate(
            input, GenerateOptions.builder().tools(List.of(confirmTransferTool)).build());

    System.out.println("Response finish reason: " + response.getFinishReason());

    // Check if we got an interrupt
    if (response.isInterrupted()) {
      System.out.println("✓ Prompt execution was interrupted!");
      System.out.println("  Number of interrupts: " + response.getInterrupts().size());

      Part interrupt = response.getInterrupts().get(0);
      Map<String, Object> metadata = interrupt.getMetadata();
      System.out.println("  Interrupt metadata: " + metadata);

      // Get user confirmation
      System.out.println("\n=== Step 2: Get User Confirmation ===\n");
      System.out.print("Confirm transfer of $200 to Bob? (yes/no): ");
      String userInput = scanner.nextLine().trim().toLowerCase();
      boolean confirmed = userInput.equals("yes") || userInput.equals("y");

      // Create the response to the interrupt
      ConfirmationOutput userResponse =
          new ConfirmationOutput(confirmed, confirmed ? "User approved" : "User declined");

      // Use the tool's respond helper
      Part responseData = confirmTool.respond(interrupt, userResponse);

      System.out.println("\n=== Step 3: Resume Prompt Execution ===\n");
      System.out.println(
          "Resuming with user " + (confirmed ? "confirmation" : "rejection") + "...\n");

      // Resume generation with the user's response
      // Note: For full resume, you would use genkit.generate() with the messages
      ModelResponse resumedResponse =
          genkit.generate(
              GenerateOptions.builder()
                  .model(bankingPrompt.getModel())
                  .messages(response.getMessages())
                  .tools(List.of(confirmTransferTool))
                  .resume(ResumeOptions.builder().respond(responseData.getToolResponse()).build())
                  .build());

      System.out.println("Final response: " + resumedResponse.getText());
      System.out.println("Finish reason: " + resumedResponse.getFinishReason());
    } else {
      System.out.println("Response (no interrupt): " + response.getText());
    }

    System.out.println("\n=== Prompt Demo Complete ===");
  }

  public static void main(String[] args) {
    InterruptsApp app = new InterruptsApp();

    boolean promptDemo = args.length > 0 && args[0].equals("--prompt");

    if (promptDemo) {
      app.runPromptDemo();
    } else {
      app.runGenerateDemo();
    }
  }
}
