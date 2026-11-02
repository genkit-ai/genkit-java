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

package com.google.genkit.plugins.middleware;

import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.errorMessage;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.isToolError;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.registerModel;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.reply;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.request;
import static com.google.genkit.plugins.middleware.MiddlewareTestSupport.toolRequests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.Genkit;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddlewareDesc;
import com.google.genkit.core.DefaultRegistry;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemTest {

  /**
   * Creates a fixture tree: hello.txt ("hello world"), docs/README.md ("# Docs"), docs/nested/x.txt
   * ("nested").
   */
  private static Path makeFs(Path root) throws Exception {
    write(root, "hello.txt", "hello world");
    write(root, "docs/README.md", "# Docs");
    write(root, "docs/nested/x.txt", "nested");
    return root;
  }

  private static void write(Path root, String rel, String body) throws Exception {
    Path p = root.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, body);
  }

  /** One scripted turn: tool requests, or a final text when there are none. */
  private static final class Turn {
    final List<ToolRequest> tools;
    final String text;

    private Turn(List<ToolRequest> tools, String text) {
      this.tools = tools;
      this.text = text;
    }

    static Turn tools(ToolRequest... requests) {
      return new Turn(List.of(requests), null);
    }

    static Turn text(String text) {
      return new Turn(List.of(), text);
    }
  }

  /** Registers a model that walks through the script and records every request it receives. */
  private static List<ModelRequest> scriptedModel(Genkit genkit, String name, Turn... script) {
    List<ModelRequest> seen = new ArrayList<>();
    int[] idx = {0};
    registerModel(
        genkit,
        name,
        (req, cb) -> {
          seen.add(req);
          if (idx[0] >= script.length) {
            return reply(req, Message.model("unexpected extra turn"));
          }
          Turn turn = script[idx[0]++];
          if (!turn.tools.isEmpty()) {
            return reply(req, toolRequests(turn.tools.toArray(new ToolRequest[0])));
          }
          return reply(req, Message.model(turn.text));
        });
    return seen;
  }

  private static ModelResponse run(Genkit genkit, String model, GenerationMiddleware fs) {
    return genkit.generate(GenerateOptions.builder().model(model).prompt("go").use(fs).build());
  }

  private static Filesystem fs(Path root) {
    return Filesystem.builder().rootDirectory(root.toString()).build();
  }

  private static Filesystem writableFs(Path root) {
    return Filesystem.builder().rootDirectory(root.toString()).allowWriteAccess(true).build();
  }

  /** Returns the list_files entries in the history as JSON maps. */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> listEntries(List<Message> history) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Part p : MiddlewareTestSupport.allParts(history)) {
      if (p.getToolResponse() != null && "list_files".equals(p.getToolResponse().getName())) {
        Object json =
            JsonUtils.getObjectMapper().convertValue(p.getToolResponse().getOutput(), List.class);
        out.addAll((List<Map<String, Object>>) json);
      }
    }
    return out;
  }

  /**
   * Returns the error answering the named tool's call in msgs, or null; fails when a user message
   * repeats a tool failure: the error belongs on the call, not in speech the user never wrote.
   */
  private static String toolErrorIn(List<Message> msgs, String tool) {
    for (Message msg : msgs) {
      for (Part p : msg.getContent()) {
        if (msg.getRole() == Role.USER && p.getText() != null && p.getText().contains("failed")) {
          return null;
        }
        if (isToolError(p) && tool.equals(p.getToolResponse().getName())) {
          return errorMessage(p);
        }
      }
    }
    return null;
  }

  private static boolean anyText(List<Message> msgs, Role role, String... needles) {
    for (Message m : msgs) {
      if (role != null && m.getRole() != role) {
        continue;
      }
      for (Part p : m.getContent()) {
        if (p.getText() == null) {
          continue;
        }
        boolean all = true;
        for (String n : needles) {
          all &= p.getText().contains(n);
        }
        if (all) {
          return true;
        }
      }
    }
    return false;
  }

  @Test
  void filesystemListFilesNonRecursive(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit,
        "test/fs-list",
        Turn.tools(request("list_files", Map.of("dirPath", ""))),
        Turn.text("done"));

    ModelResponse resp = run(genkit, "test/fs-list", fs(root));
    assertEquals("done", resp.getText());
    List<Map<String, Object>> got = listEntries(resp.getMessages());
    got.sort((a, b) -> ((String) a.get("path")).compareTo((String) b.get("path")));
    assertEquals(2, got.size(), got.toString());
    assertEquals("docs", got.get(0).get("path"));
    assertEquals(true, got.get(0).get("isDirectory"));
    assertEquals("hello.txt", got.get(1).get("path"));
    assertEquals(false, got.get(1).get("isDirectory"));
  }

  @Test
  void filesystemListFilesRecursive(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit,
        "test/fs-list-r",
        Turn.tools(request("list_files", Map.of("dirPath", "docs", "recursive", true))),
        Turn.text("done"));

    ModelResponse resp = run(genkit, "test/fs-list-r", fs(root));
    Set<Object> paths = new HashSet<>();
    for (Map<String, Object> e : listEntries(resp.getMessages())) {
      paths.add(e.get("path"));
    }
    for (String want : List.of("README.md", "nested", "nested/x.txt")) {
      assertTrue(paths.contains(want), "recursive list missing " + want + "; got " + paths);
    }
  }

  @Test
  void filesystemReadFileInjectsUserMessage(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-read",
            Turn.tools(request("read_file", Map.of("filePath", "hello.txt"))),
            Turn.text("done"));

    run(genkit, "test/fs-read", fs(root));
    assertTrue(seen.size() >= 2);
    assertTrue(
        anyText(seen.get(1).getMessages(), Role.USER, "hello world", "path=\"hello.txt\""),
        "file content not injected as a user message on turn 2");
  }

  @Test
  void filesystemReadFileInjectsImageAsMedia(@TempDir Path root) throws Exception {
    byte[] img = {
      (byte) 0x89,
      0x50,
      0x4e,
      0x47,
      0x0d,
      0x0a,
      0x1a,
      0x0a,
      0x00,
      0x00,
      0x00,
      0x0d,
      0x49,
      0x48,
      0x44,
      0x52,
      0x00,
      0x00,
      0x00,
      0x01,
      0x00,
      0x00,
      0x00,
      0x01,
      0x08,
      0x06,
      0x00,
      0x00,
      0x00,
      0x1f,
      0x15,
      (byte) 0xc4,
      (byte) 0x89,
      0x00,
      0x00,
      0x00,
      0x0a,
      0x49,
      0x44,
      0x41,
      0x54,
      0x78,
      (byte) 0x9c,
      0x63,
      0x00,
      0x01,
      0x00,
      0x00,
      0x05,
      0x00,
      0x01,
      0x0d,
      0x0a,
      0x2d,
      (byte) 0xb4,
      0x00,
      0x00,
      0x00,
      0x00,
      0x49,
      0x45,
      0x4e,
      0x44,
      (byte) 0xae,
      0x42,
      0x60,
      (byte) 0x82,
    };
    Files.write(root.resolve("pixel.png"), img);
    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-img",
            Turn.tools(request("read_file", Map.of("filePath", "pixel.png"))),
            Turn.text("done"));

    run(genkit, "test/fs-img", fs(root));
    assertTrue(seen.size() >= 2);
    boolean foundMedia = false;
    for (Message m : seen.get(1).getMessages()) {
      if (m.getRole() != Role.USER) {
        continue;
      }
      for (Part p : m.getContent()) {
        if (p.getMedia() != null
            && p.getMedia().getContentType() != null
            && p.getMedia().getContentType().startsWith("image/png")) {
          foundMedia = true;
        }
      }
    }
    assertTrue(foundMedia, "expected an image media part in the injected user message");
  }

  @Test
  void filesystemStreamsInjectedFileContents(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    int[] turn = {0};
    registerModel(
        genkit,
        "test/fs-stream",
        (req, cb) -> {
          turn[0]++;
          List<Part> content =
              turn[0] == 1
                  ? List.of(Part.toolRequest(request("read_file", Map.of("filePath", "hello.txt"))))
                  : List.of(Part.text("done"));
          if (cb != null) {
            cb.accept(new ModelResponseChunk(content));
          }
          return reply(req, new Message(Role.MODEL, content));
        });

    List<ModelResponseChunk> chunks = new ArrayList<>();
    Consumer<ModelResponseChunk> collect = chunks::add;
    genkit.generateStream(
        GenerateOptions.builder().model("test/fs-stream").prompt("go").use(fs(root)).build(),
        collect);

    // Four distinct indices in order: model (0), tool (1), injected user (2), model (3).
    LinkedHashSet<Integer> indices = new LinkedHashSet<>();
    for (ModelResponseChunk c : chunks) {
      indices.add(c.getIndex());
    }
    assertEquals(List.of(0, 1, 2, 3), new ArrayList<>(indices));

    ModelResponseChunk injected =
        chunks.stream().filter(c -> c.getIndex() == 2).findFirst().orElse(null);
    assertNotNull(injected, "no chunk at index 2 for injected file content");
    assertEquals(Role.USER, injected.getRole());
    assertTrue(
        injected.getContent().stream()
            .anyMatch(p -> p.getText() != null && p.getText().contains("hello world")));
  }

  @Test
  void filesystemWriteFileRequiresAllowWriteAccess(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Set<String> names = new HashSet<>();
    for (Tool<?, ?> t : fs(root).tools()) {
      names.add(t.getName());
    }
    assertTrue(names.contains("list_files") && names.contains("read_file"), names.toString());
    assertFalse(names.contains("write_file") || names.contains("edit_file"), names.toString());
  }

  @Test
  void filesystemWriteFileCreatesAndOverwrites(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit,
        "test/fs-write",
        Turn.tools(
            request("write_file", Map.of("filePath", "sub/new.txt", "content", "fresh content"))),
        Turn.text("done"));

    run(genkit, "test/fs-write", writableFs(root));
    assertEquals("fresh content", Files.readString(root.resolve("sub/new.txt")));
  }

  private static ToolRequest edit(String path, String oldString, String newString) {
    return request(
        "edit_file",
        Map.of(
            "filePath",
            path,
            "edits",
            List.of(Map.of("oldString", oldString, "newString", newString))));
  }

  @Test
  void filesystemEditFile(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit,
        "test/fs-edit",
        Turn.tools(request("read_file", Map.of("filePath", "hello.txt"))),
        Turn.tools(edit("hello.txt", "hello world", "hello there")),
        Turn.text("done"));

    run(genkit, "test/fs-edit", writableFs(root));
    assertEquals("hello there", Files.readString(root.resolve("hello.txt")));
  }

  @Test
  void filesystemEditFileNotFound(@TempDir Path tmp) throws Exception {
    // A failed edit answers the call with its error rather than crashing the generation.
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-edit-miss",
            Turn.tools(request("read_file", Map.of("filePath", "hello.txt"))),
            Turn.tools(edit("hello.txt", "NOT THERE", "REPLACED")),
            Turn.text("recovered"));

    ModelResponse resp = run(genkit, "test/fs-edit-miss", writableFs(root));
    assertEquals("recovered", resp.getText());
    assertEquals("hello world", Files.readString(root.resolve("hello.txt")));
    assertTrue(seen.size() >= 3);
    String msg = toolErrorIn(seen.get(2).getMessages(), "edit_file");
    assertNotNull(msg, "expected the edit_file call to be answered with its error");
    assertTrue(
        msg.startsWith("edit 0: oldString not found") && !msg.contains("Tool execution failed"),
        msg);
  }

  @Test
  void filesystemRejectsTraversal(@TempDir Path tmp) throws Exception {
    Path root = Files.createDirectories(tmp.resolve("root"));
    makeFs(root);
    Path sibling = Files.createDirectories(tmp.resolve("sibling-secret"));
    Files.writeString(sibling.resolve("secret.txt"), "nope");

    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-trav",
            Turn.tools(request("read_file", Map.of("filePath", "../sibling-secret/secret.txt"))),
            Turn.text("ok"));

    run(genkit, "test/fs-trav", fs(root));
    assertTrue(seen.size() >= 2);
    assertFalse(anyText(seen.get(1).getMessages(), null, "nope"), "sibling file leaked");
    assertNotNull(
        toolErrorIn(seen.get(1).getMessages(), "read_file"),
        "expected the traversal attempt to be answered with its error");
  }

  @Test
  void filesystemRejectsSymlinkEscape(@TempDir Path tmp) throws Exception {
    Path root = Files.createDirectories(tmp.resolve("root"));
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "nope");
    try {
      Files.createSymbolicLink(root.resolve("link"), outside);
    } catch (UnsupportedOperationException | java.io.IOException e) {
      return; // symbolic links unavailable on this platform
    }
    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-link",
            Turn.tools(request("read_file", Map.of("filePath", "link/secret.txt"))),
            Turn.text("ok"));
    run(genkit, "test/fs-link", fs(root));
    assertFalse(anyText(seen.get(1).getMessages(), null, "nope"), "linked file leaked");
    assertNotNull(toolErrorIn(seen.get(1).getMessages(), "read_file"));
  }

  @Test
  void filesystemToolNamePrefix(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Filesystem f =
        Filesystem.builder()
            .rootDirectory(root.toString())
            .allowWriteAccess(true)
            .toolNamePrefix("fs_")
            .build();
    Set<String> names = new HashSet<>();
    for (Tool<?, ?> t : f.tools()) {
      names.add(t.getName());
    }
    for (String want : List.of("fs_list_files", "fs_read_file", "fs_write_file", "fs_edit_file")) {
      assertTrue(names.contains(want), "expected prefixed tool " + want + "; got " + names);
    }
  }

  /**
   * A middleware built from JSON config, as the Dev UI dispatches it by name, uses that config and
   * not another one registered before it.
   */
  @Test
  void filesystemRespectsConfigOverride(@TempDir Path tmp) throws Exception {
    Path protoRoot = Files.createDirectories(tmp.resolve("proto"));
    Files.writeString(protoRoot.resolve("marker.txt"), "from-proto");
    Path overrideRoot = Files.createDirectories(tmp.resolve("override"));
    Files.writeString(overrideRoot.resolve("marker.txt"), "from-override");

    GenerationMiddlewareDesc desc =
        GenerationMiddlewarePlugin.create().middlewares(new DefaultRegistry()).stream()
            .filter(d -> Filesystem.NAME.equals(d.name()))
            .findFirst()
            .orElseThrow();
    desc.instantiate(JsonUtils.toJsonNode(Map.of("rootDirectory", protoRoot.toString())));
    GenerationMiddleware override =
        desc.instantiate(JsonUtils.toJsonNode(Map.of("rootDirectory", overrideRoot.toString())));

    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-devui",
            Turn.tools(request("read_file", Map.of("filePath", "marker.txt"))),
            Turn.text("done"));
    run(genkit, "test/fs-devui", override);
    assertTrue(seen.size() >= 2);
    assertTrue(anyText(seen.get(1).getMessages(), null, "from-override"));
    assertFalse(anyText(seen.get(1).getMessages(), null, "from-proto"));
  }

  @Test
  void filesystemMissingRootDirIsAnError() {
    GenkitException err =
        assertThrows(GenkitException.class, () -> new Filesystem(new Filesystem.Options()));
    assertTrue(err.getMessage().contains("RootDir is required"), err.getMessage());

    GenerationMiddlewareDesc desc =
        GenerationMiddlewarePlugin.create().middlewares(new DefaultRegistry()).stream()
            .filter(d -> Filesystem.NAME.equals(d.name()))
            .findFirst()
            .orElseThrow();
    assertThrows(GenkitException.class, () -> desc.instantiate(null));
  }

  @Test
  void filesystemListFilesIncludesSize(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit,
        "test/fs-list-size",
        Turn.tools(request("list_files", Map.of("dirPath", ""))),
        Turn.text("done"));
    ModelResponse resp = run(genkit, "test/fs-list-size", fs(root));

    Number helloSize = null;
    boolean sawDir = false;
    for (Map<String, Object> e : listEntries(resp.getMessages())) {
      if ("hello.txt".equals(e.get("path"))) {
        helloSize = (Number) e.get("sizeBytes");
      }
      if ("docs".equals(e.get("path"))) {
        sawDir = true;
        assertFalse(e.containsKey("sizeBytes"), "directory entry should not include sizeBytes");
      }
    }
    assertNotNull(helloSize);
    assertEquals("hello world".length(), helloSize.intValue());
    assertTrue(sawDir);
  }

  @Test
  void filesystemReadFileIncludesLineMetadata(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("lines.txt"), "a\nb\nc\nd\ne\n");
    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-read-meta",
            Turn.tools(request("read_file", Map.of("filePath", "lines.txt"))),
            Turn.tools(
                request("read_file", Map.of("filePath", "lines.txt", "offset", 2, "limit", 2))),
            Turn.text("done"));
    run(genkit, "test/fs-read-meta", fs(root));
    assertTrue(seen.size() >= 3);
    assertTrue(anyText(seen.get(1).getMessages(), null, "totalLines=\"5\"", "path=\"lines.txt\""));
    assertTrue(anyText(seen.get(2).getMessages(), null, "lines=\"2-3\"", "totalLines=\"5\""));
  }

  @Test
  void filesystemEditFileRequiresPriorRead(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit,
        "test/fs-edit-noread",
        Turn.tools(edit("hello.txt", "hello world", "hello there")),
        Turn.text("ok"));
    run(genkit, "test/fs-edit-noread", writableFs(root));
    assertEquals("hello world", Files.readString(root.resolve("hello.txt")));
  }

  @Test
  void filesystemEditFileDetectsExternalModification(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Path hello = root.resolve("hello.txt");
    Turn[] script = {
      Turn.tools(request("read_file", Map.of("filePath", "hello.txt"))),
      Turn.tools(edit("hello.txt", "hello world", "hello there")),
      Turn.text("done"),
    };
    int[] turn = {0};
    Genkit genkit = Genkit.builder().build();
    registerModel(
        genkit,
        "test/fs-edit-stale",
        (req, cb) -> {
          // Between the read and the edit, simulate the user editing the file.
          if (turn[0] == 1) {
            try {
              Thread.sleep(10);
              Files.writeString(hello, "user changed it");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
          Turn t = script[turn[0]++];
          return t.tools.isEmpty()
              ? reply(req, Message.model(t.text))
              : reply(req, toolRequests(t.tools.toArray(new ToolRequest[0])));
        });
    run(genkit, "test/fs-edit-stale", writableFs(root));
    assertEquals("user changed it", Files.readString(hello));
  }

  @Test
  void filesystemReadFileDedupsUnchanged(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-read-dedup",
            Turn.tools(request("read_file", Map.of("filePath", "hello.txt"))),
            Turn.tools(request("read_file", Map.of("filePath", "hello.txt"))),
            Turn.text("done"));
    run(genkit, "test/fs-read-dedup", fs(root));
    assertTrue(seen.size() >= 3);

    boolean stubFound = false;
    int bodyCount = 0;
    for (Message m : seen.get(2).getMessages()) {
      for (Part p : m.getContent()) {
        if (p.getToolResponse() != null
            && "read_file".equals(p.getToolResponse().getName())
            && String.valueOf(p.getToolResponse().getOutput())
                .contains("File unchanged since last read")) {
          stubFound = true;
        }
        if (m.getRole() == Role.USER
            && p.getText() != null
            && p.getText().contains("hello world")
            && p.getText().contains("path=\"hello.txt\"")) {
          bodyCount++;
        }
      }
    }
    assertTrue(stubFound, "expected the dedup stub in the second read's response");
    assertEquals(1, bodyCount, "want exactly one injected file body across history");
  }

  @Test
  void filesystemWriteFileRequiresReadOnOverwrite(@TempDir Path tmp) throws Exception {
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit,
        "test/fs-write-noread",
        Turn.tools(request("write_file", Map.of("filePath", "hello.txt", "content", "clobbered"))),
        Turn.text("ok"));
    run(genkit, "test/fs-write-noread", writableFs(root));
    assertEquals("hello world", Files.readString(root.resolve("hello.txt")));
  }

  @Test
  void filesystemReadFileOffsetLimit(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("lines.txt"), "a\nb\nc\nd\ne\n");
    Genkit genkit = Genkit.builder().build();
    List<ModelRequest> seen =
        scriptedModel(
            genkit,
            "test/fs-offset",
            Turn.tools(
                request("read_file", Map.of("filePath", "lines.txt", "offset", 2, "limit", 2))),
            Turn.text("done"));
    run(genkit, "test/fs-offset", fs(root));
    boolean found = false;
    for (Message m : seen.get(1).getMessages()) {
      for (Part p : m.getContent()) {
        if (p.getText() != null && p.getText().contains("b\nc") && !p.getText().contains("a\nb")) {
          found = true;
        }
      }
    }
    assertTrue(found, "offset/limit did not produce the expected line window");
  }

  @Test
  void filesystemErrorsOfOtherToolsAreUnchanged(@TempDir Path tmp) throws Exception {
    // A failed file operation answers the call; Filesystem does not change how other tools fail.
    Path root = makeFs(tmp);
    Genkit genkit = Genkit.builder().build();
    scriptedModel(
        genkit, "test/fs-other", Turn.tools(request("other", Map.of())), Turn.text("done"));
    Tool<MiddlewareTestSupport.VInput, String> other =
        MiddlewareTestSupport.vTool(
            "other",
            "fails",
            in -> {
              throw new IllegalStateException("boom");
            });
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.builder()
                .model("test/fs-other")
                .prompt("go")
                .tools(List.of(other))
                .use(fs(root))
                .build());
    assertEquals("done", resp.getText());
  }

  @Test
  void applyEditDeletesWithEmptyNewString() {
    String content = "keep\n- [ ] Write a smoke test for /health.\nkeep\n";
    String got =
        Filesystem.applyEdit(
            content,
            new Filesystem.EditSpec("- [ ] Write a smoke test for /health.\n", "", false),
            "todo.txt");
    assertEquals("keep\nkeep\n", got);
  }

  @Test
  void applyEditMultiMatchRequiresReplaceAll() {
    String content = "foo\nfoo\nbar";
    assertThrows(
        IllegalArgumentException.class,
        () -> Filesystem.applyEdit(content, new Filesystem.EditSpec("foo", "baz", false), "x.txt"));
    assertEquals(
        "baz\nbaz\nbar",
        Filesystem.applyEdit(content, new Filesystem.EditSpec("foo", "baz", true), "x.txt"));
  }

  @Test
  void applyEditEqualOldAndNew() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Filesystem.applyEdit("hello", new Filesystem.EditSpec("hello", "hello", false), "x"));
  }

  @Test
  void applyEditNotFound() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Filesystem.applyEdit("hello", new Filesystem.EditSpec("missing", "x", false), "x"));
  }

  @Test
  void applyEditEmptyOldString() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Filesystem.applyEdit("hello", new Filesystem.EditSpec("", "x", false), "x"));
  }

  @Test
  void normalizeRel() {
    Map<String, String> cases = new HashMap<>();
    cases.put("", ".");
    cases.put("   ", ".");
    cases.put("foo/bar", "foo/bar");
    cases.put("/foo/bar", "foo/bar");
    cases.put("//foo//bar", "foo/bar");
    cases.put("./foo", "foo");
    cases.put("foo\\bar", "foo/bar");
    for (Map.Entry<String, String> c : cases.entrySet()) {
      assertEquals(
          c.getValue(), Filesystem.normalizeRel(c.getKey()), "normalizeRel(" + c.getKey() + ")");
    }
  }

  @Test
  void sliceAndCountLines() {
    byte[] data = "a\nb\nc".getBytes(StandardCharsets.UTF_8);
    assertEquals(3, Filesystem.countLines(data));
    assertEquals(2, Filesystem.countLines("a\nb\n".getBytes(StandardCharsets.UTF_8)));
    assertEquals("b\n", new String(Filesystem.sliceLines(data, 2, 1), StandardCharsets.UTF_8));
    assertEquals(0, Filesystem.sliceLines(data, 9, 1).length);
  }
}
