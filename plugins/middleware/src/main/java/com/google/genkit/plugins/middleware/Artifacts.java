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

package com.google.genkit.plugins.middleware;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.agent.AgentSessionContext;
import com.google.genkit.ai.agent.Artifact;
import com.google.genkit.ai.agent.ArtifactStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Factory for artifact tools that let a model read and write named artifacts on the active agent
 * session.
 *
 * <p>Both tools operate on {@link AgentSessionContext#currentArtifactStore()}. When no agent
 * session is bound to the current thread, the tools degrade gracefully: {@code read_artifact}
 * reports {@code found = false} and {@code write_artifact} reports a {@code "no active session"}
 * status without throwing.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * AgentConfig.builder()
 *     .name("writer")
 *     .tools(Artifacts.tools(ArtifactsOptions.defaults()))
 *     .build();
 * }</pre>
 */
public final class Artifacts {

  /** Name of the read tool. */
  public static final String READ_TOOL = "read_artifact";

  /** Name of the write tool. */
  public static final String WRITE_TOOL = "write_artifact";

  private Artifacts() {}

  /**
   * Builds the artifact tools using default options ({@code read_artifact} + {@code
   * write_artifact}).
   *
   * @return the artifact tools
   */
  public static List<Tool<?, ?>> tools() {
    return tools(ArtifactsOptions.defaults());
  }

  /**
   * Builds the artifact tools.
   *
   * <p>Always includes {@code read_artifact}. Includes {@code write_artifact} unless {@code
   * options.isReadonly()} is {@code true}.
   *
   * @param options the options (must not be null)
   * @return the artifact tools
   */
  public static List<Tool<?, ?>> tools(ArtifactsOptions options) {
    if (options == null) {
      throw new IllegalArgumentException("options must not be null");
    }
    List<Tool<?, ?>> result = new ArrayList<>();
    result.add(readTool());
    if (!options.isReadonly()) {
      result.add(writeTool());
    }
    return result;
  }

  // ── Tool builders ─────────────────────────────────────────────────────────────

  private static Tool<ReadInput, ReadOutput> readTool() {
    return Tool.<ReadInput, ReadOutput>builder()
        .name(READ_TOOL)
        .description(
            "Read a named artifact from the current session. Returns its text content and whether "
                + "it was found.")
        .inputClass(ReadInput.class)
        .outputClass(ReadOutput.class)
        .handler(
            (ctx, in) -> {
              String name = in != null ? in.name : null;
              ArtifactStore store = AgentSessionContext.currentArtifactStore();
              if (store == null || name == null) {
                return new ReadOutput(name, null, false);
              }
              for (Artifact a : store.getArtifacts()) {
                if (name.equals(a.getName())) {
                  return new ReadOutput(name, textOf(a), true);
                }
              }
              return new ReadOutput(name, null, false);
            })
        .build();
  }

  private static Tool<WriteInput, WriteOutput> writeTool() {
    return Tool.<WriteInput, WriteOutput>builder()
        .name(WRITE_TOOL)
        .description(
            "Write (create or replace) a named artifact in the current session with the given text "
                + "content.")
        .inputClass(WriteInput.class)
        .outputClass(WriteOutput.class)
        .handler(
            (ctx, in) -> {
              String name = in != null ? in.name : null;
              String content = in != null ? in.content : null;
              ArtifactStore store = AgentSessionContext.currentArtifactStore();
              if (store == null) {
                return new WriteOutput("no active session");
              }
              if (name == null || name.isEmpty()) {
                return new WriteOutput("error: artifact name is required");
              }
              Artifact artifact =
                  Artifact.builder()
                      .name(name)
                      .parts(Collections.singletonList(Part.text(content != null ? content : "")))
                      .build();
              // addArtifacts deduplicates by name, replacing any existing artifact with this name.
              store.addArtifacts(artifact);
              return new WriteOutput("ok");
            })
        .build();
  }

  /** Concatenates the text of all text parts of an artifact. */
  static String textOf(Artifact artifact) {
    StringBuilder sb = new StringBuilder();
    if (artifact.getParts() != null) {
      for (Part p : artifact.getParts()) {
        if (p.getText() != null) {
          sb.append(p.getText());
        }
      }
    }
    return sb.toString();
  }

  // ── Tool I/O types ──────────────────────────────────────────────────────────────

  /** Input for {@code read_artifact}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ReadInput {
    @JsonProperty("name")
    public String name;

    /** Default constructor for JSON deserialization. */
    public ReadInput() {}
  }

  /** Output for {@code read_artifact}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class ReadOutput {
    @JsonProperty("name")
    public String name;

    @JsonProperty("content")
    public String content;

    @JsonProperty("found")
    public boolean found;

    /** Default constructor for JSON deserialization. */
    public ReadOutput() {}

    ReadOutput(String name, String content, boolean found) {
      this.name = name;
      this.content = content;
      this.found = found;
    }
  }

  /** Input for {@code write_artifact}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class WriteInput {
    @JsonProperty("name")
    public String name;

    @JsonProperty("content")
    public String content;

    /** Default constructor for JSON deserialization. */
    public WriteInput() {}
  }

  /** Output for {@code write_artifact}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class WriteOutput {
    @JsonProperty("status")
    public String status;

    /** Default constructor for JSON deserialization. */
    public WriteOutput() {}

    WriteOutput(String status) {
      this.status = status;
    }
  }
}
