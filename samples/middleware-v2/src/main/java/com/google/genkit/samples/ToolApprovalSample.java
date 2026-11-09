/*
 * Copyright 2026 Google LLC
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
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolFailException;
import com.google.genkit.plugins.middleware.Filesystem;
import com.google.genkit.plugins.middleware.SoftToolErrors;
import com.google.genkit.plugins.middleware.ToolApproval;
import com.google.genkit.plugins.middleware.ToolApproval.ToolCallDecision;
import com.google.genkit.plugins.openai.OpenAIPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shows the built-in tool middleware: {@link Filesystem} gives the model a scratch workspace,
 * {@link ToolApproval} holds the calls that change it until the user approves them, and {@link
 * SoftToolErrors} lets the model recover from a failing tool.
 *
 * <p>Run with {@code OPENAI_API_KEY} set:
 *
 * <pre>
 * mvn compile exec:java -Dexec.mainClass=com.google.genkit.samples.ToolApprovalSample
 * </pre>
 */
public class ToolApprovalSample {

  /** Input of the order lookup tool. */
  public static class OrderInput {
    public String id;
  }

  public static void main(String[] args) throws Exception {
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().devMode(false).build())
            .plugin(OpenAIPlugin.create())
            .build();

    Path workspace = Files.createTempDirectory("genkit-workspace");
    Files.writeString(workspace.resolve("notes.txt"), "Order 42 shipped on Monday.\n");

    Tool<OrderInput, String> lookupOrder =
        Tool.<OrderInput, String>builder()
            .name("lookupOrder")
            .description("Looks up an order's status by id.")
            .inputClass(OrderInput.class)
            .outputClass(String.class)
            .handler((ctx, in) -> {
              if (!"42".equals(in.id)) {
                // The model reads this and can try again.
                throw new ToolFailException("no order with id " + in.id + "; the only order is 42");
              }
              return "Order 42: delivered";
            })
            .build();

    Filesystem files =
        Filesystem.builder().rootDirectory(workspace.toString()).allowWriteAccess(true).build();
    // Reading is fine; writing and editing need the user's approval.
    ToolApproval approval =
        ToolApproval.builder().allowedTools("list_files", "read_file", "lookupOrder").build();

    List<Tool<?, ?>> tools = List.of(lookupOrder);
    GenerateOptions.Builder<Object> request =
        GenerateOptions.builder()
            .model("openai/gpt-4o-mini")
            .prompt(
                "Read notes.txt, look up order 7 and then order 42, and write a one-line status"
                    + " summary to summary.txt.")
            .tools(tools)
            .use(new SoftToolErrors(), files, approval)
            .maxTurns(10);

    ModelResponse response = genkit.generate(request.build());
    while (response.isInterrupted()) {
      ResumeOptions.Builder resume = ResumeOptions.builder();
      for (Part part : response.getInterrupts()) {
        ToolApproval.toolApprovalInterrupted(part)
            .ifPresent(call -> {
              System.out.println(
                  "Approving " + call.getPart().getToolRequest().getName() + " " + call.getInput());
              resume.part(call.restart(ToolCallDecision.approve()));
            });
      }
      response =
          genkit.generate(
              GenerateOptions.builder()
                  .model("openai/gpt-4o-mini")
                  .messages(response.getMessages())
                  .tools(tools)
                  .use(new SoftToolErrors(), files, approval)
                  .resume(resume.build())
                  .maxTurns(10)
                  .build());
    }

    System.out.println("Model: " + response.getText());
    Path summary = workspace.resolve("summary.txt");
    if (Files.exists(summary)) {
      System.out.println("summary.txt: " + Files.readString(summary));
    }
    System.out.println("Workspace: " + Map.of("dir", workspace.toString()));
  }
}
