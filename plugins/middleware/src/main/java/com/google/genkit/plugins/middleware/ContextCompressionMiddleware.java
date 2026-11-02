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

import static com.google.genkit.plugins.middleware.CompressionHistory.KEY;
import static com.google.genkit.plugins.middleware.CompressionHistory.ccMeta;
import static com.google.genkit.plugins.middleware.CompressionHistory.containsIdentity;
import static com.google.genkit.plugins.middleware.CompressionHistory.copyMessage;
import static com.google.genkit.plugins.middleware.CompressionHistory.estimatePartChars;
import static com.google.genkit.plugins.middleware.CompressionHistory.fields;
import static com.google.genkit.plugins.middleware.CompressionHistory.hasCompressionFlag;
import static com.google.genkit.plugins.middleware.CompressionHistory.hasMessageFlag;
import static com.google.genkit.plugins.middleware.CompressionHistory.isPinned;
import static com.google.genkit.plugins.middleware.CompressionHistory.keepStart;
import static com.google.genkit.plugins.middleware.CompressionHistory.marshalJson;
import static com.google.genkit.plugins.middleware.CompressionHistory.partsOf;
import static com.google.genkit.plugins.middleware.CompressionHistory.roleOf;
import static com.google.genkit.plugins.middleware.CompressionHistory.summarySplit;
import static com.google.genkit.plugins.middleware.CompressionHistory.toolResponseCharLength;
import static com.google.genkit.plugins.middleware.CompressionHistory.withCompressionMetadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.ai.Candidate;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Model;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.ai.middleware.ToolParams;
import com.google.genkit.ai.telemetry.ModelTelemetryHelper;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a conversation inside a context budget through long chats and agentic tool loops.
 *
 * <p><b>Experimental:</b> this middleware may change in any minor release.
 *
 * <p>Before each tool-loop iteration, compression runs when a trigger fires: the context exceeds
 * {@link Options#maxInputTokens}, measured by the input tokens the model reported for the previous
 * call (estimated from the messages before there is one), or the conversation holds more than
 * {@link Options#maxMessages} messages. The strategies run cheapest first:
 *
 * <ol>
 *   <li>{@link Options#deduplicateToolResponses} replaces older duplicate tool responses with a
 *       short notice.
 *   <li>{@link Options#toolResponses} truncates older tool responses, leaving the newest tool
 *       messages intact.
 *   <li>{@link Options#summarize} has a model fold older messages into a summary, unless the first
 *       two strategies already saved {@link Options#skipSummarizationThreshold} of the context and
 *       brought it under budget.
 *   <li>Older messages are dropped when nothing was summarized and the conversation still exceeds
 *       its message budget. A notice tells the model that context was removed.
 * </ol>
 *
 * <p>{@link Options#maxToolResponseChars} caps every tool response on every iteration, whether or
 * not a trigger fires. Leading system messages are always kept, and the kept messages never start
 * with a tool response separated from its request.
 *
 * <p>Two kinds of message are never summarized or dropped: the scaffolding an agent's prompt
 * renders on every turn, and the message that carries injected output-format instructions. When a
 * compaction covers one, the message stays in the model's view, after the system messages and
 * before the summary.
 *
 * <p><b>History.</b> The history the caller gets back ({@link ModelResponse#getMessages()}) keeps
 * every original message, and the model receives a compressed view of it. The compression is
 * recorded in message metadata under the {@code "contextCompression"} key, so it continues across
 * generate calls, sessions, and process restarts when the caller carries the history forward.
 * {@link #resolveCompressedHistory(List)} derives the model's view from such a history. Set {@link
 * Options#replaceHistory} to have the compressed messages replace the history instead. The final
 * response carries the stats of the newest compression under {@code
 * getCustom().get("contextCompression")}. The middleware never modifies the messages it receives.
 *
 * <p><b>Ordering.</b> List it after middleware that adds context, so the compression accounts for
 * what they add, and before retry and fallback middleware, so retried and fallback calls receive
 * the compressed view.
 *
 * <pre>{@code
 * ContextCompressionMiddleware.Options opts = new ContextCompressionMiddleware.Options();
 * opts.maxInputTokens = 80_000;
 * opts.deduplicateToolResponses = new ContextCompressionMiddleware.Dedupe();
 * opts.toolResponses = new ContextCompressionMiddleware.ToolTruncation(2000);
 * opts.summarize = new ContextCompressionMiddleware.Summarizer("googleai/gemini-flash-lite-latest");
 *
 * genkit.generate(GenerateOptions.builder()
 *     .model("googleai/gemini-flash-latest")
 *     .prompt("Research this topic thoroughly.")
 *     .tools(List.of(searchTool))
 *     .use(new ContextCompressionMiddleware(opts), new RetryMiddleware(null))
 *     .build());
 * }</pre>
 */
public class ContextCompressionMiddleware implements GenerationMiddleware {

  private static final Logger logger = LoggerFactory.getLogger(ContextCompressionMiddleware.class);

  /** The registered name of this middleware. */
  public static final String NAME =
      GenerationMiddlewarePlugin.EXPERIMENTAL_PREFIX + "contextCompression";

  /** Matches duplicate tool responses by tool name and input. */
  public static final String MATCH_NAME_AND_INPUT = "name-and-input";

  /** Matches every response from the same tool, whatever its input. */
  public static final String MATCH_NAME_ONLY = "name-only";

  static final int DEFAULT_MAX_TOOL_RESPONSE_CHARS = 400_000;
  static final int DEFAULT_TOOL_RESPONSE_PRESERVE_RECENT = 2;
  static final int DEFAULT_DEDUPE_KEEP_RECENT = 1;
  static final int DEFAULT_PRESERVE_RECENT = 4;
  static final int DEFAULT_SUMMARIZE_PRESERVE_RECENT = 6;
  static final int SUMMARIZE_RENDER_MAX_CHARS = 400_000;
  static final int SUMMARIZE_RENDER_HEAD_CHARS = 100_000;
  static final double CHARS_PER_TOKEN_ESTIMATE = 3.5;

  /** A tool response cap that never applies. */
  static final int NO_LIMIT = Integer.MAX_VALUE;

  static final String CONVERSATION_PLACEHOLDER = "{conversation}";

  static final String DEFAULT_SUMMARIZE_PROMPT =
      "Summarize the following conversation concisely. Capture key facts, decisions made, tool"
          + " calls and their results, and the current state of the conversation so that the"
          + " assistant can continue helping the user effectively.\n"
          + "\n"
          + "Conversation:\n"
          + "{conversation}\n"
          + "\n"
          + "Summary:";

  private final Options options;

  /** Per-call state; null on a prototype that {@link #newInstance()} has not bound. */
  private Run run;

  /**
   * Creates the middleware.
   *
   * @param options the configuration; null uses the defaults
   */
  public ContextCompressionMiddleware(Options options) {
    this.options = options != null ? options : new Options();
  }

  private ContextCompressionMiddleware(Options options, Run run) {
    this.options = options;
    this.run = run;
  }

  @Override
  public String name() {
    return NAME;
  }

  /**
   * Validates the configuration and returns an instance with fresh per-call state.
   *
   * @throws GenkitException with error code {@code INVALID_ARGUMENT} when the configuration is
   *     invalid
   */
  @Override
  public GenerationMiddleware newInstance() {
    return new ContextCompressionMiddleware(options, new Run(new Compressor(options)));
  }

  private Run run() {
    if (run == null) {
      run = new Run(new Compressor(options));
    }
    return run;
  }

  @Override
  public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException {
    return run().wrapGenerate(ctx, params, next);
  }

  @Override
  public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
      throws GenkitException {
    return run().wrapModel(ctx, params, next);
  }

  @Override
  public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next) throws GenkitException {
    return next.apply(ctx, params);
  }

  /**
   * Returns the messages a model receives for a history this middleware compressed: the leading
   * system messages, the protected messages the compaction covered, the newest summary, and every
   * message after its boundary, with recorded tool response limits applied. A boundary on a user
   * message after the last model or tool message is ignored, since it may come from a client. A
   * history without compression metadata is returned as is.
   *
   * @param messages the history
   * @return the model's view of the history
   */
  public static List<Message> resolveCompressedHistory(List<Message> messages) {
    if (messages == null) {
      return null;
    }
    return CompressionHistory.resolveWithIndices(messages, null, null).messages();
  }

  // ── Configuration ───────────────────────────────────────────────────────────────

  /** Configuration for {@link ContextCompressionMiddleware}. Zero leaves a field unset. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public static class Options {

    @JsonProperty("maxInputTokens")
    @JsonPropertyDescription(
        "Compress when the context exceeds this many tokens, measured by the previous call's"
            + " reported input tokens or, before one exists, estimated from the messages. Unset"
            + " disables the token trigger.")
    public int maxInputTokens;

    @JsonProperty("maxMessages")
    @JsonPropertyDescription(
        "Compress when the conversation holds more than this many messages, dropping the oldest"
            + " non-system messages so the kept history starts with a user turn. Unset disables"
            + " the message cap.")
    public int maxMessages;

    @JsonProperty("preserveRecent")
    @JsonPropertyDescription(
        "How many of the newest non-system messages are kept when older messages are dropped,"
            + " and the default window for summarize. Defaults to 4. Shrinks when the context is"
            + " far over budget.")
    public int preserveRecent;

    @JsonProperty("maxToolResponseChars")
    @JsonPropertyDescription(
        "Hard cap on the characters of any single tool response, output and content together,"
            + " applied on every iteration. Defaults to 400000. Negative disables the cap.")
    public int maxToolResponseChars;

    @JsonProperty("deduplicateToolResponses")
    @JsonPropertyDescription(
        "Replace older duplicate tool responses with a short notice when compression runs.")
    public Dedupe deduplicateToolResponses;

    @JsonProperty("toolResponses")
    @JsonPropertyDescription(
        "Truncate older tool responses when compression runs, leaving the newest tool messages"
            + " intact.")
    public ToolTruncation toolResponses;

    @JsonProperty("summarize")
    @JsonPropertyDescription(
        "Fold older messages into a summary written by a model when compression runs.")
    public Summarizer summarize;

    @JsonProperty("skipSummarizationThreshold")
    @JsonPropertyDescription(
        "Skip summarization when deduplication and truncation saved at least this fraction (0 to"
            + " 1) of the context and brought it under maxInputTokens. Unset always summarizes.")
    public double skipSummarizationThreshold;

    @JsonProperty("truncationNotice")
    @JsonPropertyDescription("Custom notice inserted when messages are dropped.")
    public String truncationNotice;

    @JsonProperty("noTruncationNotice")
    @JsonPropertyDescription(
        "Drop messages without inserting a notice that tells the model context was removed."
            + " Defaults to false.")
    public boolean noTruncationNotice;

    @JsonProperty("replaceHistory")
    @JsonPropertyDescription(
        "Replace the returned history with the compressed messages, instead of keeping the"
            + " original messages and recording the compression in their metadata. Defaults to"
            + " false.")
    public boolean replaceHistory;

    /** Creates options with every field unset. */
    public Options() {}
  }

  /** Configures how older duplicate tool responses are replaced with a short notice. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public static class Dedupe {

    @JsonProperty("matchBy")
    @JsonPropertyDescription(
        "How duplicate tool responses are matched: by tool name and input (name-and-input), or"
            + " by tool name alone (name-only), which replaces older responses even when the"
            + " inputs differ. Defaults to name-and-input.")
    public String matchBy;

    @JsonProperty("keepRecent")
    @JsonPropertyDescription(
        "How many of the newest responses in each duplicate group stay intact. Defaults to 1.")
    public int keepRecent;

    @JsonProperty("notice")
    @JsonPropertyDescription(
        "Text that replaces the output of each older duplicate. A default notice is used when"
            + " empty.")
    public String notice;

    /** Creates a deduplication config with the defaults. */
    public Dedupe() {}
  }

  /** Configures how older tool responses are truncated. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public static class ToolTruncation {

    @JsonProperty("maxChars")
    @JsonPropertyDescription(
        "Most characters an older tool response keeps, output and content together. Text"
            + " content is folded into the output when a response is truncated.")
    public int maxChars;

    @JsonProperty("preserveRecent")
    @JsonPropertyDescription(
        "How many of the newest tool messages stay intact. Defaults to 2. Negative truncates"
            + " every tool message.")
    public int preserveRecent;

    /** Creates a truncation config; {@link #maxChars} must be set. */
    public ToolTruncation() {}

    /**
     * Creates a truncation config.
     *
     * @param maxChars the most characters an older tool response keeps
     */
    public ToolTruncation(int maxChars) {
      this.maxChars = maxChars;
    }
  }

  /** Configures how older messages are folded into a summary. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public static class Summarizer {

    @JsonProperty("model")
    @JsonPropertyDescription(
        "Model that writes the summaries, typically a smaller and faster model than the one"
            + " being compressed. Its config is used as is.")
    public String model;

    @JsonProperty("config")
    @JsonPropertyDescription(
        "Config sent to the summarizer model as is, such as its output limit. Unset sends none.")
    public Map<String, Object> config;

    @JsonProperty("preserveRecent")
    @JsonPropertyDescription(
        "How many of the newest non-system messages stay out of the summary. Defaults to the"
            + " top-level preserveRecent when set, and to 6 otherwise.")
    public int preserveRecent;

    @JsonProperty("prompt")
    @JsonPropertyDescription(
        "Custom summarization prompt. Each {conversation} in it is replaced with a text"
            + " rendering of the messages to summarize; without one, the rendering is appended.")
    public String prompt;

    /** Creates a summarizer config; {@link #model} must be set. */
    public Summarizer() {}

    /**
     * Creates a summarizer config.
     *
     * @param model the name of the model that writes the summaries
     */
    public Summarizer(String model) {
      this.model = model;
    }
  }

  // ── Validated configuration ─────────────────────────────────────────────────────

  /** The options with their defaults applied and their fields validated. */
  static final class Compressor {
    final int maxInputTokens;
    final int maxMessages;
    final int preserveRecent;
    final boolean explicitPreserveRecent;
    final int maxToolResponseChars;

    boolean dedupe;
    boolean dedupeMatchByInput;
    int dedupeKeepRecent;
    String dedupeNotice;

    boolean truncate;
    int toolMaxChars;
    int toolPreserveRecent;

    boolean summarize;
    String summaryModel;
    Map<String, Object> summaryConfig;
    int summaryPreserveRecent;
    String summaryPrompt;
    final double skipSummarizationThreshold;

    final boolean insertNotice;
    final String noticeText;
    final boolean replaceHistory;

    Compressor(Options c) {
      if (c.maxInputTokens < 0) {
        throw invalid("maxInputTokens must not be negative, got " + c.maxInputTokens);
      }
      if (c.maxMessages < 0) {
        throw invalid("maxMessages must not be negative, got " + c.maxMessages);
      }
      if (c.preserveRecent < 0) {
        throw invalid("preserveRecent must not be negative, got " + c.preserveRecent);
      }
      if (c.skipSummarizationThreshold < 0
          || c.skipSummarizationThreshold > 1
          || Double.isNaN(c.skipSummarizationThreshold)) {
        throw invalid(
            "skipSummarizationThreshold must be between 0 and 1, got "
                + c.skipSummarizationThreshold);
      }
      maxInputTokens = c.maxInputTokens;
      maxMessages = c.maxMessages;
      preserveRecent = or(c.preserveRecent, DEFAULT_PRESERVE_RECENT);
      explicitPreserveRecent = c.preserveRecent != 0;
      int cap = or(c.maxToolResponseChars, DEFAULT_MAX_TOOL_RESPONSE_CHARS);
      maxToolResponseChars = cap < 0 ? NO_LIMIT : cap;
      skipSummarizationThreshold = c.skipSummarizationThreshold;
      insertNotice = !c.noTruncationNotice;
      noticeText =
          c.truncationNotice != null && !c.truncationNotice.isEmpty()
              ? c.truncationNotice
              : CompressionHistory.DEFAULT_TRUNCATION_NOTICE;
      replaceHistory = c.replaceHistory;

      Dedupe d = c.deduplicateToolResponses;
      if (d != null) {
        String matchBy = d.matchBy != null ? d.matchBy : "";
        if (!matchBy.isEmpty()
            && !matchBy.equals(MATCH_NAME_AND_INPUT)
            && !matchBy.equals(MATCH_NAME_ONLY)) {
          throw invalid(
              "unknown deduplicateToolResponses.matchBy \""
                  + matchBy
                  + "\", want \""
                  + MATCH_NAME_AND_INPUT
                  + "\" or \""
                  + MATCH_NAME_ONLY
                  + "\"");
        }
        if (d.keepRecent < 0) {
          throw invalid(
              "deduplicateToolResponses.keepRecent must not be negative, got " + d.keepRecent);
        }
        dedupe = true;
        dedupeMatchByInput = !matchBy.equals(MATCH_NAME_ONLY);
        dedupeKeepRecent = or(d.keepRecent, DEFAULT_DEDUPE_KEEP_RECENT);
        dedupeNotice =
            d.notice != null && !d.notice.isEmpty()
                ? d.notice
                : CompressionHistory.DEFAULT_DEDUPE_NOTICE;
      }

      ToolTruncation t = c.toolResponses;
      if (t != null) {
        if (t.maxChars <= 0) {
          throw invalid("toolResponses.maxChars must be positive, got " + t.maxChars);
        }
        truncate = true;
        toolMaxChars = t.maxChars;
        toolPreserveRecent =
            Math.max(0, or(t.preserveRecent, DEFAULT_TOOL_RESPONSE_PRESERVE_RECENT));
      }

      Summarizer s = c.summarize;
      if (s != null) {
        if (s.model == null || s.model.isEmpty()) {
          throw invalid("summarize.model is required");
        }
        if (s.preserveRecent < 0) {
          throw invalid("summarize.preserveRecent must not be negative, got " + s.preserveRecent);
        }
        summarize = true;
        summaryModel = s.model;
        summaryConfig = s.config;
        summaryPreserveRecent =
            or(s.preserveRecent, or(c.preserveRecent, DEFAULT_SUMMARIZE_PRESERVE_RECENT));
        summaryPrompt =
            s.prompt != null && !s.prompt.isEmpty() ? s.prompt : DEFAULT_SUMMARIZE_PROMPT;
      }
    }

    private static int or(int v, int def) {
      return v != 0 ? v : def;
    }

    private static GenkitException invalid(String message) {
      return GenkitException.builder()
          .message("contextCompression: " + message)
          .errorCode("INVALID_ARGUMENT")
          .build();
    }
  }

  // ── Per-call state ──────────────────────────────────────────────────────────────

  /** The state of one generate call. The hooks of one call run in sequence. */
  static final class Run {
    final Compressor c;

    /** The input token count of the latest model call, when {@link #haveLastInputTokens}. */
    int lastInputTokens;

    boolean haveLastInputTokens;

    int cumulativeCapped;
    int cumulativeDeduplicated;
    int cumulativeTruncated;
    boolean noticeInserted;
    boolean summarized;

    /** The stats of the call's newest compression, or null. */
    Map<String, Object> latest;

    /** Whether the summarizer model was checked to exist. */
    boolean summarizerChecked;

    /** The boundary messages this call stamped. */
    final Set<Message> trusted = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Cached measurements by part: {chars, responseChars}. */
    final Map<Part, int[]> sizes = new IdentityHashMap<>();

    /** Cached recorded edits by part. */
    final Map<Part, Part> materialized = new IdentityHashMap<>();

    Run(Compressor c) {
      this.c = c;
    }

    int[] sizeOf(Part p) {
      int[] size = sizes.get(p);
      if (size != null) {
        return size;
      }
      size = new int[] {estimatePartChars(p), 0};
      if (p != null && p.getToolResponse() != null) {
        size[1] = toolResponseCharLength(p.getToolResponse());
      }
      sizes.put(p, size);
      return size;
    }

    int messageChars(List<Message> msgs) {
      int total = 0;
      for (Message m : msgs) {
        if (m == null) {
          continue;
        }
        for (Part p : partsOf(m)) {
          total += sizeOf(p)[0];
        }
      }
      return total;
    }

    private void checkSummarizer(ActionContext ctx) {
      if (!c.summarize || summarizerChecked) {
        return;
      }
      summarizerChecked = true;
      // A missing summarizer can be resolved now or never, so it fails the call before the
      // first model turn rather than at the first compaction.
      if (ctx != null && ctx.getRegistry() != null && lookupModel(ctx, c.summaryModel) == null) {
        throw GenkitException.builder()
            .message("contextCompression: summarize.model \"" + c.summaryModel + "\" not found")
            .errorCode("NOT_FOUND")
            .build();
      }
    }

    ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next) {
      checkSummarizer(ctx);
      boolean isTopLevel = params.getIteration() == 0;
      GenerateActionOptions request = params.getRequest();
      List<Message> input =
          request.getMessages() != null ? request.getMessages() : new ArrayList<>();

      CompressionHistory.Changed sanitizedResult =
          CompressionHistory.sanitizeUntrustedUserMessages(input, trusted);
      boolean sanitized = sanitizedResult.changed();
      CompressionHistory.Changed reconciledRaw =
          CompressionHistory.reconcileStandaloneNotices(sanitizedResult.messages(), c.noticeText);
      List<Message> raw = reconciledRaw.messages();
      CompressionHistory.Resolution resolved =
          CompressionHistory.resolveWithIndices(raw, trusted, materialized);
      int prevBoundary = resolved.boundary();
      Message prevAnchor = resolved.anchor();
      Map<Message, Integer> origIndex = resolved.origIndex();
      CompressionHistory.Changed reconciledActive =
          CompressionHistory.reconcileStandaloneNotices(resolved.messages(), c.noticeText);
      List<Message> active = reconciledActive.messages();
      boolean reconciled = reconciledRaw.changed() || reconciledActive.changed();

      int stampedTokens = 0;
      if (haveLastInputTokens) {
        stampedTokens = lastInputTokens;
      } else {
        Integer t = CompressionHistory.lastReportedInputTokens(active);
        if (t == null) {
          t = CompressionHistory.lastReportedInputTokens(raw);
        }
        if (t != null) {
          stampedTokens = t;
        }
      }
      int[] activeChars = {-1};
      java.util.function.IntSupplier getActiveChars =
          () -> {
            if (activeChars[0] < 0) {
              activeChars[0] = messageChars(active);
            }
            return activeChars[0];
          };
      int estimatedTokens = 0;
      if (c.maxInputTokens > 0) {
        estimatedTokens = charsToTokens(getActiveChars.getAsInt());
      }
      final int effectiveTokens = Math.max(stampedTokens, estimatedTokens);
      final boolean overBudget = c.maxInputTokens > 0 && effectiveTokens > c.maxInputTokens;

      final boolean shouldCompress =
          overBudget || (c.maxMessages > 0 && active.size() > c.maxMessages);
      boolean hasOversizedToolResponse = false;
      if (!shouldCompress && c.maxToolResponseChars != NO_LIMIT) {
        outer:
        for (Message m : active) {
          if (roleOf(m) != Role.TOOL) {
            continue;
          }
          for (Part p : partsOf(m)) {
            if (p != null
                && p.getToolResponse() != null
                && !hasCompressionFlag(p.getMetadata(), CompressionHistory.CAPPED)
                && !hasCompressionFlag(p.getMetadata(), CompressionHistory.TRUNCATED)
                && sizeOf(p)[1] > c.maxToolResponseChars) {
              hasOversizedToolResponse = true;
              break outer;
            }
          }
        }
      }

      if (!shouldCompress && !hasOversizedToolResponse) {
        GenerateParams p = params;
        if (reconciled || sanitized) {
          p = params.withRequest(request.withMessages(raw));
        }
        ModelResponse resp = next.apply(ctx, p);
        if (isTopLevel) {
          attachStats(resp, latest);
        }
        return resp;
      }

      final int originalCount = active.size();
      int inputTokensBefore = effectiveTokens;
      if (inputTokensBefore <= 0) {
        inputTokensBefore = charsToTokens(getActiveChars.getAsInt());
      }
      double overshootRatio = 1.0;
      if (c.maxInputTokens > 0) {
        overshootRatio = (double) effectiveTokens / (double) c.maxInputTokens;
      }
      int adjustedPreserveRecent = shrinkForOvershoot(overshootRatio, c.preserveRecent);
      int adjustedSummaryPreserveRecent =
          shrinkForOvershoot(overshootRatio, c.summaryPreserveRecent);

      List<Message> messages = new ArrayList<>(active);
      int capped = 0;
      int deduplicated = 0;
      int truncated = 0;
      boolean noticeInsertedNow = false;
      boolean msgTruncated = false;
      int truncBoundary = -1;
      int sumBoundary = -1;
      boolean usedAnchorUser = false;
      boolean summarizedNow = false;
      boolean summarizationSkipped = false;
      String summaryText = "";

      // 1. Tool response deduplication, when a trigger fired.
      if (shouldCompress && c.dedupe) {
        Edit e = dedupeToolResponses(messages);
        messages = e.messages;
        deduplicated = e.count;
      }

      // 2. Tool response limits: the safety cap always, truncation when a trigger fired.
      ToolLimits limits = applyToolLimits(messages, shouldCompress);
      messages = limits.messages;
      capped = limits.capped;
      truncated = limits.truncated;

      // Both steps keep every message in place, so the edited messages map back to the raw ones
      // by position.
      Map<Integer, Message> updatedToolMessagesByRawIdx = new HashMap<>();
      for (int k = 0; k < messages.size(); k++) {
        Message m = messages.get(k);
        Integer origIdx = origIndex.get(active.get(k));
        if (origIdx != null) {
          origIndex.put(m, origIdx);
          if (m != active.get(k) && origIdx >= 0) {
            updatedToolMessagesByRawIdx.put(origIdx, m);
          }
        }
      }

      if (shouldCompress) {
        // 3. Check whether the cheap strategies brought the context under budget.
        boolean cheapUnderBudget = false;
        boolean skipSummarization = false;
        if (deduplicated > 0 || truncated > 0) {
          int charsBefore = getActiveChars.getAsInt();
          int charsAfterCheap = messageChars(messages);
          double savingsRatio = 0.0;
          int scaledTokensAfterCheap = 0;
          if (charsBefore > 0) {
            savingsRatio = (double) (charsBefore - charsAfterCheap) / (double) charsBefore;
            scaledTokensAfterCheap =
                (int)
                    Math.ceil(
                        (double) effectiveTokens * (double) charsAfterCheap / (double) charsBefore);
          }
          int tokensAfterCheap = Math.max(charsToTokens(charsAfterCheap), scaledTokensAfterCheap);
          cheapUnderBudget = c.maxInputTokens == 0 || tokensAfterCheap <= c.maxInputTokens;
          skipSummarization =
              c.summarize
                  && c.skipSummarizationThreshold > 0
                  && savingsRatio >= c.skipSummarizationThreshold
                  && cheapUnderBudget;
        }

        // 4. Summarization.
        if (c.summarize) {
          if (skipSummarization) {
            summarizationSkipped = true;
          } else {
            int fallbackPreserveRecent = overBudget ? adjustedPreserveRecent : 0;
            Summarization s =
                summarizeMessages(
                    ctx,
                    messages,
                    adjustedSummaryPreserveRecent,
                    c.maxMessages,
                    fallbackPreserveRecent);
            messages = s.messages;
            if (s.summarized) {
              summarizedNow = true;
              summaryText = s.text;
              sumBoundary = boundaryFor(s.tail, origIndex, prevBoundary, raw);
              usedAnchorUser = prevAnchor != null && containsIdentity(s.tail, prevAnchor);
            }
          }
        }

        // 5. Message truncation, when nothing was summarized.
        if (!summarizedNow) {
          List<Message> systemMessages = CompressionHistory.partition(messages).system();
          int noticeSlot = c.insertNotice && systemMessages.isEmpty() ? 1 : 0;
          int fixedSlots = systemMessages.size() + noticeSlot;

          boolean cheapSatisfiedBudget = c.summarize ? summarizationSkipped : cheapUnderBudget;
          // Over budget, messages are dropped whenever the other strategies did not bring the
          // context under it.
          boolean needsTokenFallbackTruncation = overBudget && !cheapSatisfiedBudget;

          int effectiveMaxMessages = 0;
          if ((c.explicitPreserveRecent && !cheapSatisfiedBudget) || needsTokenFallbackTruncation) {
            effectiveMaxMessages = fixedSlots + adjustedPreserveRecent;
            if (c.maxMessages > 0) {
              effectiveMaxMessages = Math.min(c.maxMessages, effectiveMaxMessages);
            }
          } else if (c.maxMessages > 0 && overshootRatio >= 1.5) {
            int adjustedCapKeep =
                shrinkForOvershoot(overshootRatio, Math.max(1, c.maxMessages - fixedSlots));
            effectiveMaxMessages = Math.min(c.maxMessages, fixedSlots + adjustedCapKeep);
          } else if (c.maxMessages > 0) {
            effectiveMaxMessages = c.maxMessages;
          }

          if (effectiveMaxMessages > 0 && messages.size() > effectiveMaxMessages) {
            Truncation t = truncateMessages(messages, effectiveMaxMessages);
            messages = t.messages;
            noticeInsertedNow = t.noticeInserted;
            if (t.dropped > 0) {
              msgTruncated = true;
              usedAnchorUser =
                  t.usedAnchorUser || (prevAnchor != null && containsIdentity(t.tail, prevAnchor));
              truncBoundary = boundaryFor(t.tail, origIndex, prevBoundary, raw);
            }
          }
        }
      }

      int compressedCount = messages.size();
      boolean wasCompressed =
          capped > 0
              || deduplicated > 0
              || truncated > 0
              || summarizedNow
              || compressedCount < originalCount
              || noticeInsertedNow;

      Map<String, Object> turnStats = null;
      if (wasCompressed) {
        cumulativeCapped += capped;
        cumulativeDeduplicated += deduplicated;
        cumulativeTruncated += truncated;
        noticeInserted = noticeInserted || noticeInsertedNow;
        summarized = summarized || summarizedNow;
        turnStats = new LinkedHashMap<>();
        turnStats.put("triggered", true);
        turnStats.put("inputTokensBefore", inputTokensBefore);
        turnStats.put("messagesOriginal", originalCount);
        turnStats.put("messagesAfter", compressedCount);
        turnStats.put("toolResponsesSafetyCapped", cumulativeCapped);
        turnStats.put("toolResponsesDeduplicated", cumulativeDeduplicated);
        turnStats.put("toolResponsesTruncated", cumulativeTruncated);
        turnStats.put("truncationNoticeInserted", noticeInserted);
        turnStats.put("summarized", summarized);
        turnStats.put("summarizationSkipped", summarizationSkipped);
        latest = turnStats;
        if (logger.isDebugEnabled()) {
          logger.debug(
              "context compressed: iteration={} inputTokensBefore={} messagesOriginal={}"
                  + " messagesAfter={} toolResponsesCapped={} toolResponsesDeduplicated={}"
                  + " toolResponsesTruncated={} summarized={} summarizationSkipped={}"
                  + " messagesTruncated={}",
              params.getIteration(),
              inputTokensBefore,
              originalCount,
              compressedCount,
              capped,
              deduplicated,
              truncated,
              summarizedNow,
              summarizationSkipped,
              msgTruncated);
        }
      }

      List<Message> outgoing;
      if (c.replaceHistory) {
        outgoing = raw;
        if (wasCompressed || reconciled || sanitized) {
          outgoing = messages;
        }
      } else if (wasCompressed) {
        boolean hasCompactionBoundary = summarizedNow || msgTruncated;
        int cutIndex = -1;
        if (hasCompactionBoundary) {
          cutIndex = Math.max(prevBoundary, Math.max(sumBoundary, truncBoundary));
        }
        String activeSummary = "";
        boolean viewHasSummary = false;
        for (Message m : messages) {
          if (hasMessageFlag(m, CompressionHistory.SUMMARY_MESSAGE)) {
            viewHasSummary = true;
            break;
          }
        }
        if (viewHasSummary) {
          if (summarizedNow) {
            activeSummary = summaryText;
          } else if (prevBoundary >= 0) {
            Object prev =
                CompressionHistory.ccGet(
                    ccMeta(raw.get(prevBoundary).getMetadata()), CompressionHistory.SUMMARY);
            activeSummary = prev instanceof String s ? s : "";
          }
        }

        outgoing = new ArrayList<>(raw.size());
        for (int idx = 0; idx < raw.size(); idx++) {
          Message m = raw.get(idx);
          Message updated = m;
          Message edited = updatedToolMessagesByRawIdx.get(idx);
          if (edited != null) {
            updated = recordToolEdits(m, edited);
          }
          if (hasCompactionBoundary && idx == cutIndex && updated != null) {
            Message stamped = copyMessage(updated);
            stamped.setMetadata(
                withCompressionMetadata(
                    updated.getMetadata(),
                    fields(
                        CompressionHistory.SUMMARY,
                        activeSummary,
                        CompressionHistory.STATS,
                        new LinkedHashMap<>(turnStats),
                        CompressionHistory.ANCHOR_USER,
                        usedAnchorUser ? Boolean.TRUE : null,
                        CompressionHistory.TRUNCATION_NOTICE,
                        noticeInsertedNow
                                && !c.noticeText.equals(
                                    CompressionHistory.DEFAULT_TRUNCATION_NOTICE)
                            ? c.noticeText
                            : null,
                        CompressionHistory.PRESERVE_SYSTEM,
                        null)));
            trusted.add(stamped);
            updated = stamped;
          }
          outgoing.add(updated);
        }
      } else {
        // Nothing changed: the history stays as received, short of the sanitizing and
        // reconciling above.
        outgoing = raw;
      }

      ModelResponse resp = next.apply(ctx, params.withRequest(request.withMessages(outgoing)));
      if (isTopLevel) {
        attachStats(resp, latest);
      }
      return resp;
    }

    ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next) {
      ModelRequest orig = params.getRequest();
      List<Message> origMessages =
          orig.getMessages() != null ? orig.getMessages() : new ArrayList<>();
      List<Message> view =
          CompressionHistory.reconcileStandaloneNotices(
                  CompressionHistory.resolveWithIndices(origMessages, trusted, materialized)
                      .messages(),
                  c.noticeText)
              .messages();
      boolean changed = !CompressionHistory.identityEqual(view, origMessages);
      ModelParams p = params;
      if (changed) {
        p = params.withRequest(requestWith(orig, view));
      }

      ModelResponse resp = next.apply(ctx, p);
      if (resp == null) {
        return null;
      }
      if (changed && resp.getRequest() != null) {
        // Models record the request they received on the response, and the history reads it as
        // the conversation so far. Put the full messages back in place of the view, keeping the
        // rest: a middleware inside this one, such as a fallback, may have changed the config it
        // ran with.
        resp.setRequest(requestWith(resp.getRequest(), origMessages));
      }
      if (resp.getUsage() == null) {
        return resp;
      }
      Integer inputTokens = resp.getUsage().getInputTokens();
      lastInputTokens = inputTokens != null ? inputTokens : 0;
      haveLastInputTokens = true;
      Message message = resp.getMessage();
      if (inputTokens != null && inputTokens > 0 && message != null) {
        Message stamped = copyMessage(message);
        stamped.setMetadata(
            withCompressionMetadata(
                message.getMetadata(), fields(CompressionHistory.INPUT_TOKENS, inputTokens)));
        setMessage(resp, stamped);
      }
      return resp;
    }

    /** Returns the raw index of the last message a compaction covers, given the kept tail. */
    private static int boundaryFor(
        List<Message> tail, Map<Message, Integer> origIndex, int prevBoundary, List<Message> raw) {
      for (Message m : tail) {
        Integer idx = origIndex.get(m);
        if (idx != null && idx > prevBoundary) {
          return idx - 1;
        }
      }
      return CompressionHistory.lastNonSystemIndex(raw);
    }

    // ── Summarization ──

    private Summarization summarizeMessages(
        ActionContext ctx,
        List<Message> msgs,
        int effectivePreserveRecent,
        int maxMessagesCap,
        int fallbackPreserveRecent) {
      Summarization unchanged = new Summarization(msgs);
      CompressionHistory.Partition parts = CompressionHistory.partition(msgs);
      List<Message> system = parts.system();
      List<Message> rest = parts.rest();

      // Reserve a slot for the summary and one for each system message, so the summary never
      // exceeds the cap or gets dropped by truncation at once.
      int targetKeep = Math.max(1, effectivePreserveRecent);
      if (maxMessagesCap > 0) {
        int maxKeepForCap = maxMessagesCap - system.size() - 1;
        if (maxKeepForCap < 1) {
          return unchanged;
        }
        targetKeep = Math.min(targetKeep, maxKeepForCap);
      }
      if (rest.size() <= targetKeep
          && fallbackPreserveRecent > 0
          && fallbackPreserveRecent < targetKeep) {
        targetKeep = fallbackPreserveRecent;
      }
      if (rest.size() <= targetKeep) {
        return unchanged;
      }

      List<Message> toSummarize;
      List<Message> lifted;
      List<Message> toKeep;
      while (true) {
        int split = summarySplit(rest, targetKeep);
        if (split < 0) {
          return unchanged;
        }
        // The pinned messages the summary would cover move ahead of it.
        toSummarize = new ArrayList<>();
        lifted = new ArrayList<>();
        for (Message m : rest.subList(0, split)) {
          if (isPinned(m)) {
            lifted.add(m);
          } else {
            toSummarize.add(m);
          }
        }
        toKeep = new ArrayList<>(rest.subList(split, rest.size()));
        if (toSummarize.isEmpty()) {
          return unchanged;
        }
        if (maxMessagesCap <= 0
            || system.size() + lifted.size() + 1 + toKeep.size() <= maxMessagesCap) {
          break;
        }
        // The lifted messages take slots under the cap, so fewer recent messages are kept. A
        // tail over the cap on its own leaves the messages as they are.
        if (system.size() + 1 + toKeep.size() > maxMessagesCap) {
          return unchanged;
        }
        targetKeep -= system.size() + lifted.size() + 1 + toKeep.size() - maxMessagesCap;
        if (targetKeep < 1) {
          return unchanged;
        }
      }

      String text;
      try {
        text = writeSummary(ctx, toSummarize);
      } catch (RuntimeException e) {
        if (isCancellation(e)) {
          throw e;
        }
        logger.warn(
            "context compression summarization failed, falling back to message truncation if"
                + " over the message limit: model={} error={}",
            c.summaryModel,
            e.getMessage());
        return unchanged;
      }

      List<Message> out = new ArrayList<>(system.size() + lifted.size() + 1 + toKeep.size());
      out.addAll(system);
      out.addAll(lifted);
      out.add(CompressionHistory.summaryMessage(text));
      out.addAll(toKeep);
      Summarization s = new Summarization(out);
      s.summarized = true;
      s.text = text;
      s.tail = toKeep;
      return s;
    }

    private String writeSummary(ActionContext ctx, List<Message> msgs) {
      Model model =
          ctx != null && ctx.getRegistry() != null ? lookupModel(ctx, c.summaryModel) : null;
      if (model == null) {
        throw new GenkitException(
            "summarizer model \"" + c.summaryModel + "\" could not be resolved");
      }
      String conversation =
          CompressionHistory.capSummarizerConversation(CompressionHistory.renderMessages(msgs));
      String prompt = c.summaryPrompt;
      if (prompt.contains(CONVERSATION_PLACEHOLDER)) {
        prompt = prompt.replace(CONVERSATION_PLACEHOLDER, conversation);
      } else {
        prompt += "\n\nConversation to summarize:\n" + conversation;
      }

      ModelRequest req = new ModelRequest(new ArrayList<>(List.of(Message.user(prompt))));
      if (c.summaryConfig != null) {
        req.setConfig(c.summaryConfig);
      }
      ModelResponse resp =
          ModelTelemetryHelper.runWithTelemetry(
              c.summaryModel,
              ctx.getFlowName(),
              "/generate/" + c.summaryModel,
              req,
              r -> model.run(ctx, r));
      if (resp == null) {
        throw new GenkitException("summarizer returned no response");
      }
      // A summary cut off at the output limit, or blocked, would stand in for the covered
      // messages from then on.
      FinishReason finish = resp.getFinishReason();
      if (finish != null && finish != FinishReason.STOP) {
        throw new GenkitException(
            "summarizer finished with reason \""
                + finish.name().toLowerCase(java.util.Locale.ROOT)
                + "\" instead of \"stop\"");
      }
      String text = resp.getText() != null ? resp.getText().trim() : "";
      if (text.isEmpty()) {
        throw new GenkitException("summarizer returned an empty summary");
      }
      return text;
    }

    // ── Truncation ──

    private Truncation truncateMessages(List<Message> msgs, int maxMessages) {
      Truncation result = new Truncation();
      if (maxMessages <= 0 || msgs.size() <= maxMessages) {
        result.messages = msgs;
        return result;
      }
      CompressionHistory.Partition parts = CompressionHistory.partition(msgs);
      List<Message> system = parts.system();
      List<Message> rest = parts.rest();
      boolean[] pinned = new boolean[rest.size()];
      int numPinned = 0;
      for (int i = 0; i < rest.size(); i++) {
        if (isPinned(rest.get(i))) {
          pinned[i] = true;
          numPinned++;
        }
      }

      boolean noticeConsumesSlot = c.insertNotice && system.isEmpty();
      int keepCount = maxMessages - system.size() - numPinned;
      if (noticeConsumesSlot) {
        keepCount--;
      }
      // The newest turn is kept even when the system and pinned messages fill the cap, since a
      // model cannot answer a conversation without one.
      keepCount = Math.max(1, keepCount);

      // The messages from start on are kept. The ones before it are dropped, except pinned ones.
      int start = keepStart(rest, pinned, keepCount);

      // Never separate a tool response from the request before it.
      while (start < rest.size() && roleOf(rest.get(start)) == Role.TOOL) {
        start++;
      }

      // When keepCount was too small to reach the model message of a trailing tool turn, keep the
      // final [model, tool...] group.
      if (start == rest.size()
          && keepCount > 0
          && !rest.isEmpty()
          && roleOf(rest.get(rest.size() - 1)) == Role.TOOL) {
        int idx = rest.size() - 1;
        while (idx >= 0 && roleOf(rest.get(idx)) == Role.TOOL) {
          idx--;
        }
        if (idx >= 0 && roleOf(rest.get(idx)) == Role.MODEL) {
          start = idx;
        }
      }

      // Start the kept messages with a user message: a later one they hold, or else the latest
      // dropped one, such as the prompt of a tool loop.
      Message anchor = null;
      boolean usedAnchorUser = false;
      if (start < rest.size() && roleOf(rest.get(start)) == Role.MODEL) {
        int u = -1;
        for (int i = start; i < rest.size(); i++) {
          if (roleOf(rest.get(i)) == Role.USER) {
            u = i - start;
            break;
          }
        }
        if (u >= 0) {
          start += u;
        } else if (keepCount > 0) {
          List<Message> dropped = rest.subList(0, start);
          int anchorIdx = CompressionHistory.latestUserIndex(dropped, true);
          if (anchorIdx < 0) {
            anchorIdx = CompressionHistory.latestUserIndex(dropped, false);
          }
          if (anchorIdx < 0) {
            start = rest.size();
          } else if (pinned[anchorIdx]) {
            // A pinned user message stays ahead of the kept messages on its own and needs no
            // slot.
          } else {
            int tailStart = rest.size();
            if (keepCount > 1) {
              tailStart = keepStart(rest, pinned, keepCount - 1);
            }
            while (tailStart < rest.size() && roleOf(rest.get(tailStart)) == Role.TOOL) {
              tailStart++;
            }
            // When one slot fewer cannot hold a [model, tool] pair, keep the latest group so the
            // tool loop keeps its newest result.
            if (tailStart < rest.size()) {
              start = tailStart;
            }
            anchor = rest.get(anchorIdx);
            usedAnchorUser = !hasMessageFlag(anchor, CompressionHistory.SUMMARY_MESSAGE);
          }
        }
      }

      // Pinned messages just before the kept ones stay in place. The others before them move
      // ahead of the anchor.
      while (start > 0 && pinned[start - 1]) {
        start--;
      }
      List<Message> tail = new ArrayList<>(rest.subList(start, rest.size()));
      List<Message> lifted = new ArrayList<>();
      for (int i = 0; i < start; i++) {
        if (pinned[i]) {
          lifted.add(rest.get(i));
        }
      }
      List<Message> kept = new ArrayList<>(lifted.size() + 1 + tail.size());
      kept.addAll(lifted);
      if (anchor != null) {
        kept.add(anchor);
      }
      kept.addAll(tail);
      int dropped = rest.size() - kept.size();

      result.dropped = dropped;
      result.tail = tail;
      result.usedAnchorUser = usedAnchorUser;
      List<Message> out = new ArrayList<>(system.size() + 1 + kept.size());
      if (dropped > 0 && c.insertNotice) {
        result.noticeInserted = true;
        if (!system.isEmpty()) {
          out.addAll(CompressionHistory.withNotice(system, c.noticeText));
        } else {
          out.add(CompressionHistory.standaloneNotice(c.noticeText));
        }
      } else {
        out.addAll(system);
      }
      out.addAll(kept);
      result.messages = out;
      return result;
    }

    // ── Deduplication ──

    private Edit dedupeToolResponses(List<Message> msgs) {
      Map<String, Object> toolInputByRef = new HashMap<>();
      Map<String, List<Long>> groups = new LinkedHashMap<>();
      List<ToolRequest> prevRequests = new ArrayList<>();
      Set<Integer> consumed = new HashSet<>();
      Map<Long, ToolRequest> matchedByPart = new HashMap<>();
      int ordinal = 0;

      for (int i = 0; i < msgs.size(); i++) {
        Message m = msgs.get(i);
        Role role = roleOf(m);
        if (role == Role.MODEL) {
          if (!c.dedupeMatchByInput) {
            continue;
          }
          prevRequests = new ArrayList<>();
          for (Part p : partsOf(m)) {
            if (p != null && p.getToolRequest() != null) {
              prevRequests.add(p.getToolRequest());
              String ref = p.getToolRequest().getRef();
              if (ref != null && !ref.isEmpty()) {
                toolInputByRef.put(ref, p.getToolRequest().getInput());
              }
            }
          }
          consumed.clear();
          ordinal = 0;
          // Claim the ref matches across the turn's tool messages first, so the positional
          // fallback never takes a request a ref names.
          for (int k = i + 1; k < msgs.size() && roleOf(msgs.get(k)) == Role.TOOL; k++) {
            List<Part> content = partsOf(msgs.get(k));
            for (int j = 0; j < content.size(); j++) {
              Part p = content.get(j);
              if (p == null
                  || p.getToolResponse() == null
                  || p.getToolResponse().getRef() == null
                  || p.getToolResponse().getRef().isEmpty()) {
                continue;
              }
              for (int idx = 0; idx < prevRequests.size(); idx++) {
                ToolRequest req = prevRequests.get(idx);
                if (!consumed.contains(idx) && p.getToolResponse().getRef().equals(req.getRef())) {
                  consumed.add(idx);
                  matchedByPart.put(pos(k, j), req);
                  break;
                }
              }
            }
          }
          continue;
        } else if (role != Role.TOOL) {
          // A response after another message answers no request of the turn before it.
          prevRequests = new ArrayList<>();
          continue;
        }

        List<Part> content = partsOf(m);
        for (int j = 0; j < content.size(); j++) {
          Part p = content.get(j);
          if (p == null || p.getToolResponse() == null) {
            continue;
          }
          ToolResponse resp = p.getToolResponse();
          String name = resp.getName() != null ? resp.getName() : "";
          if (!c.dedupeMatchByInput) {
            groups.computeIfAbsent(name, x -> new ArrayList<>()).add(pos(i, j));
            continue;
          }

          int current = ordinal++;
          Object input = null;
          boolean matched = false;
          String ref = resp.getRef() != null ? resp.getRef() : "";
          ToolRequest byPart = matchedByPart.get(pos(i, j));
          if (byPart != null) {
            input = byPart.getInput();
            matched = true;
          } else if (!ref.isEmpty() && toolInputByRef.containsKey(ref)) {
            input = toolInputByRef.get(ref);
            matched = true;
          } else if (!prevRequests.isEmpty()) {
            int idx = -1;
            if (current < prevRequests.size()
                && !consumed.contains(current)
                && name.equals(nameOf(prevRequests.get(current)))) {
              idx = current;
            } else {
              for (int k = 0; k < prevRequests.size(); k++) {
                if (!consumed.contains(k) && name.equals(nameOf(prevRequests.get(k)))) {
                  idx = k;
                  break;
                }
              }
            }
            if (idx >= 0) {
              consumed.add(idx);
              input = prevRequests.get(idx).getInput();
              matched = true;
            }
          }
          if (!matched) {
            continue;
          }
          String encoded = marshalJson(input);
          if (encoded == null) {
            continue;
          }
          groups.computeIfAbsent(name + "\u0000" + encoded, x -> new ArrayList<>()).add(pos(i, j));
        }
      }

      Set<Long> replace = new HashSet<>();
      for (List<Long> occurrences : groups.values()) {
        if (occurrences.size() > c.dedupeKeepRecent) {
          replace.addAll(occurrences.subList(0, occurrences.size() - c.dedupeKeepRecent));
        }
      }
      if (replace.isEmpty()) {
        return new Edit(msgs, 0);
      }

      int deduplicated = 0;
      List<Message> out = new ArrayList<>(msgs);
      for (int i = 0; i < msgs.size(); i++) {
        Message m = msgs.get(i);
        if (roleOf(m) != Role.TOOL) {
          continue;
        }
        List<Part> content = null;
        List<Part> parts = partsOf(m);
        for (int j = 0; j < parts.size(); j++) {
          Part p = parts.get(j);
          if (p == null
              || p.getToolResponse() == null
              || !replace.contains(pos(i, j))
              || hasCompressionFlag(p.getMetadata(), CompressionHistory.DEDUPLICATED)) {
            continue;
          }
          if (content == null) {
            content = new ArrayList<>(parts);
          }
          deduplicated++;
          Part part = p.copy();
          part.setMetadata(
              withCompressionMetadata(
                  p.getMetadata(),
                  fields(
                      CompressionHistory.DEDUPLICATED,
                      true,
                      CompressionHistory.NOTICE,
                      c.dedupeNotice)));
          part.setToolResponse(
              CompressionHistory.dedupedResponse(p.getToolResponse(), c.dedupeNotice));
          content.set(j, part);
        }
        if (content != null) {
          Message updated = copyMessage(m);
          updated.setContent(content);
          out.set(i, updated);
        }
      }
      return new Edit(out, deduplicated);
    }

    private static String nameOf(ToolRequest req) {
      return req.getName() != null ? req.getName() : "";
    }

    private static long pos(int msg, int part) {
      return ((long) msg << 32) | (part & 0xffffffffL);
    }

    // ── Tool response limits ──

    private ToolLimits applyToolLimits(List<Message> msgs, boolean includeTruncation) {
      List<Integer> toolMsgIndices = new ArrayList<>();
      for (int i = 0; i < msgs.size(); i++) {
        if (roleOf(msgs.get(i)) == Role.TOOL) {
          toolMsgIndices.add(i);
        }
      }
      int numTruncatable =
          toolMsgIndices.size() - Math.min(c.toolPreserveRecent, toolMsgIndices.size());

      ToolLimits result = new ToolLimits();
      List<Message> out = null;
      for (int n = 0; n < toolMsgIndices.size(); n++) {
        int i = toolMsgIndices.get(n);
        Message m = msgs.get(i);
        boolean isTruncatable = includeTruncation && c.truncate && n < numTruncatable;
        List<Part> content = null;
        List<Part> parts = partsOf(m);
        for (int j = 0; j < parts.size(); j++) {
          Part p = parts.get(j);
          if (p == null
              || p.getToolResponse() == null
              || hasCompressionFlag(p.getMetadata(), CompressionHistory.TRUNCATED)
              || hasCompressionFlag(p.getMetadata(), CompressionHistory.DEDUPLICATED)) {
            continue;
          }
          int limit = c.maxToolResponseChars;
          if (isTruncatable) {
            limit = Math.min(limit, c.toolMaxChars);
          }
          if (limit == NO_LIMIT
              || (limit == c.maxToolResponseChars
                  && hasCompressionFlag(p.getMetadata(), CompressionHistory.CAPPED))) {
            continue;
          }
          // Truncation when the stricter limit is the configured one, the safety cap otherwise.
          String mode =
              isTruncatable && limit == c.toolMaxChars
                  ? CompressionHistory.TRUNCATED
                  : CompressionHistory.CAPPED;
          ToolResponse updated =
              CompressionHistory.truncateToolResponse(p.getToolResponse(), limit, mode);
          if (updated == null) {
            continue;
          }
          if (mode.equals(CompressionHistory.TRUNCATED)) {
            result.truncated++;
          } else {
            result.capped++;
          }
          if (content == null) {
            content = new ArrayList<>(parts);
          }
          Part part = p.copy();
          part.setMetadata(
              withCompressionMetadata(
                  p.getMetadata(), fields(mode, true, CompressionHistory.MAX_CHARS, limit)));
          part.setToolResponse(updated);
          content.set(j, part);
        }
        if (content != null) {
          if (out == null) {
            out = new ArrayList<>(msgs);
          }
          Message updated = copyMessage(m);
          updated.setContent(content);
          out.set(i, updated);
        }
      }
      result.messages = out != null ? out : msgs;
      return result;
    }
  }

  // ── Results ──

  private static final class Summarization {
    final List<Message> messages;
    boolean summarized;
    String text;

    /** The messages kept after the summary. */
    List<Message> tail = List.of();

    Summarization(List<Message> messages) {
      this.messages = messages;
    }
  }

  private static final class Truncation {
    List<Message> messages;
    int dropped;
    boolean noticeInserted;

    /** The messages kept after the boundary, without the anchor user message. */
    List<Message> tail = List.of();

    boolean usedAnchorUser;
  }

  private static final class ToolLimits {
    List<Message> messages;
    int capped;
    int truncated;
  }

  private record Edit(List<Message> messages, int count) {}

  // ── Helpers ──

  /** Records stats under {@code custom["contextCompression"]} on resp. */
  static void attachStats(ModelResponse resp, Map<String, Object> stats) {
    if (resp == null || stats == null) {
      return;
    }
    Map<String, Object> custom =
        resp.getCustom() != null ? new LinkedHashMap<>(resp.getCustom()) : new LinkedHashMap<>();
    custom.put(KEY, stats);
    resp.setCustom(custom);
  }

  /**
   * Returns raw with the compression metadata of each part edited records, flagged rawOutput: the
   * raw part keeps its original output, and the view applies the edit again from the metadata.
   */
  static Message recordToolEdits(Message raw, Message edited) {
    List<Part> rawParts = partsOf(raw);
    List<Part> editedParts = partsOf(edited);
    List<Part> content = null;
    for (int i = 0; i < rawParts.size(); i++) {
      if (i >= editedParts.size()) {
        break;
      }
      Part rawPart = rawParts.get(i);
      Part editedPart = editedParts.get(i);
      if (editedPart == null || editedPart == rawPart || rawPart == null) {
        continue;
      }
      Map<String, Object> editedCc = ccMeta(editedPart.getMetadata());
      if (editedCc == null) {
        continue;
      }
      if (content == null) {
        content = new ArrayList<>(rawParts);
      }
      Map<String, Object> f = new LinkedHashMap<>(editedCc);
      f.put(CompressionHistory.RAW_OUTPUT, true);
      Part part = rawPart.copy();
      part.setMetadata(withCompressionMetadata(rawPart.getMetadata(), f));
      content.set(i, part);
    }
    if (content == null) {
      return raw;
    }
    Message updated = copyMessage(raw);
    updated.setContent(content);
    return updated;
  }

  /** Returns a copy of req carrying msgs. */
  static ModelRequest requestWith(ModelRequest req, List<Message> msgs) {
    ModelRequest updated = new ModelRequest();
    updated.setMessages(msgs);
    updated.setConfig(req.getConfig());
    updated.setTools(req.getTools());
    updated.setOutput(req.getOutput());
    updated.setContext(req.getContext());
    return updated;
  }

  /** Replaces the message of resp's first candidate, copying the candidate. */
  static void setMessage(ModelResponse resp, Message message) {
    List<Candidate> candidates = resp.getCandidates();
    if (candidates == null || candidates.isEmpty()) {
      return;
    }
    Candidate first = candidates.get(0);
    Candidate copy = new Candidate();
    copy.setIndex(first.getIndex());
    copy.setMessage(message);
    copy.setFinishReason(first.getFinishReason());
    copy.setFinishMessage(first.getFinishMessage());
    copy.setCustom(first.getCustom());
    List<Candidate> updated = new ArrayList<>(candidates);
    updated.set(0, copy);
    resp.setCandidates(updated);
  }

  static Model lookupModel(ActionContext ctx, String name) {
    Action<?, ?, ?> action = ctx.getRegistry().lookupAction(ActionType.MODEL, name);
    return action instanceof Model m ? m : null;
  }

  /** Reports whether e, or a cause, is the caller's cancellation. */
  static boolean isCancellation(Throwable e) {
    if (Thread.currentThread().isInterrupted()) {
      return true;
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof InterruptedException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Shrinks a preserve window of n messages when the context is far over budget: halved, but not
   * below 2, beyond 1.5 times; at most 2 beyond twice.
   */
  static int shrinkForOvershoot(double overshootRatio, int n) {
    if (overshootRatio >= 2) {
      return Math.min(n, 2);
    }
    if (overshootRatio >= 1.5) {
      return Math.min(n, Math.max(2, n / 2));
    }
    return n;
  }

  /** Estimates the tokens of chars characters. */
  static int charsToTokens(int chars) {
    return (int) Math.ceil(chars / CHARS_PER_TOKEN_ESTIMATE);
  }
}
