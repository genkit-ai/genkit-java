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
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.ai.FinishReason;
import com.google.genkit.ai.GenerateActionOptions;
import com.google.genkit.ai.InterruptedCall;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelObserver;
import com.google.genkit.ai.ModelRequest;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.ToolInterruptException;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.ToolResponse;
import com.google.genkit.ai.Usage;
import com.google.genkit.ai.agent.AgentSessionContext;
import com.google.genkit.ai.middleware.GenerateNext;
import com.google.genkit.ai.middleware.GenerateParams;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.ModelNext;
import com.google.genkit.ai.middleware.ModelParams;
import com.google.genkit.ai.middleware.ToolNext;
import com.google.genkit.ai.middleware.ToolParams;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caps the usage a generate run, or an agent session, may spend.
 *
 * <p><b>Experimental:</b> this middleware may change in any minor release.
 *
 * <p>{@link Options#limit} caps each of its non-zero fields, {@link Usage#getCustom()} key by key:
 * {@code inputTokens} and {@code outputTokens}, but also {@code thoughtsTokens}, {@code
 * outputImages}, or a provider-reported cost such as {@code custom.cost}. Usage counts as models
 * report it, so {@code outputTokens} leaves out {@code thoughtsTokens}: cap both to cap what a
 * model generates.
 *
 * <p>The limit is checked before each model call of the tool loop. Once a capped field reaches its
 * limit, the run stops with {@link BudgetExceededException}, which reports it as aborted rather
 * than failed and carries its partial response. A turn already under way finishes, so a run can
 * overshoot by one turn, and with a retry middleware in the chain, one turn can be several billed
 * calls. The check counts every model call made under the run: the tool loop, calls other
 * middleware makes, nested generates in tools, and sub-agents the run invokes, so delegation cannot
 * spend around it.
 *
 * <p>With {@link Options#interrupt} set, a run-scoped budget pauses instead: the tool calls that
 * would lead to the next turn are held, for the caller to decide whether to continue. The hold
 * happens only when the run is over its limit by the time its tool calls start: a run that goes
 * over while its tools run has nothing left to hold, and the check before the next model call stops
 * it. {@link #interrupted(Part)} claims each held call, {@link #exceeded(InterruptedCall)} reads
 * what it carries, and a restart with a {@link Decision} decides it. A response to a hold refuses
 * it:
 *
 * <pre>{@code
 * ResumeOptions.Builder resume = ResumeOptions.builder();
 * for (Part part : response.getInterrupts()) {
 *   BudgetMiddleware.interrupted(part)
 *       .ifPresent(call -> resume.part(call.restart(BudgetMiddleware.Decision.approve())));
 * }
 * }</pre>
 *
 * <p>A session-scoped budget counts the agent session's own usage across all its turns and
 * invocations, plus every call the run makes. It needs an agent with a session store, and cannot
 * pause. A run-scoped budget counts one run across its resumes: the run's final message records
 * what it spent, and a resume continues from there, so answering another middleware's interrupt
 * does not renew the limit; only approving the budget's own hold does.
 */
public class BudgetMiddleware implements GenerationMiddleware {

  private static final Logger logger = LoggerFactory.getLogger(BudgetMiddleware.class);

  /** The registered name of this middleware. */
  public static final String NAME = GenerationMiddlewarePlugin.EXPERIMENTAL_PREFIX + "budget";

  /** Counts every model call made under one generate call. */
  public static final String SCOPE_RUN = "run";

  /** Counts the agent session's usage across all its turns and invocations. */
  public static final String SCOPE_SESSION = "session";

  private final Options options;
  private final State state;

  /**
   * Creates the middleware.
   *
   * @param options the configuration (its limit must set at least one field)
   */
  public BudgetMiddleware(Options options) {
    this.options = options != null ? options : new Options();
    this.state = null;
  }

  private BudgetMiddleware(Options options, State state) {
    this.options = options;
    this.state = state;
  }

  @Override
  public String name() {
    return NAME;
  }

  /**
   * Validates the configuration and returns an instance with a fresh count. A session-scoped budget
   * reads the session's usage here, so it must be created inside the agent turn it limits.
   *
   * @throws GenkitException with error code {@code INVALID_ARGUMENT} for an invalid configuration,
   *     or {@code FAILED_PRECONDITION} for a session budget outside an agent with a session store
   */
  @Override
  public GenerationMiddleware newInstance() {
    return new BudgetMiddleware(options, newState(options));
  }

  private State state() {
    return state != null ? state : newState(options);
  }

  private static State newState(Options b) {
    Usage limitUsage = b.limit != null ? b.limit : new Usage();
    if (limitUsage.getCustom() != null) {
      for (Map.Entry<String, Double> e : limitUsage.getCustom().entrySet()) {
        Double v = e.getValue();
        if (v != null && (v.isNaN() || v.isInfinite())) {
          throw error(
              "INVALID_ARGUMENT", "limit custom." + e.getKey() + " must be a finite number");
        }
      }
    }
    Map<String, Double> limit = usageFields(limitUsage);
    for (Map.Entry<String, Double> e : new ArrayList<>(limit.entrySet())) {
      if (e.getValue() < 0) {
        throw error("INVALID_ARGUMENT", "limit " + e.getKey() + " must not be negative");
      }
      if (e.getValue() == 0) {
        limit.remove(e.getKey());
      }
    }
    if (limit.isEmpty()) {
      throw error("INVALID_ARGUMENT", "set at least one field of limit");
    }
    State s = new State(limit, b.interrupt);
    String scope = b.scope != null ? b.scope : "";
    switch (scope) {
      case "", SCOPE_RUN -> {}
      case SCOPE_SESSION -> {
        if (b.interrupt) {
          throw error("INVALID_ARGUMENT", "a session budget cannot interrupt");
        }
        Optional<Usage> u = AgentSessionContext.sessionUsage();
        if (u.isEmpty()) {
          throw error("FAILED_PRECONDITION", "session scope needs an agent with a session store");
        }
        s.session = u.get();
      }
      default -> throw error("INVALID_ARGUMENT", "unknown scope \"" + scope + "\"");
    }
    return s;
  }

  @Override
  public ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next)
      throws GenkitException {
    return state().wrapGenerate(ctx, params, next);
  }

  @Override
  public ModelResponse wrapModel(ActionContext ctx, ModelParams params, ModelNext next)
      throws GenkitException {
    return next.apply(ctx, params);
  }

  @Override
  public Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next) throws GenkitException {
    State s = state();
    if (!s.interrupt) {
      return next.apply(ctx, params);
    }
    return s.wrapTool(ctx, params, next);
  }

  /**
   * Claims part for the budget: returns the held call when part is a call a budget held, to be
   * answered with {@link InterruptedCall#restart} and a {@link Decision}. Every budget in a chain
   * claims the same holds. {@link InterruptedCall#respond} stands in for the tool's output, which
   * refuses the hold.
   *
   * @param part an interrupt part from {@link ModelResponse#getInterrupts()}
   * @return the held call, or empty when part is not a budget's hold
   */
  public static Optional<InterruptedCall<Decision>> interrupted(Part part) {
    return InterruptedCall.ofMiddleware(NAME, part);
  }

  /**
   * Returns what a budget hold carries: the limit reached, the usage spent, and the limit.
   *
   * @param call a call {@link #interrupted(Part)} claimed
   * @return the hold's data
   */
  public static Exceeded exceeded(InterruptedCall<Decision> call) {
    try {
      return JsonUtils.convert(call.getInterruptData(), Exceeded.class);
    } catch (RuntimeException e) {
      return new Exceeded();
    }
  }

  // ── Configuration and wire types ─────────────────────────────────────────────────

  /** Configuration for {@link BudgetMiddleware}. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public static class Options {

    @JsonProperty("limit")
    @JsonPropertyDescription(
        "Usage the run or session may spend. Each non-zero field is a cap, custom key by key.")
    public Usage limit;

    @JsonProperty("scope")
    @JsonPropertyDescription(
        "What the limit counts against: one generate run (run), or the agent session (session)."
            + " Defaults to run.")
    public String scope;

    @JsonProperty("interrupt")
    @JsonPropertyDescription(
        "Pause the run for approval instead of stopping it once a limit is reached. Run scope"
            + " only.")
    public boolean interrupt;

    /** Creates options with no limit set. */
    public Options() {}

    /**
     * Creates run-scoped options with the given limit.
     *
     * @param limit the usage the run may spend
     */
    public Options(Usage limit) {
      this.limit = limit;
    }
  }

  /** The data a budget hold carries. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static final class Exceeded {

    /** Says which limit the run reached. */
    @JsonProperty("message")
    public String message;

    /**
     * What the run spent, for each capped field by its JSON name ("inputTokens", "custom.cost").
     */
    @JsonProperty("spent")
    public Map<String, Double> spent;

    /** The limit, for each capped field by its JSON name. */
    @JsonProperty("limit")
    public Map<String, Double> limit;

    /** Default constructor for JSON deserialization. */
    public Exceeded() {}
  }

  /**
   * The answer to a budget hold. The holds of one run share one decision: the run continues only if
   * no restart of them refuses.
   */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public static final class Decision {

    /**
     * True continues the run with the full limit again; false stops it with {@link
     * BudgetExceededException} before it runs the held calls.
     */
    @JsonProperty("budgetApproved")
    public boolean approved;

    /** When not approved, added to the error the run stops with. */
    @JsonProperty("reason")
    public String reason;

    /** Default constructor for JSON deserialization; refuses. */
    public Decision() {}

    /**
     * Creates a decision.
     *
     * @param approved whether to continue the run
     * @param reason the reason for a refusal, or null
     */
    public Decision(boolean approved, String reason) {
      this.approved = approved;
      this.reason = reason;
    }

    /**
     * Returns a decision that continues the run with the full limit again.
     *
     * @return the approval
     */
    public static Decision approve() {
      return new Decision(true, null);
    }

    /**
     * Returns a decision that stops the run.
     *
     * @param reason the reason, added to the error the run stops with; may be null
     * @return the refusal
     */
    public static Decision refuse(String reason) {
      return new Decision(false, reason);
    }
  }

  // ── Per-call state ──────────────────────────────────────────────────────────────

  /** One generate call's count. The observer and the tool hook may run concurrently. */
  static final class State {
    final Map<String, Double> limit;
    final boolean interrupt;

    /** The agent session's usage when the run started; null for run scope. */
    Usage session;

    /** Set when the resume refuses one of the budget's holds. */
    Decision refusal;

    /** Counts every model call under the run, sub-agents included. */
    private Usage tree;

    State(Map<String, Double> limit, boolean interrupt) {
      this.limit = limit;
      this.interrupt = interrupt;
    }

    ModelResponse wrapGenerate(ActionContext ctx, GenerateParams params, GenerateNext next) {
      boolean first = params.getIteration() == 0;
      GenerateActionOptions request = params.getRequest();
      if (first) {
        if (session == null) {
          resume(request);
        }
        if (refusal != null) {
          logger.debug("run stopped by budget: continuing was refused: {}", refusal.reason);
          String msg = "continuing past the limit was refused";
          if (refusal.reason != null && !refusal.reason.isEmpty()) {
            msg += ": " + refusal.reason;
          }
          throw stop(msg, request);
        }
      }
      // A resume's first turn runs the restarted calls before any model call, so the check waits
      // for the turn after it; with interrupt set, the tool hook holds those calls if the run is
      // over its limit.
      boolean resuming = first && request.getResume() != null;
      String over = exceeded();
      if (over != null && !resuming) {
        if (interrupt) {
          // Over with no hold means the run went over while its tools ran.
          over += "; the limit was reached while tools ran, so the run could not pause";
        }
        logger.debug("run stopped by budget: {}", over);
        throw stop(over, request);
      }
      if (!first) {
        return next.apply(ctx, params);
      }
      ModelResponse resp = ModelObserver.observe(this::count, () -> next.apply(ctx, params));
      if (session == null && resp != null && resp.getMessage() != null) {
        Usage spent;
        synchronized (this) {
          spent = Usage.sum(tree);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        if (spent != null) {
          record.put("spent", JsonUtils.getObjectMapper().convertValue(spent, Map.class));
        }
        // A copy, so the record never lands on a message the caller holds.
        Message msg = CompressionHistory.copyMessage(resp.getMessage());
        Map<String, Object> md =
            resp.getMessage().getMetadata() != null
                ? new LinkedHashMap<>(resp.getMessage().getMetadata())
                : new LinkedHashMap<>();
        md.put(NAME, record);
        msg.setMetadata(md);
        ContextCompressionMiddleware.setMessage(resp, msg);
      }
      return resp;
    }

    /**
     * Continues the count of the run a resume continues, from the record on its last model message,
     * unless the resume restarts one of the budget's holds, which decides the run: approved, the
     * limit is renewed; refused, the run stops. A response directive for a hold refuses it. A call
     * that is not a resume starts from zero.
     */
    private void resume(GenerateActionOptions request) {
      ResumeOptions opts = request.getResume();
      List<Message> msgs = request.getMessages();
      if (opts == null || msgs == null || msgs.isEmpty()) {
        return;
      }
      int i = msgs.size() - 1;
      while (i >= 0 && msgs.get(i) != null && msgs.get(i).getRole() == Role.TOOL) {
        i--;
      }
      if (i < 0 || msgs.get(i) == null || msgs.get(i).getRole() != Role.MODEL) {
        return;
      }
      Message last = msgs.get(i);
      List<ToolRequest> restarts = opts.getRestart() != null ? opts.getRestart() : List.of();
      List<ToolResponse> responds = opts.getRespond() != null ? opts.getRespond() : List.of();
      boolean approved = false;
      for (Part p : CompressionHistory.partsOf(last)) {
        if (p == null || p.getToolRequest() == null || p.getMetadata() == null) {
          continue;
        }
        ToolRequest req = p.getToolRequest();
        if (interrupted(p).isEmpty()) {
          continue;
        }
        for (ToolRequest a : restarts) {
          if (!answers(a.getName(), a.getRef(), req)) {
            continue;
          }
          Decision d = decision(a);
          if (d.approved) {
            approved = true;
          } else if (refusal == null) {
            refusal = d;
          }
        }
        if (refusal == null) {
          for (ToolResponse a : responds) {
            if (answers(a.getName(), a.getRef(), req)) {
              refusal =
                  new Decision(
                      false, "the hold was answered with a response, which does not approve it");
              break;
            }
          }
        }
      }
      if (approved || refusal != null) {
        return;
      }
      Object rec = last.getMetadata() != null ? last.getMetadata().get(NAME) : null;
      Map<String, Object> record = CompressionHistory.asMap(rec);
      if (record == null || record.get("spent") == null) {
        return;
      }
      Usage spent;
      try {
        spent = JsonUtils.convert(record.get("spent"), Usage.class);
      } catch (RuntimeException e) {
        return;
      }
      synchronized (this) {
        tree = Usage.sum(tree, spent);
      }
    }

    /** Adds a model call's usage to the run's count. */
    private void count(ModelObserver.ModelCall call) {
      if (call.response() != null && call.response().getUsage() != null) {
        synchronized (this) {
          tree = Usage.sum(tree, call.response().getUsage());
        }
      }
    }

    /**
     * Holds the call once the run reached its limit. A restart answering a later stage, such as the
     * tool's own question, passes a call this hook already let through.
     */
    Part wrapTool(ActionContext ctx, ToolParams params, ToolNext next) {
      String over = exceeded();
      ToolRequest req = params.getRequest();
      if (over == null || params.isReleased()) {
        return next.apply(ctx, params);
      }
      logger.debug("tool held by budget: tool={} reason={}", req.getName(), over);
      Map<String, Double> spent = spent();
      Map<String, Double> used = new LinkedHashMap<>();
      for (String k : new TreeMap<>(limit).keySet()) {
        used.put(k, spent.getOrDefault(k, 0.0));
      }
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("message", "Usage budget reached: " + over);
      data.put("spent", used);
      data.put("limit", new LinkedHashMap<>(new TreeMap<>(limit)));
      throw new ToolInterruptException(data);
    }

    /** Returns what counts against the budget, flattened by {@link #usageFields(Usage)}. */
    private Map<String, Double> spent() {
      Map<String, Double> spent;
      synchronized (this) {
        spent = usageFields(tree);
      }
      for (Map.Entry<String, Double> e : usageFields(session).entrySet()) {
        spent.merge(e.getKey(), e.getValue(), Double::sum);
      }
      return spent;
    }

    /** Returns which capped field reached its limit, or null. */
    private String exceeded() {
      Map<String, Double> spent = spent();
      for (Map.Entry<String, Double> e : new TreeMap<>(limit).entrySet()) {
        double used = spent.getOrDefault(e.getKey(), 0.0);
        if (used >= e.getValue()) {
          return "used " + format(used) + " of " + format(e.getValue()) + " " + e.getKey();
        }
      }
      return null;
    }

    private static BudgetExceededException stop(String msg, GenerateActionOptions request) {
      ModelResponse partial = new ModelResponse(new ArrayList<>());
      partial.setRequest(
          new ModelRequest(
              request.getMessages() != null
                  ? new ArrayList<>(request.getMessages())
                  : new ArrayList<>()));
      partial.setFinishReason(FinishReason.ABORTED);
      partial.setFinishMessage("budget: " + msg);
      return new BudgetExceededException("budget: " + msg, partial);
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────

  /**
   * Reads the decision a restart carries. A bare restart, or one whose payload does not decode,
   * refuses.
   */
  static Decision decision(ToolRequest restart) {
    Object resumed =
        restart.getMetadata() != null
            ? restart.getMetadata().get(InterruptedCall.RESUMED_KEY)
            : null;
    if (resumed instanceof Decision d) {
      return d;
    }
    if (!(resumed instanceof Map)) {
      return new Decision();
    }
    try {
      return JsonUtils.convert(resumed, Decision.class);
    } catch (RuntimeException e) {
      return new Decision();
    }
  }

  /** Reports whether a directive answers req, matched on tool name and ref. */
  private static boolean answers(String name, String ref, ToolRequest req) {
    return Objects.equals(nullToEmpty(name), nullToEmpty(req.getName()))
        && Objects.equals(nullToEmpty(ref), nullToEmpty(req.getRef()));
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  /**
   * Flattens u into its numeric fields by JSON name, with custom keys as "custom.key", so a field
   * added to {@link Usage} is capped with no change here. A null u flattens to an empty map.
   */
  static Map<String, Double> usageFields(Usage u) {
    Map<String, Double> out = new LinkedHashMap<>();
    if (u == null) {
      return out;
    }
    Map<?, ?> m;
    try {
      m = JsonUtils.getObjectMapper().convertValue(u, Map.class);
    } catch (RuntimeException e) {
      return out;
    }
    for (Map.Entry<?, ?> e : m.entrySet()) {
      String k = String.valueOf(e.getKey());
      Object v = e.getValue();
      if (v instanceof Number n) {
        out.put(k, n.doubleValue());
      } else if (v instanceof Map<?, ?> custom) {
        for (Map.Entry<?, ?> ce : custom.entrySet()) {
          if (ce.getValue() instanceof Number n) {
            out.put(k + "." + ce.getKey(), n.doubleValue());
          }
        }
      }
    }
    return out;
  }

  private static String format(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v)) {
      return String.valueOf(v);
    }
    return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
  }

  private static GenkitException error(String code, String message) {
    return GenkitException.builder().message("budget: " + message).errorCode(code).build();
  }
}
