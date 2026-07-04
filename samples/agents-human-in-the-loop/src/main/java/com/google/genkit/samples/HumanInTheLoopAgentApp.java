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
import com.google.genkit.agent.AgentConfig;
import com.google.genkit.ai.InterruptConfig;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentChat;
import com.google.genkit.ai.agent.AgentFinishReason;
import com.google.genkit.ai.agent.AgentInterrupt;
import com.google.genkit.ai.agent.AgentResponse;
import com.google.genkit.ai.agent.FileSessionStore;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Human-in-the-loop agent CLI demo — a banking assistant that pauses on a sensitive action (a money
 * transfer) and waits for the user to approve or reject it on the command line before continuing.
 *
 * <p>This demonstrates the agent-level interrupt/resume flow:
 *
 * <ul>
 *   <li>A tool created with {@code genkit.defineInterrupt(...)} pauses the turn instead of running.
 *   <li>The turn finishes with {@link AgentFinishReason#INTERRUPTED} and {@link
 *       AgentResponse#interrupts()} surfaces the pending tool request.
 *   <li>The caller resolves it with {@code tool.respond(interrupt.part(), output)} and resumes via
 *       {@link AgentChat#resume(java.util.List)}; the agent then completes the turn.
 * </ul>
 *
 * <p>State is persisted server-side via {@link FileSessionStore} (under {@code ./.snapshots}), so
 * the paused turn survives across the resume.
 *
 * <p>Run it (requires {@code GEMINI_API_KEY}):
 *
 * <pre>
 *   export GEMINI_API_KEY=your-key
 *   mvn -q exec:java
 * </pre>
 */
public class HumanInTheLoopAgentApp {

  /** Input for the money-transfer confirmation interrupt. */
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

  /** The caller's decision returned to the interrupted tool. */
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

  // Scanner wraps System.in for the CLI prompt; we intentionally do not close it (closing
  // System.in would break stdin for the rest of the JVM) — the process exits right after.
  @SuppressWarnings("resource")
  public static void main(String[] args) throws Exception {
    // ── 1. Build Genkit with the beta agents API enabled ────────────────────
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().experimental(true).build())
            .plugin(GoogleGenAIPlugin.create())
            .build();

    // ── 2. Define an interrupt tool ─────────────────────────────────────────
    //
    // defineInterrupt creates a tool that PAUSES the turn (throwing the proper
    // interrupt internally) instead of executing. The model calls it like any
    // tool; the turn then finishes INTERRUPTED so a human can decide.
    Tool<TransferRequest, ConfirmationOutput> confirmTransfer =
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
                        List.of("recipient", "amount")))
                .requestMetadata(
                    input ->
                        Map.of(
                            "type",
                            "transfer_confirmation",
                            "recipient",
                            input.getRecipient() != null ? input.getRecipient() : "",
                            "amount",
                            input.getAmount(),
                            "reason",
                            input.getReason() != null ? input.getReason() : ""))
                .build());

    // ── 3. Define a server-managed agent that uses the interrupt tool ───────
    Agent<Map<String, Object>> bankingAgent =
        genkit
            .beta()
            .defineAgent(
                AgentConfig.<Map<String, Object>>builder()
                    .name("bankingAgent")
                    .description("A banking assistant that confirms transfers with the user")
                    .system(
                        "You are a helpful banking assistant. Whenever the user asks to transfer"
                            + " or send money, you MUST call the confirmTransfer tool first and"
                            + " only proceed once it returns a confirmation.")
                    .tools(confirmTransfer)
                    .model("googleai/gemini-2.5-flash")
                    .store(new FileSessionStore<>("./.snapshots"))
                    .build());

    // ── 4. Run the interrupt → human decision → resume flow (needs GEMINI_API_KEY) ──
    String apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.out.println(
          "GEMINI_API_KEY is not set — agent defined successfully but skipping live calls.");
      System.out.println("Set GEMINI_API_KEY and re-run to see the full human-in-the-loop flow.");
      return;
    }

    System.out.println("=== Human-in-the-loop banking agent ===");
    AgentChat<Map<String, Object>> chat = bankingAgent.chat();

    AgentResponse<Map<String, Object>> turn1 = chat.send("Transfer $150 to Alice for dinner");
    System.out.println("Finish reason: " + turn1.finishReason());

    if (turn1.finishReason() == AgentFinishReason.INTERRUPTED && !turn1.interrupts().isEmpty()) {
      AgentInterrupt interrupt = turn1.interrupts().get(0);
      System.out.println("Agent paused awaiting approval for tool: " + interrupt.name());
      System.out.println("  Requested transfer: " + interrupt.input());

      System.out.print("Approve this transfer? (yes/no): ");
      Scanner scanner = new Scanner(System.in);
      String answer = scanner.hasNextLine() ? scanner.nextLine().trim().toLowerCase() : "no";
      boolean approved = answer.equals("yes") || answer.equals("y");

      // Build the response part for the interrupted tool and resume the turn.
      Part respond =
          confirmTransfer.respond(
              interrupt.part(),
              new ConfirmationOutput(approved, approved ? "User approved" : "User declined"));
      AgentResponse<Map<String, Object>> resumed = chat.resume(List.of(respond));

      System.out.println("Resumed finish reason: " + resumed.finishReason());
      System.out.println("Final response: " + resumed.text());
    } else {
      System.out.println("Agent did not interrupt. Response: " + turn1.text());
    }
  }
}
