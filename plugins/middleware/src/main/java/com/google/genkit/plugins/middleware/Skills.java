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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolInterruptException;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.ai.middleware.BaseGenerationMiddleware;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.ai.middleware.ToolParams;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware that makes a local library of "skills" available to the model, following the Agent
 * Skills specification (<a href="https://agentskills.io">agentskills.io</a>). A skill is a
 * directory containing a {@code SKILL.md} file whose contents become specialized instructions the
 * model can load on demand.
 *
 * <p>Skills implements the specification's three tiers of progressive disclosure:
 *
 * <ul>
 *   <li><b>Catalog.</b> A system prompt lists each available skill's name and description, and
 *       names the tool that loads one.
 *   <li><b>Instructions.</b> A {@value #SKILL_TOOL_NAME} tool returns a skill's full {@code
 *       SKILL.md}, wrapped in {@code <skill_content>} together with the skill's directory. A skill
 *       already loaded in the conversation is not sent twice.
 *   <li><b>Resources.</b> When {@link Options#allowResourceAccess} is set, each activation lists
 *       the files the skill bundles, and a {@value #SKILL_RESOURCE_TOOL_NAME} tool reads them on
 *       demand, confined to that one skill directory.
 * </ul>
 *
 * <p>{@code SKILL.md} should start with a YAML frontmatter block carrying {@code name} and {@code
 * description}. A skill that violates the format is loaded anyway and reported through the logger,
 * so a library authored for another agent still works here.
 *
 * <p>Skill directories are scanned once per {@code generate} call, so the catalog and the tools
 * agree on one skill set and an edited {@code SKILL.md} takes effect on the next call. Unreadable
 * paths, malformed frontmatter and oversized files are logged and skipped rather than reported as
 * errors. When no skill is found, the middleware adds no tools and leaves the request untouched.
 *
 * <p><b>Security:</b> skill paths resolve against the process working directory by default, or
 * inside {@link Options#skillFs} when it is set, and a discovered {@code SKILL.md} becomes
 * instruction text the model follows. Treat a skills tree the way you treat source code, and prefer
 * an absolute path over the default in a server process. Genkit applies no trust gate of its own.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Skills.Options opts = new Skills.Options();
 * opts.skillPaths = List.of("skills");
 *
 * ModelResponse resp = genkit.generate(GenerateOptions.builder()
 *     .model("googleai/gemini-2.5-flash")
 *     .prompt("use the python skill to compute ...")
 *     .use(new Skills(opts))
 *     .build());
 * }</pre>
 */
public class Skills extends BaseGenerationMiddleware {

  private static final Logger logger = LoggerFactory.getLogger(Skills.class);

  /** The registered name of this middleware. */
  public static final String NAME = "genkit-middleware/skills";

  /**
   * Name of the tool that loads a skill's instructions by name. When {@link Options#toolNamePrefix}
   * is set, the registered name is the prefix followed by this constant.
   */
  public static final String SKILL_TOOL_NAME = "use_skill";

  /**
   * Name of the tool that reads a file bundled inside a skill directory. It is registered only when
   * {@link Options#allowResourceAccess} is set. When {@link Options#toolNamePrefix} is set, the
   * registered name is the prefix followed by this constant.
   */
  public static final String SKILL_RESOURCE_TOOL_NAME = "read_skill_file";

  /**
   * Metadata key stamped on every message part that carries a skill's instructions, whether the
   * model loaded them with {@value #SKILL_TOOL_NAME} or {@link Options#preload} injected them. Its
   * value is the skill name as a string.
   *
   * <p>The middleware reads the key back out of the conversation to recognize a skill that is
   * already loaded. Context-management middleware can use it to find skill instructions in a
   * transcript and exempt them from summarization.
   */
  public static final String SKILL_ACTIVATION_METADATA_KEY = "skillActivation";

  // Directories scanned when skillPaths is unset. ".agents/skills" is the cross-client
  // interoperability convention; "skills" is scanned last so it keeps winning collisions.
  static final String AGENTS_SKILLS_PATH = ".agents/skills";
  static final String DEFAULT_SKILLS_PATH = "skills";

  // Matched case-exactly, so a case-insensitive volume discovers the same skills as any other.
  static final String SKILL_FILE_NAME = "SKILL.md";

  // Marks the catalog part so a later tool-loop iteration refreshes it instead of appending a
  // second copy. Its value is true.
  static final String SKILLS_MARKER = "skills-instructions";

  // Records the activation tool name of the instance that wrote a part, which is what tells two
  // instances on one call apart.
  static final String SKILLS_TOOL_METADATA_KEY = "skillsActivationTool";

  // Bounds a single SKILL.md. An oversized file is skipped, never truncated.
  static final long SKILL_MAX_BYTES = 1L << 20;

  // Bounds one read through the resource tool.
  static final long SKILL_RESOURCE_MAX_BYTES = 256L * 1024;

  // Advisory bounds from the specification. Exceeding one is a diagnostic; the skill still loads.
  static final int SKILL_NAME_MAX_RUNES = 64;
  static final int SKILL_DESCRIPTION_MAX_RUNES = 1024;

  // Bounds on the bundled-resource listing appended at activation.
  static final int SKILL_RESOURCE_LIST_MAX = 100;
  static final int SKILL_RESOURCE_MAX_DEPTH = 4;

  static final String SKILL_ALREADY_LOADED_STUB =
      "Skill %s is already loaded in this conversation; its instructions are still in context."
          + " Refer to the earlier result instead of loading it again.";

  private static final String TOOL_FAILED_PREFIX = "Tool execution failed: ";

  // Whether the host exposes POSIX file-type bits, used to describe a non-regular file.
  private static final boolean UNIX_LIKE =
      !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

  private static final ObjectMapper YAML =
      new ObjectMapper(
          YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

  private final Options options;

  // Per-generate state. The configured prototype scans lazily; newInstance() scans eagerly.
  private volatile State state;

  // Carries the activation metadata from the use_skill handler to wrapTool, which places it on the
  // tool response part.
  private final ThreadLocal<Map<String, Object>> pendingActivation = new ThreadLocal<>();

  /**
   * Creates a Skills middleware.
   *
   * @param options the configuration, or null for the defaults
   */
  public Skills(Options options) {
    this.options = options != null ? options : new Options();
  }

  /** Creates a Skills middleware with the default configuration. */
  public Skills() {
    this(null);
  }

  private Skills(Options options, boolean scan) {
    this(options);
    if (scan) {
      this.state = scan();
    }
  }

  /**
   * Returns a new builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configuration of this middleware.
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

  /**
   * Returns a fresh instance that scans the configured skill paths, so each {@code generate} call
   * sees the skills as they are on disk at that moment.
   */
  @Override
  public GenerationMiddleware newInstance() {
    return new Skills(options, true);
  }

  @Override
  public List<Tool<?, ?>> tools() {
    return state().tools;
  }

  @Override
  public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException {
    State st = state();
    if (st.empty) {
      return next.apply(ctx, params);
    }
    // Inject first, then read the activation set back out of the result: a preloaded skill marks
    // itself through the metadata on the part that carries it.
    GenerateActionOptions req = params.getRequest();
    List<Message> messages = injectSkills(req.getMessages(), st.catalog, st.preload);
    st.activation.reset(messages);
    return next.apply(ctx, params.withRequest(req.withMessages(messages)));
  }

  /**
   * Recovers from a failed skill tool by reporting the failure to the model instead of aborting the
   * generation. Interrupts are propagated untouched.
   */
  @Override
  public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next) throws GenkitException {
    State st = state();
    String toolName = params.getTool().getName();
    if (!st.toolSet.contains(toolName)) {
      return next.apply(ctx, params);
    }
    pendingActivation.remove();
    try {
      Part part = next.apply(ctx, params);
      Map<String, Object> activation = pendingActivation.get();
      if (activation != null && part != null) {
        Map<String, Object> meta =
            part.getMetadata() != null ? new HashMap<>(part.getMetadata()) : new HashMap<>();
        meta.putAll(activation);
        part.setMetadata(meta);
      }
      return part;
    } catch (RuntimeException e) {
      if (isInterrupt(e)) {
        throw e;
      }
      logger.debug(
          "skills tool failed, reporting to the model: tool={} error={}", toolName, e.getMessage());
      // The error text carries path components from disk, so it is folded like any other on-disk
      // string before the model reads it.
      String msg = "Tool " + goQuote(toolName) + " failed: " + catalogText(errorMessage(e)) + ".";
      // The skill list helps a model that could not name a skill to load. A failed file read
      // already named a valid skill, so the list would only be noise.
      if (toolName.equals(toolName(SKILL_TOOL_NAME))) {
        msg += " " + st.available;
      }
      ToolRequest req = params.getRequest();
      String ref = req != null ? req.getRef() : null;
      String name = req != null && req.getName() != null ? req.getName() : toolName;
      return Part.toolResponse(new ToolResponse(ref, name, msg));
    } finally {
      pendingActivation.remove();
    }
  }

  private static boolean isInterrupt(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ToolInterruptException) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  // Tool.run wraps a handler failure with a generic prefix; the model reads the original text.
  private static String errorMessage(Throwable e) {
    String msg = e.getMessage();
    if (e instanceof GenkitException
        && e.getCause() != null
        && msg != null
        && msg.startsWith(TOOL_FAILED_PREFIX)) {
      String causeMsg = e.getCause().getMessage();
      if (causeMsg != null) {
        return causeMsg;
      }
      return msg.substring(TOOL_FAILED_PREFIX.length());
    }
    return msg != null ? msg : e.toString();
  }

  // ── Per-call state ──────────────────────────────────────────────────────────

  private State state() {
    State st = state;
    if (st == null) {
      synchronized (this) {
        st = state;
        if (st == null) {
          st = scan();
          state = st;
        }
      }
    }
    return st;
  }

  /** The skill set and hooks for one generate call. */
  private static final class State {
    final boolean empty;
    final List<Tool<?, ?>> tools;
    final Set<String> toolSet;
    final String catalog;
    final Map<String, SkillInfo> preload;
    final String available;
    final ActivationSet activation;

    State(
        boolean empty,
        List<Tool<?, ?>> tools,
        String catalog,
        Map<String, SkillInfo> preload,
        String available,
        ActivationSet activation) {
      this.empty = empty;
      this.tools = Collections.unmodifiableList(tools);
      Set<String> names = new HashSet<>();
      for (Tool<?, ?> t : tools) {
        names.add(t.getName());
      }
      this.toolSet = names;
      this.catalog = catalog;
      this.preload = preload;
      this.available = available;
      this.activation = activation;
    }
  }

  private State scan() {
    List<String> preloadNames = options.preload != null ? options.preload : List.of();
    Map<String, SkillInfo> info =
        scanSkills(
            store(),
            paths(),
            options.skillPaths != null && !options.skillPaths.isEmpty(),
            preloadNames);
    if (info.isEmpty()) {
      return new State(true, List.of(), "", Map.of(), "", null);
    }
    logger.debug("skills middleware scanned: skills={}", sortedNames(info));

    Map<String, SkillInfo> preload = resolvePreload(info);
    ActivationSet act = new ActivationSet(toolName(SKILL_TOOL_NAME));
    String available = availableSkillsSentence(info);

    // Registering the activation tool with nothing left to activate would offer the model a tool
    // whose every input is a dead end, which happens when preload names every discovered skill.
    List<String> loadable = loadableNames(info, preload);
    String catalog = buildSkillsPrompt(info, loadable);

    List<Tool<?, ?>> tools = new ArrayList<>();
    if (!loadable.isEmpty()) {
      tools.add(newUseSkillTool(info, act, available));
    }
    if (options.allowResourceAccess) {
      tools.add(newReadSkillFileTool(info, available));
    }
    return new State(false, tools, catalog, preload, available, act);
  }

  /** Returns the directories to scan, falling back to the defaults. */
  List<String> paths() {
    if (options.skillPaths == null || options.skillPaths.isEmpty()) {
      return List.of(AGENTS_SKILLS_PATH, DEFAULT_SKILLS_PATH);
    }
    return options.skillPaths;
  }

  /** Returns where skills are read from. */
  SkillStore store() {
    if (options.skillFs != null) {
      return new FsStore(options.skillFs);
    }
    return new DiskStore();
  }

  private String toolName(String suffix) {
    String prefix = options.toolNamePrefix != null ? options.toolNamePrefix : "";
    return prefix + suffix;
  }

  private Map<String, SkillInfo> resolvePreload(Map<String, SkillInfo> info) {
    if (options.preload == null || options.preload.isEmpty()) {
      return Map.of();
    }
    Map<String, SkillInfo> preload = new HashMap<>();
    for (String name : options.preload) {
      SkillInfo si = info.get(name);
      if (si == null) {
        logger.warn(
            "preloaded skill not found, ignoring: skill={} available={}", name, sortedNames(info));
        continue;
      }
      preload.put(name, si);
    }
    return preload;
  }

  // ── Tools ───────────────────────────────────────────────────────────────────

  /** Input to the activation tool. */
  static final class UseSkillInput {
    @JsonProperty("skillName")
    @JsonPropertyDescription("The name of the skill to use, exactly as listed in <skills>.")
    public String skillName;

    public UseSkillInput() {}
  }

  /** Input to the bundled-resource reader. */
  static final class ReadSkillFileInput {
    @JsonProperty("skillName")
    @JsonPropertyDescription(
        "Name of the skill that bundles the file, exactly as listed in <skills>.")
    public String skillName;

    @JsonProperty("filePath")
    @JsonPropertyDescription(
        "Path to the file, relative to the skill directory (for example \"references/api.md\").")
    public String filePath;

    public ReadSkillFileInput() {}
  }

  private static Map<String, Object> stringProperty(String description) {
    Map<String, Object> prop = new LinkedHashMap<>();
    prop.put("type", "string");
    prop.put("description", description);
    return prop;
  }

  private static Map<String, Object> objectSchema(Map<String, Object> properties) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", new ArrayList<>(properties.keySet()));
    schema.put("additionalProperties", false);
    return schema;
  }

  private Tool<UseSkillInput, String> newUseSkillTool(
      Map<String, SkillInfo> info, ActivationSet act, String available) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "skillName",
        stringProperty("The name of the skill to use, exactly as listed in <skills>."));
    return Tool.<UseSkillInput, String>builder()
        .name(toolName(SKILL_TOOL_NAME))
        .description("Load a skill's instructions by name.")
        .inputSchema(objectSchema(props))
        .outputSchema(Map.of("type", "string"))
        .inputClass(UseSkillInput.class)
        .handler(
            (ctx, in) -> {
              String requested = in != null && in.skillName != null ? in.skillName : "";
              SkillInfo si = lookupSkill(info, requested);
              if (si == null) {
                return unknownSkillMessage(requested, available);
              }
              if (!act.claim(si.name)) {
                return String.format(SKILL_ALREADY_LOADED_STUB, goQuote(si.name));
              }
              String content;
              try {
                content = renderSkill(si);
              } catch (IOException e) {
                act.release(si.name);
                throw new SkillToolException(e.getMessage(), e);
              } catch (RuntimeException e) {
                act.release(si.name);
                throw e;
              }
              pendingActivation.set(activationMetadata(si.name));
              return content;
            })
        .build();
  }

  private Tool<ReadSkillFileInput, String> newReadSkillFileTool(
      Map<String, SkillInfo> info, String available) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "skillName",
        stringProperty("Name of the skill that bundles the file, exactly as listed in <skills>."));
    props.put(
        "filePath",
        stringProperty(
            "Path to the file, relative to the skill directory (for example"
                + " \"references/api.md\")."));
    return Tool.<ReadSkillFileInput, String>builder()
        .name(toolName(SKILL_RESOURCE_TOOL_NAME))
        .description(
            "Read a file bundled inside a skill directory, such as a reference document or a"
                + " script.")
        .inputSchema(objectSchema(props))
        .outputSchema(Map.of("type", "string"))
        .inputClass(ReadSkillFileInput.class)
        .handler(
            (ctx, in) -> {
              String skillName = in != null && in.skillName != null ? in.skillName : "";
              String filePath = in != null && in.filePath != null ? in.filePath : "";
              // An unknown name is an answer, not a failure, the same as for use_skill.
              SkillInfo si = lookupSkill(info, skillName);
              if (si == null) {
                return unknownSkillMessage(skillName, available);
              }
              if (goTrimSpace(filePath).isEmpty()) {
                throw new SkillToolException("filePath is required", null);
              }
              String rel = normalizeRel(filePath);
              if (hiddenPath(rel)) {
                throw new SkillToolException(
                    rel
                        + " is not readable: skills expose no path with a segment starting with"
                        + " \".\"",
                    null);
              }
              byte[] data;
              try {
                data = si.store.readResource(si.dir, rel);
              } catch (IOException e) {
                throw new SkillToolException(
                    "read "
                        + goQuote(rel)
                        + " from skill "
                        + goQuote(skillName)
                        + ": "
                        + e.getMessage(),
                    e);
              }
              // The result is a string, so bytes that are not UTF-8 would reach the model as
              // replacement characters. Saying what the file is costs a line instead.
              String text = decodeUtf8(data);
              if (text == null) {
                return catalogText(rel)
                    + " is a binary file ("
                    + data.length
                    + " bytes); "
                    + toolName(SKILL_RESOURCE_TOOL_NAME)
                    + " returns text files only.";
              }
              return text;
            })
        .build();
  }

  /** A skill tool failure whose message is reported to the model. */
  static final class SkillToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SkillToolException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private String renderSkill(SkillInfo si) throws IOException {
    byte[] data = si.store.readSkillFile(si.path);
    return skillContent(si, data);
  }

  // An activation and a preload both go through this, so the two deliver the same content.
  private String skillContent(SkillInfo si, byte[] body) {
    String resources = "";
    if (options.allowResourceAccess) {
      resources = listSkillResources(si.store, si.dir, toolName(SKILL_RESOURCE_TOOL_NAME));
    }
    return wrapSkillContent(si, new String(body, StandardCharsets.UTF_8), resources);
  }

  /** Returns the metadata stamped on a part carrying the instructions of the named skill. */
  Map<String, Object> activationMetadata(String name) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put(SKILL_ACTIVATION_METADATA_KEY, name);
    meta.put(SKILLS_TOOL_METADATA_KEY, toolName(SKILL_TOOL_NAME));
    return meta;
  }

  /**
   * The per-call view of which skills are already loaded into the conversation by the instance
   * whose activation tool is named {@code tool}. The check-and-set happens under one lock, so two
   * calls for the same skill cannot both deliver the body.
   */
  static final class ActivationSet {
    private final String tool;
    private Set<String> loaded = new HashSet<>();

    ActivationSet(String tool) {
      this.tool = tool;
    }

    synchronized boolean claim(String name) {
      return loaded.add(name);
    }

    synchronized void release(String name) {
      loaded.remove(name);
    }

    // Rebuilds the set from a turn's messages, so it never reports a skill as loaded after context
    // management has dropped the part carrying it.
    synchronized void reset(List<Message> msgs) {
      loaded = activatedSkills(msgs, tool);
    }
  }

  /**
   * Resolves a model-supplied skill name. The catalog prints each name folded to one line, so a
   * folded spelling is accepted when it identifies exactly one skill.
   */
  static SkillInfo lookupSkill(Map<String, SkillInfo> info, String name) {
    SkillInfo exact = info.get(name);
    if (exact != null) {
      return exact;
    }
    SkillInfo match = null;
    for (SkillInfo si : info.values()) {
      if (!catalogText(si.name).equals(name)) {
        continue;
      }
      if (match != null) {
        // Two skills share a folded form. Guessing between them would be worse than the
        // unknown-skill reply, which lists both.
        return null;
      }
      match = si;
    }
    return match;
  }

  static String unknownSkillMessage(String name, String available) {
    return "Unknown skill " + goQuote(catalogText(name)) + ". " + available;
  }

  static String availableSkillsSentence(Map<String, SkillInfo> info) {
    List<String> quoted = new ArrayList<>();
    for (String n : sortedNames(info)) {
      quoted.add(goQuote(catalogText(n)));
    }
    return "Available skills: " + String.join(", ", quoted) + ".";
  }

  /**
   * Reports whether any segment of the slash-separated path starts with ".". The resource listing
   * leaves such entries out, and the reader refuses them to match.
   */
  static boolean hiddenPath(String rel) {
    for (String seg : rel.split("/", -1)) {
      if (seg.startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  static String normalizeRel(String p) {
    p = goTrimSpace(p);
    if (File.separatorChar != '/') {
      p = p.replace(File.separatorChar, '/');
    }
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

  private static String decodeUtf8(byte[] data) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  // ── Stores ──────────────────────────────────────────────────────────────────

  /** A directory entry, with attributes read without following a symbolic link. */
  static final class DirEntry {
    final String name;
    final BasicFileAttributes attrs;

    DirEntry(String name, BasicFileAttributes attrs) {
      this.name = name;
      this.attrs = attrs;
    }

    boolean isDir() {
      return attrs != null && attrs.isDirectory();
    }

    boolean isSymlink() {
      return attrs != null && attrs.isSymbolicLink();
    }

    boolean isRegular() {
      return attrs != null && attrs.isRegularFile();
    }
  }

  /**
   * Where skills are read from. Names are in the store's own form: host paths for {@link
   * DiskStore}, slash-separated paths for {@link FsStore}.
   */
  interface SkillStore {
    /** Turns a skillPaths entry into the name the other methods take. */
    String resolve(String p) throws IOException;

    String join(String... elem);

    List<DirEntry> readDir(String name) throws IOException;

    /** Stats a name, following symbolic links. */
    BasicFileAttributes stat(String name) throws IOException;

    /**
     * Reads a SKILL.md, refusing a symbolic link, anything that is not a regular file, and anything
     * over the size limit.
     */
    byte[] readSkillFile(String name) throws IOException;

    /** Reads rel, a slash-separated path, from the skill directory dir, confined to it. */
    byte[] readResource(String dir, String rel) throws IOException;

    /** Returns the skill directory as a path to walk. */
    Path openDir(String dir) throws IOException;

    /**
     * Reports whether entry, inside the walked root, is a symbolic link that resolves to a regular
     * file the reader would accept.
     */
    boolean linksToRegularFile(Path root, Path entry);

    boolean sameDir(String a, String b);

    /** Reports whether names are paths on the host, which other tools can open. */
    boolean hostPaths();
  }

  /** Reads skills from the default file system, guarding against files swapped mid-read. */
  static final class DiskStore implements SkillStore {

    @Override
    public String resolve(String p) {
      return Paths.get(p).toAbsolutePath().normalize().toString();
    }

    @Override
    public String join(String... elem) {
      List<String> parts = new ArrayList<>();
      for (String e : elem) {
        if (e != null && !e.isEmpty()) {
          parts.add(e);
        }
      }
      if (parts.isEmpty()) {
        return "";
      }
      Path p = Paths.get(parts.get(0), parts.subList(1, parts.size()).toArray(new String[0]));
      String s = p.normalize().toString();
      return s.isEmpty() ? "." : s;
    }

    @Override
    public List<DirEntry> readDir(String name) throws IOException {
      return listDir(Paths.get(name), name, true);
    }

    @Override
    public BasicFileAttributes stat(String name) throws IOException {
      return statPath(Paths.get(name), name, true, "stat");
    }

    @Override
    public byte[] readSkillFile(String name) throws IOException {
      return Skills.readSkillFile(Paths.get(name));
    }

    @Override
    public byte[] readResource(String dir, String rel) throws IOException {
      return readSkillResource(Paths.get(dir), rel);
    }

    @Override
    public Path openDir(String dir) throws IOException {
      Path p = Paths.get(dir);
      statPath(p, dir, true, "open");
      return p;
    }

    @Override
    public boolean linksToRegularFile(Path root, Path entry) {
      try {
        Path rootReal = root.toRealPath();
        Path real = entry.toRealPath();
        return real.startsWith(rootReal) && Files.isRegularFile(real);
      } catch (IOException | RuntimeException e) {
        return false;
      }
    }

    @Override
    public boolean sameDir(String a, String b) {
      if (a.equals(b)) {
        return true;
      }
      try {
        return Files.isSameFile(Paths.get(a), Paths.get(b));
      } catch (IOException | RuntimeException e) {
        return false;
      }
    }

    @Override
    public boolean hostPaths() {
      return true;
    }
  }

  /**
   * Reads skills from a directory on any {@link java.nio.file.FileSystem}, such as a zip or jar
   * file system. Names are slash-separated paths relative to the base directory; ".." and absolute
   * paths are refused.
   */
  static final class FsStore implements SkillStore {
    private final Path base;

    FsStore(Path base) {
      this.base = base;
    }

    private Path toPath(String name) {
      return ".".equals(name) ? base : base.resolve(name);
    }

    @Override
    public String resolve(String p) throws IOException {
      String name = cleanPath(p.replace('\\', '/'));
      if (!validPath(name)) {
        throw new IOException(
            goQuote(p)
                + " is not a path inside skillFs: use a slash-separated relative path without"
                + " \"..\"");
      }
      return name;
    }

    @Override
    public String join(String... elem) {
      return joinSlash(elem);
    }

    @Override
    public List<DirEntry> readDir(String name) throws IOException {
      return listDir(toPath(name), name, false);
    }

    @Override
    public BasicFileAttributes stat(String name) throws IOException {
      if (!validPath(name)) {
        throw new IOException("stat " + name + ": invalid argument");
      }
      return statPath(toPath(name), name, false, "stat");
    }

    @Override
    public byte[] readSkillFile(String name) throws IOException {
      if (!validPath(name)) {
        throw new IOException("lstat " + name + ": invalid argument");
      }
      Path p = toPath(name);
      BasicFileAttributes st = lstatPath(p, name, false);
      return readFsChecked(p, name, st, SKILL_MAX_BYTES);
    }

    @Override
    public byte[] readResource(String dir, String rel) throws IOException {
      if (!validPath(dir)) {
        throw new IOException("sub " + dir + ": invalid argument");
      }
      if (!validPath(rel)) {
        throw new IOException("stat " + rel + ": invalid argument");
      }
      Path p = toPath(joinSlash(dir, rel));
      BasicFileAttributes st = statPath(p, rel, false, "stat");
      return readFsChecked(p, rel, st, SKILL_RESOURCE_MAX_BYTES);
    }

    @Override
    public Path openDir(String dir) throws IOException {
      if (!validPath(dir)) {
        throw new IOException("sub " + dir + ": invalid argument");
      }
      return toPath(dir);
    }

    @Override
    public boolean linksToRegularFile(Path root, Path entry) {
      try {
        return Files.readAttributes(entry, BasicFileAttributes.class).isRegularFile();
      } catch (IOException | RuntimeException e) {
        return false;
      }
    }

    @Override
    public boolean sameDir(String a, String b) {
      return a.equals(b);
    }

    @Override
    public boolean hostPaths() {
      return false;
    }
  }

  private static List<DirEntry> listDir(Path dir, String name, boolean host) throws IOException {
    List<DirEntry> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path child : stream) {
        Path fileName = child.getFileName();
        if (fileName == null) {
          continue;
        }
        String entryName = fileName.toString();
        if (entryName.endsWith("/")) {
          entryName = entryName.substring(0, entryName.length() - 1);
        }
        BasicFileAttributes attrs;
        try {
          attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
          attrs = null;
        }
        entries.add(new DirEntry(entryName, attrs));
      }
    } catch (IOException e) {
      throw ioError("open", name, e, host);
    }
    entries.sort((a, b) -> compareCodePoints(a.name, b.name));
    return entries;
  }

  private static BasicFileAttributes statPath(Path p, String name, boolean host, String op)
      throws IOException {
    try {
      return Files.readAttributes(p, BasicFileAttributes.class);
    } catch (IOException e) {
      throw ioError(op, name, e, host);
    }
  }

  private static BasicFileAttributes lstatPath(Path p, String name, boolean host)
      throws IOException {
    try {
      return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      throw ioError("lstat", name, e, host);
    }
  }

  // Describes a file system failure in the form "<op> <name>: <reason>".
  private static IOException ioError(String op, String name, IOException e, boolean host) {
    String reason;
    if (e instanceof NoSuchFileException) {
      reason = host ? "no such file or directory" : "file does not exist";
    } else if (e instanceof AccessDeniedException) {
      reason = "permission denied";
    } else if (e instanceof NotDirectoryException) {
      reason = "not a directory";
    } else {
      reason = e.getMessage() != null ? e.getMessage() : e.toString();
    }
    return new IOException(op + " " + name + ": " + reason, e);
  }

  // Reads a file from a non-default store after st, its stat, passes checkReadable. The length is
  // checked again after the read.
  private static byte[] readFsChecked(Path p, String name, BasicFileAttributes st, long maxBytes)
      throws IOException {
    checkReadable(st, p, name, maxBytes);
    byte[] data;
    try {
      data = Files.readAllBytes(p);
    } catch (IOException e) {
      throw ioError("open", name, e, false);
    }
    if (data.length > maxBytes) {
      throw new IOException(name + " is over the " + maxBytes + " byte limit");
    }
    return data;
  }

  /**
   * Reads one bundled file, confined to dir: a path resolving outside it, including via "..", an
   * absolute path, or a symbolic link, is refused. A symbolic link to a file elsewhere in the same
   * skill is followed.
   */
  static byte[] readSkillResource(Path dir, String rel) throws IOException {
    Path rootReal;
    try {
      rootReal = dir.toRealPath();
    } catch (IOException e) {
      throw ioError("open", dir.toString(), e, true);
    }
    Path relPath = rootReal.getFileSystem().getPath(rel.replace('/', File.separatorChar));
    if (relPath.isAbsolute() || relPath.getRoot() != null) {
      throw new IOException("stat " + rel + ": path escapes from parent");
    }
    Path target = rootReal.resolve(relPath).normalize();
    if (!target.startsWith(rootReal)) {
      throw new IOException("stat " + rel + ": path escapes from parent");
    }
    Path real;
    try {
      real = target.toRealPath();
    } catch (IOException e) {
      throw ioError("stat", rel, e, true);
    }
    if (!real.startsWith(rootReal)) {
      throw new IOException("stat " + rel + ": path escapes from parent");
    }
    BasicFileAttributes st = statPath(target, rel, true, "stat");
    checkReadable(st, target, rel, SKILL_RESOURCE_MAX_BYTES);
    return readChecked(target, st, rel);
  }

  /**
   * Reads a SKILL.md, refusing anything over the size limit rather than truncating it. A symbolic
   * link is refused: it would name a file the skill author neither owns nor can write.
   */
  static byte[] readSkillFile(Path p) throws IOException {
    String name = p.toString();
    BasicFileAttributes st = lstatPath(p, name, true);
    checkReadable(st, p, name, SKILL_MAX_BYTES);
    return readChecked(p, st, name);
  }

  /**
   * Opens target and reads it whole, provided it is still the file described by st. Between the
   * check and the open the path can be replaced, by a symbolic link or a named pipe; comparing the
   * file at the path against the checked one refuses either substitute, and the type is checked
   * before the open since opening a named pipe blocks until a writer appears.
   */
  static byte[] readChecked(Path target, BasicFileAttributes st, String name) throws IOException {
    BasicFileAttributes current = statPath(target, name, true, "open");
    if (!sameFile(st, current) || !current.isRegularFile()) {
      throw new IOException(name + " changed while it was being opened");
    }
    long size = st.size();
    if (size > Integer.MAX_VALUE) {
      throw new IOException(name + " is " + size + " bytes, too large to read");
    }
    byte[] data;
    try (InputStream in = Files.newInputStream(target)) {
      data = in.readNBytes((int) size);
    } catch (IOException e) {
      throw ioError("read", name, e, true);
    }
    if (data.length < size) {
      throw new IOException("read " + name + ": unexpected EOF");
    }
    BasicFileAttributes after = statPath(target, name, true, "open");
    if (!sameFile(st, after)) {
      throw new IOException(name + " changed while it was being opened");
    }
    return data;
  }

  private static boolean sameFile(BasicFileAttributes a, BasicFileAttributes b) {
    Object ka = a.fileKey();
    Object kb = b.fileKey();
    if (ka != null && kb != null) {
      return ka.equals(kb);
    }
    return Objects.equals(a.creationTime(), b.creationTime())
        && Objects.equals(a.lastModifiedTime(), b.lastModifiedTime())
        && a.size() == b.size()
        && a.isRegularFile() == b.isRegularFile();
  }

  /**
   * The rule both skill readers apply before opening anything: a regular file within the byte
   * limit. The type check precedes the open, because opening a named pipe blocks until a writer
   * appears.
   */
  static void checkReadable(BasicFileAttributes st, Path p, String name, long maxBytes)
      throws IOException {
    if (st.isDirectory()) {
      throw new IOException(name + " is a directory, not a file");
    }
    if (!st.isRegularFile()) {
      throw new IOException(name + " is not a regular file (mode " + modeType(st, p) + ")");
    }
    if (st.size() > maxBytes) {
      throw new IOException(
          name + " is " + st.size() + " bytes, over the " + maxBytes + " byte limit");
    }
  }

  // Renders the type bits of a file mode the way "ls -l" style mode strings do.
  private static String modeType(BasicFileAttributes st, Path p) {
    if (st.isSymbolicLink()) {
      return "L---------";
    }
    if (UNIX_LIKE && p != null) {
      try {
        Object mode = Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        if (mode instanceof Integer) {
          int type = ((Integer) mode) & 0170000;
          switch (type) {
            case 0010000:
              return "p---------";
            case 0140000:
              return "S---------";
            case 0020000:
              return "Dc--------";
            case 0060000:
              return "D---------";
            default:
              break;
          }
        }
      } catch (IOException | RuntimeException e) {
        // Fall through to the generic description.
      }
    }
    return "?---------";
  }

  // ── Scanning ────────────────────────────────────────────────────────────────

  /** A discovered skill. */
  static final class SkillInfo {
    /** The directory name, which is what the catalog advertises and what the tools accept. */
    final String name;

    /** The root that bundled-file reads resolve against, as the store names it. */
    final String dir;

    /** The same directory as the skillPaths entry names it, which is what the model sees. */
    String shownDir;

    final String path;
    final String description;
    final SkillStore store;

    /** The SKILL.md bytes the scan read, kept only for the skills the scan was asked to retain. */
    final byte[] body;

    SkillInfo(
        String name, String dir, String path, String description, SkillStore store, byte[] body) {
      this.name = name;
      this.dir = dir;
      this.path = path;
      this.description = description;
      this.store = store;
      this.body = body;
    }
  }

  /**
   * Enumerates SKILL.md files under each path and returns the skills keyed by directory name. It
   * keeps the SKILL.md bytes of each skill named in retain. A skipped path is a warning when the
   * caller configured it explicitly and debug noise when it is only the unset default.
   */
  static Map<String, SkillInfo> scanSkills(
      SkillStore store, List<String> paths, boolean explicit, List<String> retain) {
    Map<String, SkillInfo> result = new HashMap<>();
    for (String p : paths) {
      String abs;
      try {
        abs = store.resolve(p);
      } catch (IOException | RuntimeException e) {
        skipped(explicit, p, e);
        continue;
      }
      List<DirEntry> entries;
      try {
        entries = store.readDir(abs);
      } catch (IOException | RuntimeException e) {
        skipped(explicit, abs, e);
        continue;
      }
      for (DirEntry entry : entries) {
        if (entry.name.startsWith(".") || !isDirEntry(store, abs, entry)) {
          continue;
        }
        SkillInfo si =
            readSkillDir(store, abs, entry.name, retain != null && retain.contains(entry.name));
        if (si == null) {
          continue;
        }
        si.shownDir = store.join(p, entry.name);
        SkillInfo prev = result.get(si.name);
        if (prev != null) {
          // An installer that links one copy of a skill into several agents' directories makes the
          // same skill appear twice. Nothing is shadowed then, so there is nothing to warn about.
          if (store.sameDir(prev.dir, si.dir)) {
            logger.debug(
                "skill found in more than one path through a symbolic link: skill={} paths={}",
                si.name,
                List.of(prev.path, si.path));
          } else {
            logger.warn(
                "skill name found in more than one path, the later path wins: skill={}"
                    + " shadowed={} using={}",
                si.name,
                prev.path,
                si.path);
          }
        }
        result.put(si.name, si);
      }
    }
    return result;
  }

  private static void skipped(boolean explicit, String p, Exception e) {
    if (explicit) {
      logger.warn("skills path could not be read, skipping: path={} error={}", p, e.getMessage());
    } else {
      logger.debug("skills path could not be read, skipping: path={} error={}", p, e.getMessage());
    }
  }

  // Reports whether entry is a directory or a symbolic link to one.
  private static boolean isDirEntry(SkillStore store, String parent, DirEntry entry) {
    if (entry.isDir()) {
      return true;
    }
    if (!entry.isSymlink()) {
      return false;
    }
    try {
      return store.stat(store.join(parent, entry.name)).isDirectory();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  // Loads one candidate skill directory. Returns null when it holds no usable SKILL.md.
  private static SkillInfo readSkillDir(
      SkillStore store, String parent, String name, boolean retain) {
    String dir = store.join(parent, name);
    List<DirEntry> entries;
    try {
      entries = store.readDir(dir);
    } catch (IOException | RuntimeException e) {
      logger.debug(
          "skill directory could not be read, skipping: path={} error={}", dir, e.getMessage());
      return null;
    }

    // Match SKILL.md case-exactly, and require a regular file: a symbolic link here would read a
    // file the skill author neither owns nor can write, and a named pipe would block the request.
    DirEntry found = null;
    for (DirEntry e : entries) {
      if (!e.name.equals(SKILL_FILE_NAME)) {
        continue;
      }
      if (!e.isRegular()) {
        logger.warn(
            "SKILL.md is not a regular file, skipping skill: path={} mode={}",
            store.join(dir, SKILL_FILE_NAME),
            e.attrs != null ? modeType(e.attrs, null) : "?---------");
        break;
      }
      found = e;
      break;
    }
    if (found == null) {
      warnNestedSkills(store, dir, entries);
      return null;
    }

    String skillMd = store.join(dir, SKILL_FILE_NAME);
    if (found.attrs.size() > SKILL_MAX_BYTES) {
      logger.warn(
          "SKILL.md is over the size limit, skipping skill: path={} bytes={} limit={}",
          skillMd,
          found.attrs.size(),
          SKILL_MAX_BYTES);
      return null;
    }
    byte[] data;
    try {
      data = store.readSkillFile(skillMd);
    } catch (IOException | RuntimeException e) {
      logger.warn(
          "SKILL.md could not be read, skipping skill: path={} error={}", skillMd, e.getMessage());
      return null;
    }

    Frontmatter fm = parseFrontmatter(new String(data, StandardCharsets.UTF_8));
    String desc = goTrimSpace(fm.description);
    if (fm.error != null && desc.isEmpty() && fm.name.isEmpty()) {
      logger.warn(
          "SKILL.md frontmatter could not be parsed; the skill is listed by name only: path={}"
              + " error={}",
          skillMd,
          fm.error.getMessage());
    } else if (fm.error != null) {
      logger.debug(
          "SKILL.md frontmatter is not valid YAML; fields were recovered leniently: path={}"
              + " error={}",
          skillMd,
          fm.error.getMessage());
    }
    if (desc.isEmpty()) {
      // The description is the entire signal the model routes on, but a skill without one is
      // loaded anyway for compatibility with plain-Markdown skill files, and reported here.
      logger.warn("skill has no description and will be listed by name only: path={}", skillMd);
    }

    validateSkillMetadata(name, fm, desc, skillMd);
    return new SkillInfo(name, dir, skillMd, desc, store, retain ? data : null);
  }

  // Reports specification violations that do not stop the skill from loading.
  private static void validateSkillMetadata(
      String dirName, Frontmatter fm, String desc, String skillMd) {
    if (!validSkillName(dirName)) {
      logger.debug(
          "skill directory name does not meet the Agent Skills naming rules (1-64 lowercase"
              + " letters, digits and single hyphens); the skill is loaded anyway: skill={}"
              + " path={}",
          dirName,
          skillMd);
    }
    String fmName = goTrimSpace(fm.name);
    if (fmName.isEmpty()) {
      logger.debug(
          "SKILL.md has no name field, which the specification requires; the directory name is"
              + " used: directory={} path={}",
          dirName,
          skillMd);
    } else if (!fmName.equals(dirName)) {
      logger.debug(
          "SKILL.md name does not match its directory name; the directory name is used: name={}"
              + " directory={} path={}",
          fmName,
          dirName,
          skillMd);
    }
    int n = desc.codePointCount(0, desc.length());
    if (n > SKILL_DESCRIPTION_MAX_RUNES) {
      logger.warn(
          "skill description is over the length the specification allows and will be clipped in"
              + " the skills catalog; the full text still loads with the skill: skill={} runes={}"
              + " limit={} path={}",
          dirName,
          n,
          SKILL_DESCRIPTION_MAX_RUNES,
          skillMd);
    }
  }

  // Reports a directory that holds no SKILL.md but does hold a subdirectory that does. Scanning is
  // one level deep, so those skills are invisible.
  private static void warnNestedSkills(SkillStore store, String dir, List<DirEntry> entries) {
    for (DirEntry e : entries) {
      if (!e.isDir() || e.name.startsWith(".")) {
        continue;
      }
      try {
        store.stat(store.join(dir, e.name, SKILL_FILE_NAME));
        logger.debug(
            "directory holds nested skills, which are not scanned; add it to skillPaths to expose"
                + " them: path={}",
            dir);
        return;
      } catch (IOException | RuntimeException ignored) {
        // Not a skill directory.
      }
    }
  }

  /**
   * Reports whether name meets the Agent Skills naming rules: 1 to 64 code points of lowercase
   * letters, digits, and hyphens, with no leading, trailing, or doubled hyphen.
   */
  static boolean validSkillName(String name) {
    int n = name.codePointCount(0, name.length());
    if (n == 0 || n > SKILL_NAME_MAX_RUNES) {
      return false;
    }
    if (name.startsWith("-") || name.endsWith("-") || name.contains("--")) {
      return false;
    }
    for (int i = 0; i < name.length(); ) {
      int cp = name.codePointAt(i);
      i += Character.charCount(cp);
      int type = Character.getType(cp);
      if (cp == '-' || type == Character.DECIMAL_DIGIT_NUMBER) {
        continue;
      }
      if (Character.isLetter(cp)
          && type != Character.UPPERCASE_LETTER
          && type != Character.TITLECASE_LETTER) {
        continue;
      }
      return false;
    }
    return true;
  }

  // ── Frontmatter ─────────────────────────────────────────────────────────────

  /** The name and description recovered from a SKILL.md, and the YAML error if any. */
  static final class Frontmatter {
    final String name;
    final String description;
    final Exception error;

    Frontmatter(String name, String description, Exception error) {
      this.name = name != null ? name : "";
      this.description = description != null ? description : "";
      this.error = error;
    }

    Frontmatter withError(Exception e) {
      return new Frontmatter(name, description, e);
    }
  }

  private static final Frontmatter EMPTY_FRONTMATTER = new Frontmatter("", "", null);

  /**
   * Extracts the YAML frontmatter fenced by "---" lines at the top of a SKILL.md.
   *
   * <p>The closing fence must be a line holding only "---". When the YAML does not parse, the block
   * is parsed again with its plain top-level values turned into block scalars. If that also fails,
   * name and description are recovered by scanning those lines directly. The first parse error is
   * returned whenever the block was not valid YAML.
   */
  static Frontmatter parseFrontmatter(String content) {
    String text = content.startsWith("﻿") ? content.substring(1) : content;
    if (!text.startsWith("---")) {
      return EMPTY_FRONTMATTER;
    }
    String rest = text.substring(3);
    // The opening fence runs to the end of its line; trailing spaces are tolerated.
    int nl = rest.indexOf('\n');
    if (nl < 0 || !trimRight(rest.substring(0, nl), " \t\r").isEmpty()) {
      return EMPTY_FRONTMATTER;
    }
    rest = rest.substring(nl + 1);

    String block = cutFrontmatterBlock(rest);
    if (block == null) {
      return EMPTY_FRONTMATTER;
    }
    try {
      return unmarshalFrontmatter(block);
    } catch (Exception err) {
      try {
        return unmarshalFrontmatter(blockScalarPlainValues(block)).withError(err);
      } catch (Exception ignored) {
        return scanFrontmatterLines(block).withError(err);
      }
    }
  }

  private static Frontmatter unmarshalFrontmatter(String block) throws IOException {
    JsonNode node = YAML.readTree(block);
    if (node == null || node.isMissingNode() || node.isNull()) {
      return EMPTY_FRONTMATTER;
    }
    if (!node.isObject()) {
      throw new IOException("frontmatter is not a mapping");
    }
    return new Frontmatter(scalar(node, "name"), scalar(node, "description"), null);
  }

  private static String scalar(JsonNode node, String field) throws IOException {
    JsonNode v = node.get(field);
    if (v == null || v.isNull() || v.isMissingNode()) {
      return "";
    }
    if (!v.isValueNode()) {
      throw new IOException("frontmatter field " + field + " is not a string");
    }
    return v.asText();
  }

  /**
   * Rewrites each plain top-level value in a frontmatter block as a folded block scalar, taking its
   * space-indented continuation lines with it. Quoted, flow, block, and empty values are left
   * untouched.
   */
  static String blockScalarPlainValues(String block) {
    String[] lines = block.split("\n", -1);
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = trimRight(lines[i], "\r");
      int colon = line.indexOf(':');
      String key = colon >= 0 ? line.substring(0, colon) : line;
      String value = colon >= 0 ? goTrimSpace(line.substring(colon + 1)) : "";
      if (colon < 0
          || key.isEmpty()
          || " \t#-".indexOf(key.charAt(0)) >= 0
          || value.isEmpty()
          || "\"'|>[{&*!#".indexOf(value.charAt(0)) >= 0) {
        b.append(line).append('\n');
        continue;
      }
      b.append(key).append(": >-\n  ").append(value).append('\n');
      while (i + 1 < lines.length) {
        String next = trimRight(lines[i + 1], "\r");
        if (!next.isEmpty() && !next.startsWith(" ")) {
          break;
        }
        i++;
        next = goTrimSpace(next);
        if (next.isEmpty()) {
          b.append('\n');
          continue;
        }
        b.append("  ").append(next).append('\n');
      }
    }
    return b.toString();
  }

  // Returns everything before the closing fence, or null when there is none.
  private static String cutFrontmatterBlock(String s) {
    int offset = 0;
    while (offset < s.length()) {
      int nl = s.indexOf('\n', offset);
      int end = nl >= 0 ? nl : s.length();
      String line = s.substring(offset, end);
      if (trimRight(line, " \t\r").equals("---")) {
        return s.substring(0, offset);
      }
      if (end == s.length()) {
        break;
      }
      offset = end + 1;
    }
    return null;
  }

  /**
   * Recovers name and description from frontmatter that is not valid YAML, taking each value
   * verbatim to the end of its line. A value that is only a block-scalar header is treated as
   * absent.
   */
  static Frontmatter scanFrontmatterLines(String block) {
    String name = "";
    String description = "";
    for (String line : block.split("\n", -1)) {
      line = trimRight(line, "\r");
      if (line.startsWith("name:") && name.isEmpty()) {
        name = scanValue(line.substring("name:".length()));
        continue;
      }
      if (line.startsWith("description:") && description.isEmpty()) {
        description = scanValue(line.substring("description:".length()));
      }
    }
    return new Frontmatter(name, description, null);
  }

  private static String scanValue(String v) {
    v = goTrimSpace(v);
    return isBlockScalarHeader(v) ? "" : v;
  }

  // Reports whether s is a YAML block-scalar indicator on its own.
  static boolean isBlockScalarHeader(String s) {
    if (s.isEmpty() || (s.charAt(0) != '|' && s.charAt(0) != '>')) {
      return false;
    }
    for (int i = 1; i < s.length(); ) {
      int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      if (cp != '+' && cp != '-' && Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER) {
        return false;
      }
    }
    return true;
  }

  // ── Rendering ───────────────────────────────────────────────────────────────

  /**
   * Renders the catalog listing the given skills, which are already sorted and exclude preloaded
   * ones.
   */
  private String buildSkillsPrompt(Map<String, SkillInfo> info, List<String> names) {
    if (names.isEmpty()) {
      return "";
    }
    StringBuilder b = new StringBuilder();
    b.append("<skills>\n");
    b.append(
        "You have access to a library of skills that serve as specialized"
            + " instructions/personas.\n");
    b.append("Strongly prefer to use them when working on anything related to them.\n");
    b.append("Only use them once to load the context.\n");
    b.append("Call the ")
        .append(toolName(SKILL_TOOL_NAME))
        .append(" tool with a skill's name to load its instructions.\n");
    b.append("Here are the available skills:\n");
    for (String name : names) {
      // Clip before escaping: the bound is on the author's text.
      String desc = catalogText(clipRunes(info.get(name).description, SKILL_DESCRIPTION_MAX_RUNES));
      if (desc.isEmpty()) {
        b.append(" - ").append(catalogText(name)).append('\n');
        continue;
      }
      b.append(" - ").append(catalogText(name)).append(" - ").append(desc).append('\n');
    }
    b.append("</skills>");
    return b.toString();
  }

  // Truncates s to max code points, marking that it was cut.
  static String clipRunes(String s, int max) {
    if (s.codePointCount(0, s.length()) <= max) {
      return s;
    }
    int end = s.offsetByCodePoints(0, max);
    return trimRight(s.substring(0, end), " ") + "...";
  }

  // Renders an activated skill. The full file is returned, frontmatter included.
  private static String wrapSkillContent(SkillInfo si, String body, String resources) {
    StringBuilder b = new StringBuilder();
    b.append("<skill_content name=\"")
        .append(attrText(si.name))
        .append("\" path=\"")
        .append(attrText(si.shownDir))
        .append("\">\n");
    b.append(body);
    if (!body.endsWith("\n")) {
      b.append('\n');
    }
    if (si.store.hostPaths()) {
      b.append("\nRelative paths in this skill resolve against ")
          .append(catalogText(si.shownDir))
          .append('\n');
    }
    b.append(resources);
    b.append("</skill_content>");
    return b.toString();
  }

  /**
   * Enumerates the files a skill bundles, so the model knows they exist without any of them being
   * read. The listing admits exactly what the reader does.
   */
  static String listSkillResources(SkillStore store, String dir, String toolName) {
    Path root;
    try {
      root = store.openDir(dir);
    } catch (IOException | RuntimeException e) {
      logger.debug("skill resources could not be listed: path={} error={}", dir, e.getMessage());
      return "";
    }
    List<String> files = new ArrayList<>();
    boolean[] truncated = {false};
    walkResources(store, dir, root, root, "", files, truncated);
    if (files.isEmpty()) {
      return "";
    }
    files.sort(Skills::compareCodePoints);

    StringBuilder b = new StringBuilder();
    b.append("\n<skill_resources>\n");
    b.append("Paths are relative to the skill directory above; read them with the ")
        .append(toolName)
        .append(" tool.\n");
    for (String f : files) {
      b.append(" - ").append(catalogText(f)).append('\n');
    }
    if (truncated[0]) {
      b.append(" (listing truncated at ").append(SKILL_RESOURCE_LIST_MAX).append(" files)\n");
    }
    b.append("</skill_resources>\n");
    return b.toString();
  }

  // Walks one directory in lexical order. Returns false once the listing cap stops the walk.
  private static boolean walkResources(
      SkillStore store,
      String dir,
      Path root,
      Path current,
      String prefix,
      List<String> files,
      boolean[] truncated) {
    List<DirEntry> entries;
    try {
      entries = listDir(current, prefix.isEmpty() ? "." : prefix, store.hostPaths());
    } catch (IOException e) {
      logger.debug(
          "skill resource could not be listed, skipping: path={} error={}",
          store.join(dir, prefix.isEmpty() ? "." : prefix),
          e.getMessage());
      return true;
    }
    for (DirEntry d : entries) {
      String rel = prefix.isEmpty() ? d.name : prefix + "/" + d.name;
      if (d.attrs == null) {
        logger.debug("skill resource could not be listed, skipping: path={}", store.join(dir, rel));
        continue;
      }
      if (d.name.startsWith(".")) {
        continue;
      }
      // Installed dependencies are not something the skill author wrote for the model, and would
      // otherwise fill the listing cap before references/ and scripts/ are reached.
      if (d.isDir() && d.name.equals("node_modules")) {
        continue;
      }
      if (d.isDir()) {
        if (countSlashes(rel) + 1 >= SKILL_RESOURCE_MAX_DEPTH) {
          continue;
        }
        if (!walkResources(store, dir, root, current.resolve(d.name), rel, files, truncated)) {
          return false;
        }
        continue;
      }
      if (rel.equals(SKILL_FILE_NAME)) {
        continue;
      }
      // Listing a path is an invitation to read it: a regular file, or a symbolic link that
      // resolves to one without leaving the skill directory.
      if (!d.isRegular()
          && !(d.isSymlink() && store.linksToRegularFile(root, current.resolve(d.name)))) {
        continue;
      }
      if (files.size() >= SKILL_RESOURCE_LIST_MAX) {
        truncated[0] = true;
        return false;
      }
      files.add(rel);
    }
    return true;
  }

  private static int countSlashes(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '/') {
        n++;
      }
    }
    return n;
  }

  // ── Conversation ────────────────────────────────────────────────────────────

  /**
   * Returns the skills whose instructions the instance with activation tool {@code tool} placed in
   * the messages. A part loaded by another instance does not count.
   */
  static Set<String> activatedSkills(List<Message> msgs, String tool) {
    Set<String> activated = new HashSet<>();
    if (msgs == null) {
      return activated;
    }
    for (Message msg : msgs) {
      if (msg == null || msg.getContent() == null) {
        continue;
      }
      for (Part part : msg.getContent()) {
        if (part == null || part.getMetadata() == null) {
          continue;
        }
        Object name = part.getMetadata().get(SKILL_ACTIVATION_METADATA_KEY);
        if (name instanceof String
            && !((String) name).isEmpty()
            && ownsPart(part.getMetadata(), tool)) {
          activated.add((String) name);
        }
      }
    }
    return activated;
  }

  /**
   * Returns a copy of the messages carrying the skills catalog and the instructions of any
   * preloaded skill that is not already present.
   */
  List<Message> injectSkills(
      List<Message> messages, String catalog, Map<String, SkillInfo> preload) {
    List<Message> msgs = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    if (!catalog.isEmpty()) {
      injectCatalogPart(msgs, catalog);
    }
    if (preload.isEmpty()) {
      return msgs;
    }
    Set<String> present = activatedSkills(msgs, toolName(SKILL_TOOL_NAME));
    List<Part> parts = new ArrayList<>();
    List<String> names = new ArrayList<>(preload.keySet());
    names.sort(Skills::compareCodePoints);
    for (String name : names) {
      if (present.contains(name)) {
        continue;
      }
      SkillInfo si = preload.get(name);
      Part p = Part.text(skillContent(si, si.body));
      p.setMetadata(activationMetadata(name));
      parts.add(p);
    }
    if (!parts.isEmpty()) {
      appendToSystemMessage(msgs, parts);
    }
    return msgs;
  }

  // Places the catalog in this instance's marked part, replacing an existing one in place, or
  // adding a new part when there is none.
  private void injectCatalogPart(List<Message> msgs, String catalog) {
    String tool = toolName(SKILL_TOOL_NAME);
    for (int i = 0; i < msgs.size(); i++) {
      Message msg = msgs.get(i);
      if (msg == null || msg.getContent() == null) {
        continue;
      }
      List<Part> content = msg.getContent();
      for (int j = 0; j < content.size(); j++) {
        Part part = content.get(j);
        if (part == null || !part.isText() || !isCatalogPart(part, tool)) {
          continue;
        }
        if (catalog.equals(part.getText())) {
          return;
        }
        Message copy = copyMessage(msg);
        copy.getContent().set(j, newSkillsPart(catalog));
        msgs.set(i, copy);
        return;
      }
    }
    appendToSystemMessage(msgs, List.of(newSkillsPart(catalog)));
  }

  // Adds parts to the system message, creating one at the front of the conversation when there is
  // none.
  private static void appendToSystemMessage(List<Message> msgs, List<Part> parts) {
    for (int i = 0; i < msgs.size(); i++) {
      Message msg = msgs.get(i);
      if (msg == null || msg.getRole() != Role.SYSTEM) {
        continue;
      }
      Message copy = copyMessage(msg);
      copy.getContent().addAll(parts);
      msgs.set(i, copy);
      return;
    }
    msgs.add(0, new Message(Role.SYSTEM, new ArrayList<>(parts)));
  }

  private static Message copyMessage(Message msg) {
    Message copy =
        new Message(
            msg.getRole(),
            msg.getContent() != null ? new ArrayList<>(msg.getContent()) : new ArrayList<>());
    if (msg.getMetadata() != null) {
      copy.setMetadata(new HashMap<>(msg.getMetadata()));
    }
    return copy;
  }

  /** Reports whether part is the skills catalog of the instance whose activation tool is tool. */
  static boolean isCatalogPart(Part part, String tool) {
    Map<String, Object> meta = part.getMetadata();
    if (meta == null) {
      return false;
    }
    return Boolean.TRUE.equals(meta.get(SKILLS_MARKER)) && ownsPart(meta, tool);
  }

  /**
   * Reports whether a part marked by this middleware belongs to the instance whose activation tool
   * is tool. A part without an owner is claimed by any instance, so a catalog written by a runtime
   * that does not record the owner is refreshed rather than duplicated.
   */
  static boolean ownsPart(Map<String, Object> meta, String tool) {
    Object owner = meta.get(SKILLS_TOOL_METADATA_KEY);
    return !(owner instanceof String) || owner.equals(tool);
  }

  private Part newSkillsPart(String text) {
    Part p = Part.text(text);
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put(SKILLS_MARKER, true);
    meta.put(SKILLS_TOOL_METADATA_KEY, toolName(SKILL_TOOL_NAME));
    p.setMetadata(meta);
    return p;
  }

  static List<String> sortedNames(Map<String, SkillInfo> info) {
    List<String> names = new ArrayList<>(info.keySet());
    names.sort(Skills::compareCodePoints);
    return names;
  }

  // Returns the skills the model may activate: everything discovered, less anything preloaded.
  private static List<String> loadableNames(
      Map<String, SkillInfo> info, Map<String, SkillInfo> preload) {
    List<String> names = new ArrayList<>();
    for (String name : sortedNames(info)) {
      if (!preload.containsKey(name)) {
        names.add(name);
      }
    }
    return names;
  }

  // ── Text helpers ────────────────────────────────────────────────────────────

  /**
   * Prepares author-supplied text for a line the model reads: whitespace runs are folded to single
   * spaces so it stays one line, and a tag-like "<" is escaped so it cannot close the block it sits
   * in.
   */
  static String catalogText(String s) {
    return escapeMarkup(String.join(" ", fields(s)));
  }

  /** Escapes each "<" that begins a tag, leaving other uses intact. */
  static String escapeMarkup(String s) {
    if (s.indexOf('<') < 0) {
      return s;
    }
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '<') {
        b.append(c);
        continue;
      }
      int j = i + 1;
      if (j < s.length() && s.charAt(j) == '/') {
        j++;
      }
      if (j < s.length() && isAsciiLetter(s.charAt(j))) {
        b.append("&lt;");
        continue;
      }
      b.append(c);
    }
    return b.toString();
  }

  /** Escapes text placed in a quoted attribute. */
  static String attrText(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&':
          b.append("&amp;");
          break;
        case '<':
          b.append("&lt;");
          break;
        case '>':
          b.append("&gt;");
          break;
        case '"':
          b.append("&quot;");
          break;
        case '\n':
        case '\r':
          b.append(' ');
          break;
        default:
          b.append(c);
      }
    }
    return b.toString();
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  // Unicode White_Space, matching the set that folding and trimming treat as space.
  static boolean isSpace(int cp) {
    switch (cp) {
      case '\t':
      case '\n':
      case 0x0B:
      case '\f':
      case '\r':
      case ' ':
      case 0x85:
      case 0xA0:
        return true;
      default:
        if (cp < 0x100) {
          return false;
        }
        int type = Character.getType(cp);
        return type == Character.SPACE_SEPARATOR
            || type == Character.LINE_SEPARATOR
            || type == Character.PARAGRAPH_SEPARATOR;
    }
  }

  private static List<String> fields(String s) {
    List<String> out = new ArrayList<>();
    int start = -1;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      int len = Character.charCount(cp);
      if (isSpace(cp)) {
        if (start >= 0) {
          out.add(s.substring(start, i));
          start = -1;
        }
      } else if (start < 0) {
        start = i;
      }
      i += len;
    }
    if (start >= 0) {
      out.add(s.substring(start));
    }
    return out;
  }

  static String goTrimSpace(String s) {
    int start = 0;
    int end = s.length();
    while (start < end) {
      int cp = s.codePointAt(start);
      if (!isSpace(cp)) {
        break;
      }
      start += Character.charCount(cp);
    }
    while (end > start) {
      int cp = s.codePointBefore(end);
      if (!isSpace(cp)) {
        break;
      }
      end -= Character.charCount(cp);
    }
    return s.substring(start, end);
  }

  private static String trimRight(String s, String cutset) {
    int end = s.length();
    while (end > 0 && cutset.indexOf(s.charAt(end - 1)) >= 0) {
      end--;
    }
    return s.substring(0, end);
  }

  /** Quotes s as a double-quoted string literal with escapes for non-printable characters. */
  static String goQuote(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2);
    b.append('"');
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      switch (cp) {
        case '"':
          b.append("\\\"");
          continue;
        case '\\':
          b.append("\\\\");
          continue;
        case 0x07:
          b.append("\\a");
          continue;
        case '\b':
          b.append("\\b");
          continue;
        case '\f':
          b.append("\\f");
          continue;
        case '\n':
          b.append("\\n");
          continue;
        case '\r':
          b.append("\\r");
          continue;
        case '\t':
          b.append("\\t");
          continue;
        case 0x0B:
          b.append("\\v");
          continue;
        default:
          break;
      }
      if (isPrint(cp)) {
        b.appendCodePoint(cp);
      } else if (cp < 0x80) {
        b.append(String.format("\\x%02x", cp));
      } else if (cp < 0x10000) {
        b.append(String.format("\\u%04x", cp));
      } else {
        b.append(String.format("\\U%08x", cp));
      }
    }
    b.append('"');
    return b.toString();
  }

  private static boolean isPrint(int cp) {
    if (cp == ' ') {
      return true;
    }
    switch (Character.getType(cp)) {
      case Character.UPPERCASE_LETTER:
      case Character.LOWERCASE_LETTER:
      case Character.TITLECASE_LETTER:
      case Character.MODIFIER_LETTER:
      case Character.OTHER_LETTER:
      case Character.NON_SPACING_MARK:
      case Character.ENCLOSING_MARK:
      case Character.COMBINING_SPACING_MARK:
      case Character.DECIMAL_DIGIT_NUMBER:
      case Character.LETTER_NUMBER:
      case Character.OTHER_NUMBER:
      case Character.CONNECTOR_PUNCTUATION:
      case Character.DASH_PUNCTUATION:
      case Character.START_PUNCTUATION:
      case Character.END_PUNCTUATION:
      case Character.INITIAL_QUOTE_PUNCTUATION:
      case Character.FINAL_QUOTE_PUNCTUATION:
      case Character.OTHER_PUNCTUATION:
      case Character.MATH_SYMBOL:
      case Character.CURRENCY_SYMBOL:
      case Character.MODIFIER_SYMBOL:
      case Character.OTHER_SYMBOL:
        return true;
      default:
        return false;
    }
  }

  // Orders strings by code point, which is the same as ordering their UTF-8 bytes.
  static int compareCodePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int ca = a.codePointAt(i);
      int cb = b.codePointAt(j);
      if (ca != cb) {
        return Integer.compare(ca, cb);
      }
      i += Character.charCount(ca);
      j += Character.charCount(cb);
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  // ── Slash paths ─────────────────────────────────────────────────────────────

  /** Returns the shortest slash-separated path equivalent to p, by purely lexical processing. */
  static String cleanPath(String p) {
    if (p.isEmpty()) {
      return ".";
    }
    boolean rooted = p.charAt(0) == '/';
    List<String> out = new ArrayList<>();
    for (String seg : p.split("/", -1)) {
      if (seg.isEmpty() || seg.equals(".")) {
        continue;
      }
      if (seg.equals("..")) {
        if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
          out.remove(out.size() - 1);
        } else if (!rooted) {
          out.add("..");
        }
        continue;
      }
      out.add(seg);
    }
    String joined = String.join("/", out);
    if (rooted) {
      return "/" + joined;
    }
    return joined.isEmpty() ? "." : joined;
  }

  /**
   * Reports whether name is a valid path inside a skill file system: unrooted, slash-separated,
   * with no empty, "." or ".." element, or exactly ".".
   */
  static boolean validPath(String name) {
    if (name.equals(".")) {
      return true;
    }
    if (name.isEmpty()) {
      return false;
    }
    for (String seg : name.split("/", -1)) {
      if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
        return false;
      }
    }
    return true;
  }

  private static String joinSlash(String... elem) {
    List<String> parts = new ArrayList<>();
    for (String e : elem) {
      if (e != null && !e.isEmpty()) {
        parts.add(e);
      }
    }
    if (parts.isEmpty()) {
      return "";
    }
    return cleanPath(String.join("/", parts));
  }

  // ── Configuration ───────────────────────────────────────────────────────────

  /** Configuration for {@link Skills}. */
  public static class Options {

    /**
     * Directories scanned for skills. Each direct subdirectory containing a {@code SKILL.md} file
     * is exposed as a skill; scanning is one level deep. A symbolic link to a skill directory is
     * followed, but a {@code SKILL.md} that is itself a symbolic link is not. Relative paths
     * resolve against the process working directory (or inside {@link #skillFs} when it is set),
     * and a skill's directory is shown to the model the way its entry here names it, so a relative
     * entry keeps host paths out of the conversation.
     *
     * <p>When two paths hold a skill of the same name the later path wins, and the shadowing is
     * logged. Defaults to {@code [".agents/skills", "skills"]}.
     */
    @JsonProperty("skillPaths")
    @JsonPropertyDescription(
        "Directories scanned for skills. Each direct subdirectory containing a SKILL.md file is"
            + " exposed as a skill; scanning is one level deep. If two paths hold the same skill"
            + " name, the later path wins. Defaults to the \".agents/skills\" and \"skills\""
            + " directories.")
    public List<String> skillPaths;

    /**
     * Skills whose instructions are injected before the first model turn instead of waiting for the
     * model to call {@value Skills#SKILL_TOOL_NAME}. Use it when the application, rather than the
     * model, decides that a skill applies. Preloaded skills are left out of the catalog. A name
     * matching no discovered skill is logged and ignored.
     */
    @JsonProperty("preload")
    @JsonPropertyDescription(
        "Skills whose instructions are injected before the first model turn, without waiting for"
            + " the model to load them. Names matching no discovered skill are logged and"
            + " ignored.")
    public List<String> preload;

    /**
     * Registers {@value Skills#SKILL_RESOURCE_TOOL_NAME}, which reads files bundled inside a skill
     * directory, and appends a listing of those files to each activation. Reads are confined to the
     * one skill directory. Defaults to false: enabling it grants the model file access it does not
     * otherwise have.
     */
    @JsonProperty("allowResourceAccess")
    @JsonPropertyDescription(
        "Adds the read_skill_file tool and lists each skill's bundled files at activation. Reads"
            + " are confined to the individual skill directory. Defaults to false.")
    public boolean allowResourceAccess;

    /**
     * Prepended to each registered tool name. Use distinct prefixes when attaching more than one
     * Skills middleware to a call, or to avoid colliding with a caller-supplied tool of the same
     * name.
     */
    @JsonProperty("toolNamePrefix")
    @JsonPropertyDescription(
        "Prepended to each tool name. Use distinct prefixes when attaching multiple skills"
            + " middlewares to one call so their tool names do not collide.")
    public String toolNamePrefix;

    /**
     * When set, the directory that {@link #skillPaths} are read from, in place of the process
     * working directory. It can live on any {@link java.nio.file.FileSystem}, which lets an
     * application ship skills inside a jar or zip file:
     *
     * <pre>{@code
     * FileSystem zip = FileSystems.newFileSystem(Path.of("skills.zip"));
     * options.skillFs = zip.getPath("/");
     * }</pre>
     *
     * <p>{@link #skillPaths} are then slash-separated paths relative to this directory, and the
     * defaults apply unchanged. Paths containing ".." or starting with "/" are refused. A skill
     * read this way has no host path, so its activation does not tell the model where relative
     * paths resolve; its bundled files are reachable only through {@value
     * Skills#SKILL_RESOURCE_TOOL_NAME}.
     *
     * <p>Containment comes from that path validation. Symbolic links on the default file system are
     * followed out of the tree, so for a directory on disk prefer setting {@link #skillPaths} and
     * leaving this unset.
     *
     * <p>This field is not part of the JSON configuration.
     */
    @JsonIgnore public Path skillFs;

    /** Creates options with the defaults. */
    public Options() {}
  }

  /** Fluent builder for {@link Skills}. */
  public static final class Builder {
    private final Options options = new Options();

    private Builder() {}

    /**
     * Sets the directories scanned for skills.
     *
     * @param paths the directories
     * @return this builder
     */
    public Builder skillPaths(String... paths) {
      options.skillPaths = new ArrayList<>(List.of(paths));
      return this;
    }

    /**
     * Sets the directories scanned for skills.
     *
     * @param paths the directories
     * @return this builder
     */
    public Builder skillPaths(List<String> paths) {
      options.skillPaths = paths != null ? new ArrayList<>(paths) : null;
      return this;
    }

    /**
     * Sets the skills injected before the first model turn.
     *
     * @param names the skill names
     * @return this builder
     */
    public Builder preload(String... names) {
      options.preload = new ArrayList<>(List.of(names));
      return this;
    }

    /**
     * Enables the bundled-file reader tool.
     *
     * @param allow whether to allow resource access
     * @return this builder
     */
    public Builder allowResourceAccess(boolean allow) {
      options.allowResourceAccess = allow;
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
     * Sets the directory skill paths are read from.
     *
     * @param base the base directory, on any file system
     * @return this builder
     */
    public Builder skillFs(Path base) {
      options.skillFs = base;
      return this;
    }

    /**
     * Builds the middleware.
     *
     * @return the middleware
     */
    public Skills build() {
      return new Skills(options);
    }
  }
}
