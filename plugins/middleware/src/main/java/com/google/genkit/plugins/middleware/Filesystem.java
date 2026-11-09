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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.SoftToolErrorPolicy;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grants the model scoped file access under a single root directory. It contributes {@code
 * list_files} and {@code read_file}, plus {@code write_file} and {@code edit_file} when {@link
 * Options#allowWriteAccess} is set.
 *
 * <p>Every path is confined to the root: a path that resolves outside it, through {@code ".."}, an
 * absolute path, or a symbolic link, is refused.
 *
 * <p>{@code read_file} delivers a file's contents as a user message on the next turn, and answers
 * the call with a short summary. A failed file operation does not fail the generation: its error
 * answers the call, as with {@link SoftToolErrors}, so the model can correct itself.
 *
 * <pre>{@code
 * genkit.generate(GenerateOptions.builder()
 *     .model("googleai/gemini-2.5-flash")
 *     .prompt("summarise docs/ and save the summary to out.md")
 *     .use(Filesystem.builder().rootDirectory("./workspace").allowWriteAccess(true).build())
 *     .build());
 * }</pre>
 */
public class Filesystem extends BaseGenerationMiddleware implements SoftToolErrorPolicy {

  private static final Logger logger = LoggerFactory.getLogger(Filesystem.class);

  /** The registered middleware name. */
  public static final String NAME = GenerationMiddlewarePlugin.PLUGIN_NAME + "/filesystem";

  /**
   * The metadata key that marks the user messages Filesystem adds to carry file contents, valued
   * with the tool name. Those messages are tool output, not something the user wrote.
   */
  public static final String TOOL_METADATA_KEY = "filesystemMiddlewareTool";

  /** Caps a single full read or returned slice. */
  static final int READ_MAX_BYTES = 256 * 1024;

  /** The on-disk file-size ceiling for any read. */
  static final long FILE_MAX_BYTES = 10L * 1024 * 1024;

  /** Bounds the per-call file state cache; eviction is FIFO on insert. */
  static final int FILE_STATE_CACHE_MAX_ENTRIES = 200;

  /** Returned by read_file when a prior read at the same range is still current. */
  static final String FILE_UNCHANGED_STUB =
      "File unchanged since last read. The content from the earlier read_file result in this"
          + " conversation is still current — refer to that instead of re-reading.";

  private static final int PATH_LOCK_BUCKETS = 256;

  private final Options options;

  // Per-call state, allocated by newInstance().
  private final Path root;
  private final List<Tool<?, ?>> tools;
  private final Set<String> toolNames;
  private final List<Message> queue = new ArrayList<>();
  private final FileStateCache cache = new FileStateCache(FILE_STATE_CACHE_MAX_ENTRIES);
  private final ReentrantLock[] locks = new ReentrantLock[PATH_LOCK_BUCKETS];

  /**
   * Creates the middleware.
   *
   * @param options the configuration; {@link Options#rootDirectory} is required
   * @throws GenkitException if the root directory is unset or cannot be opened
   */
  public Filesystem(Options options) {
    this.options = options != null ? options : new Options();
    String dir = this.options.rootDirectory;
    if (dir == null || dir.trim().isEmpty()) {
      throw new GenkitException("filesystem middleware: RootDir is required");
    }
    Path abs = Path.of(dir).toAbsolutePath().normalize();
    try {
      if (!Files.isDirectory(abs)) {
        throw new GenkitException(
            "filesystem middleware: open root \"" + abs + "\": not a directory");
      }
      this.root = abs.toRealPath();
    } catch (IOException e) {
      throw new GenkitException("filesystem middleware: open root \"" + abs + "\": " + e, e);
    }
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new ReentrantLock();
    }
    this.tools = buildTools();
    Set<String> names = new java.util.HashSet<>();
    for (Tool<?, ?> t : tools) {
      names.add(t.getName());
    }
    this.toolNames = Collections.unmodifiableSet(names);
  }

  /**
   * Returns a builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns this middleware's configuration.
   *
   * @return the options
   */
  public Options getOptions() {
    return options;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public GenerationMiddleware newInstance() {
    return new Filesystem(options);
  }

  @Override
  public List<Tool<?, ?>> tools() {
    return tools;
  }

  /** A failed file operation answers the call; other tools keep their own error handling. */
  @Override
  public boolean coversToolErrors(String toolName) {
    return toolNames.contains(toolName);
  }

  @Override
  public boolean strictToolErrors() {
    return false;
  }

  @Override
  public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException {
    List<Message> queued;
    synchronized (queue) {
      queued = new ArrayList<>(queue);
      queue.clear();
    }
    if (!queued.isEmpty()) {
      logger.debug("filesystem middleware injecting {} queued messages", queued.size());
      int messageIndex = params.getMessageIndex();
      Consumer<ModelResponseChunk> onChunk = params.getOnChunk();
      if (onChunk != null) {
        for (Message msg : queued) {
          ModelResponseChunk chunk = new ModelResponseChunk(msg.getContent());
          chunk.setRole(msg.getRole());
          chunk.setIndex(messageIndex);
          onChunk.accept(chunk);
          messageIndex++;
        }
      }
      GenerateActionOptions req = params.getRequest();
      List<Message> messages =
          new ArrayList<>(req.getMessages() != null ? req.getMessages() : List.of());
      messages.addAll(queued);
      params = params.withRequest(req.withMessages(messages)).withMessageIndex(messageIndex);
    }
    return next.apply(ctx, params);
  }

  private String toolName(String suffix) {
    return (options.toolNamePrefix != null ? options.toolNamePrefix : "") + suffix;
  }

  private void enqueueParts(Part... parts) {
    synchronized (queue) {
      if (!queue.isEmpty() && queue.get(queue.size() - 1).getRole() == Role.USER) {
        Message last = queue.get(queue.size() - 1);
        List<Part> content = new ArrayList<>(last.getContent());
        content.addAll(List.of(parts));
        last.setContent(content);
        return;
      }
      Message msg = new Message(Role.USER, new ArrayList<>(List.of(parts)));
      Map<String, Object> metadata = new HashMap<>();
      metadata.put(TOOL_METADATA_KEY, toolName("read_file"));
      msg.setMetadata(metadata);
      queue.add(msg);
    }
  }

  private List<Tool<?, ?>> buildTools() {
    List<Tool<?, ?>> list = new ArrayList<>();
    list.add(listFilesTool());
    list.add(readFileTool());
    if (options.allowWriteAccess) {
      list.add(writeFileTool());
      list.add(editFileTool());
    }
    return Collections.unmodifiableList(list);
  }

  // ── path handling ──────────────────────────────────────────────────────────────

  /**
   * Canonicalises a model-supplied path into a slash-separated relative path. Leading separators
   * are stripped so "/foo" is treated as relative to the root; {@link #resolve} enforces the final
   * escape check.
   */
  static String normalizeRel(String p) {
    p = p == null ? "" : p.trim();
    p = p.replace('\\', '/');
    int i = 0;
    while (i < p.length() && p.charAt(i) == '/') {
      i++;
    }
    p = p.substring(i);
    if (p.isEmpty()) {
      return ".";
    }
    return cleanPath(p);
  }

  /** Lexically cleans a slash-separated relative path. */
  private static String cleanPath(String p) {
    List<String> out = new ArrayList<>();
    for (String seg : p.split("/")) {
      if (seg.isEmpty() || seg.equals(".")) {
        continue;
      }
      if (seg.equals("..") && !out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
        out.remove(out.size() - 1);
        continue;
      }
      out.add(seg);
    }
    return out.isEmpty() ? "." : String.join("/", out);
  }

  /**
   * Resolves a normalized relative path inside the root, refusing any path that escapes it,
   * lexically or through a symbolic link.
   */
  private Path resolve(String op, String rel) throws IOException {
    if (rel.equals("..") || rel.startsWith("../")) {
      throw new IOException(op + " " + rel + ": path escapes from parent");
    }
    Path target = rel.equals(".") ? root : root.resolve(rel).normalize();
    if (!target.startsWith(root)) {
      throw new IOException(op + " " + rel + ": path escapes from parent");
    }
    // Follow symbolic links on the deepest existing ancestor and confirm it stays inside.
    Path existing = target;
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    if (existing != null && Files.exists(existing)) {
      Path real = existing.toRealPath();
      if (!real.startsWith(root)) {
        throw new IOException(op + " " + rel + ": path escapes from parent");
      }
    } else if (existing != null && Files.isSymbolicLink(existing)) {
      throw new IOException(op + " " + rel + ": path escapes from parent");
    }
    return target;
  }

  private static BasicFileAttributes stat(Path p, String rel) throws IOException {
    try {
      return Files.readAttributes(p, BasicFileAttributes.class);
    } catch (NoSuchFileException e) {
      throw new IOException("stat " + rel + ": no such file or directory", e);
    }
  }

  private ReentrantLock lockFor(String path) {
    int h = 0;
    for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
      h = h * 31 + (b & 0xff);
    }
    return locks[Integer.remainderUnsigned(h, PATH_LOCK_BUCKETS)];
  }

  private static RuntimeException fail(IOException e) {
    return new UncheckedIOException(e.getMessage(), e);
  }

  private static void requireFilePath(String s) {
    if (s == null || s.trim().isEmpty()) {
      throw new IllegalArgumentException("filePath is required");
    }
  }

  static String mimeType(String rel) {
    int dot = rel.lastIndexOf('.');
    String ext = dot >= 0 ? rel.substring(dot).toLowerCase(Locale.ROOT) : "";
    switch (ext) {
      case ".png":
        return "image/png";
      case ".jpg":
      case ".jpeg":
        return "image/jpeg";
      case ".gif":
        return "image/gif";
      case ".webp":
        return "image/webp";
      case ".svg":
        return "image/svg+xml";
      case ".avif":
        return "image/avif";
      default:
        String guessed = URLConnection.guessContentTypeFromName("x" + ext);
        return guessed != null ? guessed : "";
    }
  }

  // ── list_files ─────────────────────────────────────────────────────────────────

  /** Input of list_files. */
  public static class ListFilesInput {
    @JsonProperty("dirPath")
    @JsonPropertyDescription("Directory path relative to root. Defaults to the root directory.")
    public String dirPath;

    @JsonProperty("recursive")
    @JsonPropertyDescription("If true, descend into subdirectories.")
    public boolean recursive;
  }

  /** An entry list_files returns. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class FileEntry {
    @JsonProperty("path")
    public String path;

    @JsonProperty("isDirectory")
    public boolean isDirectory;

    @JsonProperty("sizeBytes")
    public Long sizeBytes;

    /** Creates an empty entry. */
    public FileEntry() {}

    FileEntry(String path, boolean isDirectory, Long sizeBytes) {
      this.path = path;
      this.isDirectory = isDirectory;
      this.sizeBytes = sizeBytes;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Tool<ListFilesInput, List<FileEntry>> listFilesTool() {
    return Tool.<ListFilesInput, List<FileEntry>>builder()
        .name(toolName("list_files"))
        .description(
            "Lists files and directories in a given path. Returns a list of entries with path and"
                + " type.")
        .inputClass(ListFilesInput.class)
        .outputSchema(Map.of("type", "array"))
        .handler((ctx, in) -> {
          String dir = normalizeRel(in != null ? in.dirPath : null);
          try {
            Path base = resolve("open", dir);
            if (!stat(base, dir).isDirectory()) {
              throw new IOException("readdirent " + dir + ": not a directory");
            }
            List<FileEntry> out = new ArrayList<>();
            if (in == null || !in.recursive) {
              for (Path p : sortedChildren(base)) {
                out.add(entry(p, p.getFileName().toString()));
              }
              return out;
            }
            walk(base, "", out);
            return out;
          } catch (IOException e) {
            throw fail(e);
          }
        })
        .build();
  }

  private static List<Path> sortedChildren(Path dir) throws IOException {
    List<Path> children = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path p : stream) {
        children.add(p);
      }
    }
    children.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
    return children;
  }

  private static FileEntry entry(Path p, String rel) {
    boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
    Long size = null;
    if (!isDir) {
      try {
        long s = Files.size(p);
        size = s != 0 ? s : null;
      } catch (IOException e) {
        size = null;
      }
    }
    return new FileEntry(rel, isDir, size);
  }

  private static void walk(Path dir, String prefix, List<FileEntry> out) throws IOException {
    for (Path p : sortedChildren(dir)) {
      String rel = prefix + p.getFileName();
      FileEntry e = entry(p, rel);
      out.add(e);
      if (e.isDirectory) {
        walk(p, rel + "/", out);
      }
    }
  }

  // ── read_file ──────────────────────────────────────────────────────────────────

  /** Input of read_file. */
  public static class ReadFileInput {
    @JsonProperty(value = "filePath", required = true)
    @JsonPropertyDescription("File path relative to root.")
    public String filePath;

    @JsonProperty("offset")
    @JsonPropertyDescription(
        "1-indexed line to start reading from. 0 or 1 means start at the beginning.")
    public int offset;

    @JsonProperty("limit")
    @JsonPropertyDescription("Maximum number of lines to read. 0 means read to end of file.")
    public int limit;
  }

  private Tool<ReadFileInput, String> readFileTool() {
    return Tool.<ReadFileInput, String>builder()
        .name(toolName("read_file"))
        .description(
            "Reads the contents of a file. The actual contents are delivered as a user message on"
                + " the next turn. Use offset and limit (1-indexed lines) for large files.")
        .inputClass(ReadFileInput.class)
        .outputClass(String.class)
        .handler((ctx, in) -> {
          String filePath = in != null ? in.filePath : null;
          requireFilePath(filePath);
          try {
            return readFile(in);
          } catch (IOException e) {
            throw fail(e);
          }
        })
        .build();
  }

  private String readFile(ReadFileInput in) throws IOException {
    String rel = normalizeRel(in.filePath);
    String mime = mimeType(rel);
    boolean isImage = mime.startsWith("image/");
    Path path = resolve("stat", rel);
    String key = path.toString();

    ReentrantLock lock = lockFor(key);
    lock.lock();
    try {
      BasicFileAttributes info = stat(path, rel);
      if (info.isDirectory()) {
        throw new IOException("path " + in.filePath + " is a directory; use list_files");
      }

      if (!isImage) {
        FileState prev = cache.get(key);
        if (prev != null
            && prev.modTime.equals(info.lastModifiedTime())
            && prev.size == info.size()
            && prev.offset == in.offset
            && prev.limit == in.limit) {
          return FILE_UNCHANGED_STUB;
        }
      }

      long fullSize = info.size();
      if (fullSize > FILE_MAX_BYTES) {
        throw new IOException(
            String.format(
                "file %s is %d bytes (>%d); too large to read until streaming line-slicing is"
                    + " supported",
                in.filePath, fullSize, FILE_MAX_BYTES));
      }
      if (!isImage && in.offset == 0 && in.limit == 0 && fullSize > READ_MAX_BYTES) {
        throw new IOException(
            String.format(
                "file %s is %d bytes (>%d); use offset/limit to read a slice",
                in.filePath, fullSize, READ_MAX_BYTES));
      }

      byte[] data = Files.readAllBytes(path);
      byte[] content = data;
      boolean sliced = in.offset > 0 || in.limit > 0;
      if (!isImage && sliced) {
        content = sliceLines(data, in.offset, in.limit);
        if (content.length > READ_MAX_BYTES) {
          throw new IOException(
              String.format(
                  "requested slice of %s is %d bytes (>%d); narrow the range",
                  in.filePath, content.length, READ_MAX_BYTES));
        }
      }

      if (isImage) {
        String encoded = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(content);
        enqueueParts(
            Part.text("\n\nread_file result " + mime + " " + in.filePath),
            Part.media(mime, encoded));
        return "File " + in.filePath + " read successfully, see contents below.";
      }

      int totalLines = countLines(data);
      int shownLines = countLines(content);
      String header;
      String summary;
      if (sliced) {
        int startLine = Math.max(in.offset, 1);
        int endLine = Math.max(startLine + shownLines - 1, startLine);
        header =
            String.format(
                "<read_file path=%s lines=\"%d-%d\" totalLines=\"%d\">",
                quote(in.filePath), startLine, endLine, totalLines);
        summary =
            String.format(
                "File %s read successfully (lines %d-%d of %d total). See contents below.",
                in.filePath, startLine, endLine, totalLines);
      } else {
        header =
            String.format("<read_file path=%s totalLines=\"%d\">", quote(in.filePath), totalLines);
        summary =
            String.format(
                "File %s read successfully (%d lines). See contents below.",
                in.filePath, totalLines);
      }
      // No newline before the closing tag: the file's own terminator separates it when present,
      // and none is fabricated when it is not.
      enqueueParts(
          Part.text(header + "\n" + new String(content, StandardCharsets.UTF_8) + "</read_file>"));
      cache.set(key, new FileState(info, in.offset, in.limit));
      return summary;
    } finally {
      lock.unlock();
    }
  }

  /** Quotes a string the way a Go %q verb does for printable text. */
  static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\r':
          sb.append("\\r");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\x%02x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.append('"').toString();
  }

  /**
   * Returns the number of lines in data. A trailing newline does not add a phantom empty line; an
   * unterminated final line still counts.
   */
  static int countLines(byte[] data) {
    if (data.length == 0) {
      return 0;
    }
    int n = 0;
    for (byte b : data) {
      if (b == '\n') {
        n++;
      }
    }
    if (data[data.length - 1] != '\n') {
      n++;
    }
    return n;
  }

  /**
   * Returns the bytes covering lines [offset, offset+limit) of data, 1-indexed. offset &lt;= 1
   * starts at the beginning; limit &lt;= 0 reads to the end. Empty when offset is past the end.
   * Line terminators are preserved byte for byte.
   */
  static byte[] sliceLines(byte[] data, int offset, int limit) {
    if (offset < 1) {
      offset = 1;
    }
    int start = 0;
    for (int i = 1; i < offset; i++) {
      int idx = indexOf(data, start, (byte) '\n');
      if (idx == -1) {
        return new byte[0];
      }
      start = idx + 1;
    }
    if (start >= data.length) {
      return new byte[0];
    }
    if (limit <= 0) {
      return java.util.Arrays.copyOfRange(data, start, data.length);
    }
    int end = start;
    for (int i = 0; i < limit && end < data.length; i++) {
      int idx = indexOf(data, end, (byte) '\n');
      if (idx == -1) {
        end = data.length;
        break;
      }
      end = idx + 1;
    }
    return java.util.Arrays.copyOfRange(data, start, end);
  }

  private static int indexOf(byte[] data, int from, byte b) {
    for (int i = from; i < data.length; i++) {
      if (data[i] == b) {
        return i;
      }
    }
    return -1;
  }

  // ── write_file ─────────────────────────────────────────────────────────────────

  /** Input of write_file. */
  public static class WriteFileInput {
    @JsonProperty(value = "filePath", required = true)
    @JsonPropertyDescription("File path relative to root.")
    public String filePath;

    @JsonProperty(value = "content", required = true)
    @JsonPropertyDescription("Content to write to the file.")
    public String content;
  }

  private Tool<WriteFileInput, String> writeFileTool() {
    return Tool.<WriteFileInput, String>builder()
        .name(toolName("write_file"))
        .description(
            "Writes content to a file, creating it (and any missing parent directories) or"
                + " overwriting it if it exists. Overwriting an existing file requires a prior"
                + " read_file in this session.")
        .inputClass(WriteFileInput.class)
        .outputClass(String.class)
        .handler((ctx, in) -> {
          String filePath = in != null ? in.filePath : null;
          requireFilePath(filePath);
          try {
            return writeFile(in);
          } catch (IOException e) {
            throw fail(e);
          }
        })
        .build();
  }

  private String writeFile(WriteFileInput in) throws IOException {
    String rel = normalizeRel(in.filePath);
    Path path = resolve("open", rel);
    String key = path.toString();

    ReentrantLock lock = lockFor(key);
    lock.lock();
    try {
      boolean exists = Files.exists(path);
      if (exists) {
        BasicFileAttributes info = stat(path, rel);
        if (info.isDirectory()) {
          throw new IOException("path " + in.filePath + " is a directory");
        }
        FileState prev = cache.get(key);
        if (prev == null) {
          throw new IOException(
              "file "
                  + in.filePath
                  + " exists but has not been read yet; read_file it first before overwriting");
        }
        if (!info.lastModifiedTime().equals(prev.modTime) || info.size() != prev.size) {
          throw new IOException(
              "file "
                  + in.filePath
                  + " has been modified since last read; re-read it before overwriting");
        }
      }
      Path parent = path.getParent();
      if (parent != null && !parent.equals(root)) {
        try {
          Files.createDirectories(parent);
        } catch (IOException e) {
          throw new IOException("create parent dirs: " + e.getMessage(), e);
        }
      }
      Files.write(path, (in.content != null ? in.content : "").getBytes(StandardCharsets.UTF_8));
      cache.set(key, new FileState(Files.readAttributes(path, BasicFileAttributes.class), 0, 0));
      return "File " + in.filePath + " written successfully.";
    } finally {
      lock.unlock();
    }
  }

  // ── edit_file ──────────────────────────────────────────────────────────────────

  /** One edit of edit_file. */
  public static class EditSpec {
    @JsonProperty(value = "oldString", required = true)
    @JsonPropertyDescription(
        "The exact text to find. Match is byte-for-byte including whitespace and indentation.")
    public String oldString;

    @JsonProperty(value = "newString", required = true)
    @JsonPropertyDescription("The replacement text. Use an empty string to delete oldString.")
    public String newString;

    @JsonProperty("replaceAll")
    @JsonPropertyDescription(
        "If true, replace every occurrence of oldString. If false (default), oldString must"
            + " match exactly once in the file.")
    public boolean replaceAll;

    /** Creates an empty edit. */
    public EditSpec() {}

    EditSpec(String oldString, String newString, boolean replaceAll) {
      this.oldString = oldString;
      this.newString = newString;
      this.replaceAll = replaceAll;
    }
  }

  /** Input of edit_file. */
  public static class EditFileInput {
    @JsonProperty(value = "filePath", required = true)
    @JsonPropertyDescription("File path relative to root.")
    public String filePath;

    @JsonProperty(value = "edits", required = true)
    @JsonPropertyDescription(
        "One or more edits to apply in order. Each edit is applied to the result of the previous"
            + " edit.")
    public List<EditSpec> edits;
  }

  private Tool<EditFileInput, String> editFileTool() {
    return Tool.<EditFileInput, String>builder()
        .name(toolName("edit_file"))
        .description(
            "Applies one or more structured edits to a file. Requires a prior read_file in this"
                + " session. Edits are applied sequentially; later edits see the changes from"
                + " earlier ones. Each edit's oldString must match the file byte-for-byte"
                + " (including whitespace), and by default must be unique — set replaceAll on the"
                + " edit to rename across all occurrences.")
        .inputClass(EditFileInput.class)
        .outputClass(String.class)
        .handler((ctx, in) -> {
          String filePath = in != null ? in.filePath : null;
          requireFilePath(filePath);
          if (in.edits == null || in.edits.isEmpty()) {
            throw new IllegalArgumentException("edits is required");
          }
          try {
            return editFile(in);
          } catch (IOException e) {
            throw fail(e);
          }
        })
        .build();
  }

  private String editFile(EditFileInput in) throws IOException {
    String rel = normalizeRel(in.filePath);
    Path path = resolve("stat", rel);
    String key = path.toString();

    ReentrantLock lock = lockFor(key);
    lock.lock();
    try {
      FileState prev = cache.get(key);
      if (prev == null) {
        throw new IOException(
            "file " + in.filePath + " has not been read yet; read_file it first before editing");
      }
      BasicFileAttributes info = stat(path, rel);
      if (!info.lastModifiedTime().equals(prev.modTime) || info.size() != prev.size) {
        throw new IOException(
            "file "
                + in.filePath
                + " has been modified since last read; re-read it before editing");
      }
      String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      for (int i = 0; i < in.edits.size(); i++) {
        try {
          content = applyEdit(content, in.edits.get(i), in.filePath);
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("edit " + i + ": " + e.getMessage(), e);
        }
      }
      Files.write(path, content.getBytes(StandardCharsets.UTF_8));
      cache.set(key, new FileState(Files.readAttributes(path, BasicFileAttributes.class), 0, 0));
      return "Successfully applied " + in.edits.size() + " edit(s) to " + in.filePath + ".";
    } finally {
      lock.unlock();
    }
  }

  /**
   * Replaces oldString with newString in content. With replaceAll, every occurrence is replaced;
   * without it, oldString must match exactly once.
   */
  static String applyEdit(String content, EditSpec e, String filePath) {
    String oldString = e.oldString != null ? e.oldString : "";
    String newString = e.newString != null ? e.newString : "";
    if (oldString.isEmpty()) {
      throw new IllegalArgumentException("oldString is required");
    }
    if (oldString.equals(newString)) {
      throw new IllegalArgumentException("oldString and newString are identical");
    }
    int count = 0;
    for (int i = content.indexOf(oldString);
        i >= 0;
        i = content.indexOf(oldString, i + oldString.length())) {
      count++;
    }
    if (count == 0) {
      throw new IllegalArgumentException(
          "oldString not found in "
              + filePath
              + "; the match must be byte-for-byte, including whitespace and indentation");
    }
    if (count > 1 && !e.replaceAll) {
      throw new IllegalArgumentException(
          "oldString matches "
              + count
              + " locations in "
              + filePath
              + "; add more surrounding context to make it unique, or set replaceAll=true");
    }
    if (e.replaceAll) {
      return content.replace(oldString, newString);
    }
    int idx = content.indexOf(oldString);
    return content.substring(0, idx) + newString + content.substring(idx + oldString.length());
  }

  // ── file state cache ───────────────────────────────────────────────────────────

  /** The on-disk state observed at the last read or write of a path. */
  static final class FileState {
    final FileTime modTime;
    final long size;
    final int offset;
    final int limit;

    FileState(BasicFileAttributes info, int offset, int limit) {
      this.modTime = info.lastModifiedTime();
      this.size = info.size();
      this.offset = offset;
      this.limit = limit;
    }
  }

  /** A per-call, bounded path to state map with FIFO eviction on insert. */
  static final class FileStateCache {
    private final int max;
    private final LinkedHashMap<String, FileState> entries = new LinkedHashMap<>();

    FileStateCache(int max) {
      this.max = max;
    }

    synchronized FileState get(String path) {
      return entries.get(path);
    }

    synchronized void set(String path, FileState state) {
      if (!entries.containsKey(path) && entries.size() >= max && !entries.isEmpty()) {
        String eldest = entries.keySet().iterator().next();
        entries.remove(eldest);
      }
      entries.put(path, state);
    }
  }

  // ── configuration ──────────────────────────────────────────────────────────────

  /** Configuration for {@link Filesystem}. */
  public static class Options {

    /** The directory that all operations are confined to. */
    @JsonProperty("rootDirectory")
    @JsonPropertyDescription("Directory that all file operations are confined to. Required.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String rootDirectory;

    /** Adds write_file and edit_file. */
    @JsonProperty("allowWriteAccess")
    @JsonPropertyDescription("Adds the write_file and edit_file tools. Defaults to false.")
    public boolean allowWriteAccess;

    /** Prepended to each tool name. */
    @JsonProperty("toolNamePrefix")
    @JsonPropertyDescription(
        "Prepended to each tool name. Use distinct prefixes when attaching multiple filesystem"
            + " middlewares to one call, so their tool names do not collide.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public String toolNamePrefix;

    /** Creates the default options. */
    public Options() {}
  }

  /** Builder for {@link Filesystem}. */
  public static class Builder {
    private final Options options = new Options();

    /**
     * Sets the directory all operations are confined to. Required.
     *
     * @param dir the root directory
     * @return this builder
     */
    public Builder rootDirectory(String dir) {
      options.rootDirectory = dir;
      return this;
    }

    /**
     * Adds the write_file and edit_file tools.
     *
     * @param allow whether to allow writes
     * @return this builder
     */
    public Builder allowWriteAccess(boolean allow) {
      options.allowWriteAccess = allow;
      return this;
    }

    /**
     * Sets the prefix prepended to each tool name.
     *
     * @param prefix the prefix
     * @return this builder
     */
    public Builder toolNamePrefix(String prefix) {
      options.toolNamePrefix = prefix;
      return this;
    }

    /**
     * Builds the middleware.
     *
     * @return the middleware
     * @throws GenkitException if the root directory is unset or cannot be opened
     */
    public Filesystem build() {
      return new Filesystem(options);
    }
  }
}
