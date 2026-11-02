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

import static com.google.genkit.plugins.middleware.CompressionTestSupport.ViewRecorder;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.cc;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.map;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.modelMsg;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.stats;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.statsOrEmpty;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.text;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.texts;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.toolMessages;
import static com.google.genkit.plugins.middleware.CompressionTestSupport.userMsg;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.ResumeOptions;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.Tool;
import com.google.genkit.ai.ToolInterruptException;
import com.google.genkit.ai.ToolRequest;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.AgentFn;
import com.google.genkit.ai.agent.AgentInit;
import com.google.genkit.ai.agent.AgentInput;
import com.google.genkit.ai.agent.AgentOutput;
import com.google.genkit.ai.agent.AgentResult;
import com.google.genkit.ai.agent.CustomAgentConfig;
import com.google.genkit.ai.agent.GetSnapshotRequest;
import com.google.genkit.ai.agent.InMemorySessionStore;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs {@link ContextCompressionMiddleware} against a live model.
 *
 * <p>To run: {@code GEMINI_API_KEY=... mvn -pl plugins/middleware -am test
 * -Dtest=ContextCompressionLiveTest -Dsurefire.failIfNoSpecifiedTests=false}. Skipped unless {@code
 * GEMINI_API_KEY} or {@code GOOGLE_API_KEY} is set.
 */
class ContextCompressionLiveTest {

  private static final String LIVE_MODEL = "googleai/gemini-2.5-flash";
  private static final String LIVE_SUMMARIZER = "googleai/gemini-2.5-flash-lite";

  /**
   * The system prompt of the research loops. Without the last sentence, models read a shortened
   * result as a failed fetch and fetch the report again.
   */
  private static final String RESEARCH_SYSTEM =
      "You are a research assistant. Search for reports first, then fetch every report, one"
          + " fetchReport call per turn, then write a short summary of all of them. Older tool"
          + " results are shortened to save space, which is expected: never fetch a report twice.";

  private static String apiKey;

  @BeforeAll
  static void requireKey() {
    String key = System.getenv("GEMINI_API_KEY");
    if (key == null || key.isEmpty()) {
      key = System.getenv("GOOGLE_API_KEY");
    }
    apiKey = key;
  }

  private static Genkit newGenkit(boolean experimental) {
    assumeTrue(
        apiKey != null && !apiKey.isEmpty(),
        "set GEMINI_API_KEY or GOOGLE_API_KEY to run the live test");
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().experimental(experimental).build())
            .plugin(GoogleGenAIPlugin.create(apiKey))
            .build();
    return genkit;
  }

  /** Returns a long, distinct body for a report. */
  private static String reportDetails(String id) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      sb.append("Section ")
          .append(i)
          .append(" of ")
          .append(id)
          .append(": latency, throughput, error budgets and rollout notes for cluster us-central-")
          .append(i)
          .append(". ");
    }
    return sb.toString();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Tool<Map<String, Object>, Object> tool(
      Genkit genkit,
      String name,
      String description,
      java.util.function.BiFunction<ActionContext, Map<String, Object>, Object> fn) {
    Class<Map<String, Object>> in = (Class) Map.class;
    return genkit.defineTool(name, description, fn, in, Object.class);
  }

  private static Tool<Map<String, Object>, Object> searchReports(Genkit genkit) {
    return tool(
        genkit,
        "searchReports",
        "Searches for report IDs about a topic.",
        (ctx, in) -> List.of("report-101", "report-102", "report-103"));
  }

  private static Tool<Map<String, Object>, Object> fetchReport(Genkit genkit) {
    return tool(
        genkit,
        "fetchReport",
        "Fetches the full text of one report by ID.",
        (ctx, in) -> {
          String id = String.valueOf(in.get("reportId"));
          return map("id", id, "title", "Project Alpha " + id, "details", reportDetails(id));
        });
  }

  /** Reports a fetchReport response without its full output; returns how many it found. */
  @SuppressWarnings("unchecked")
  private static int checkFullReports(List<Message> history) {
    int fetched = 0;
    for (Message m : toolMessages(history)) {
      for (Part p : m.getContent()) {
        if (!"fetchReport".equals(p.getToolResponse().getName())) {
          continue;
        }
        fetched++;
        Map<String, Object> out = (Map<String, Object>) p.getToolResponse().getOutput();
        assertEquals(
            reportDetails(String.valueOf(out.get("id"))),
            out.get("details"),
            "history lost the full output of " + out.get("id"));
      }
    }
    return fetched;
  }

  /** Checks the last view against what the history before its response resolves to. */
  private static List<Message> checkLastViewResolves(ViewRecorder rec, List<Message> history) {
    List<Message> last = rec.views.get(rec.views.size() - 1);
    assertEquals(
        texts(last),
        texts(
            ContextCompressionMiddleware.resolveCompressedHistory(
                history.subList(0, history.size() - 1))));
    return last;
  }

  @Test
  void researchLoopCompressesMidRunAndKeepsTheHistory() {
    Genkit genkit = newGenkit(false);
    ViewRecorder rec = new ViewRecorder();
    ContextCompressionMiddleware.Options o = new ContextCompressionMiddleware.Options();
    o.maxInputTokens = 900;
    o.deduplicateToolResponses = new ContextCompressionMiddleware.Dedupe();
    o.toolResponses = new ContextCompressionMiddleware.ToolTruncation(200);
    o.toolResponses.preserveRecent = 1;
    o.summarize = new ContextCompressionMiddleware.Summarizer(LIVE_SUMMARIZER);
    o.summarize.preserveRecent = 2;
    o.skipSummarizationThreshold = 0.3;
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.<Void>builder()
                .model(LIVE_MODEL)
                .system(RESEARCH_SYSTEM)
                .prompt("Investigate Project Alpha.")
                .tools(List.of(searchReports(genkit), fetchReport(genkit)))
                .maxTurns(10)
                .use(new ContextCompressionMiddleware(o), rec)
                .build());
    assertFalse(resp.getText().isBlank(), "empty final answer");
    Map<String, Object> s = stats(resp);
    assertNotNull(s, "want a compression");
    assertEquals(true, s.get("triggered"));

    List<Message> history = resp.getMessages();
    assertTrue(checkFullReports(history) > 0, "the model fetched no report");
    for (int i = 0; i < history.size() - 1; i++) {
      Message m = history.get(i);
      if (m.getRole() == Role.MODEL) {
        assertNotNull(
            cc(m.getMetadata()) != null
                ? cc(m.getMetadata()).get(CompressionHistory.INPUT_TOKENS)
                : null,
            "model message " + i + " has no inputTokens stamp");
      }
    }
    List<Message> last = checkLastViewResolves(rec, history);
    if (Boolean.TRUE.equals(s.get("summarized"))) {
      assertTrue(last.size() < history.size() - 1, "want fewer messages after a summary");
    }
  }

  @Test
  void streamingToolLoopKeepsTheHistory() {
    Genkit genkit = newGenkit(false);
    ViewRecorder rec = new ViewRecorder();
    AtomicInteger chunks = new AtomicInteger();
    ContextCompressionMiddleware.Options o = new ContextCompressionMiddleware.Options();
    o.maxInputTokens = 900;
    o.toolResponses = new ContextCompressionMiddleware.ToolTruncation(200);
    o.toolResponses.preserveRecent = 1;
    ModelResponse resp =
        genkit.generateStream(
            GenerateOptions.<Void>builder()
                .model(LIVE_MODEL)
                .system(RESEARCH_SYSTEM)
                .prompt("Investigate Project Alpha.")
                .tools(List.of(searchReports(genkit), fetchReport(genkit)))
                .maxTurns(10)
                .use(new ContextCompressionMiddleware(o), rec)
                .build(),
            c -> chunks.incrementAndGet());
    assertTrue(chunks.get() > 0, "no chunks streamed");
    Object truncated = statsOrEmpty(resp).get("toolResponsesTruncated");
    assertNotNull(truncated, "want truncated responses");
    assertNotEquals(0, truncated);
    List<Message> history = resp.getMessages();
    checkFullReports(history);
    checkLastViewResolves(rec, history);
  }

  @Test
  void resumeAfterAnInterruptKeepsToolPairs() {
    Genkit genkit = newGenkit(false);
    Tool<Map<String, Object>, Object> publish =
        tool(
            genkit,
            "publish",
            "Publishes a one-line summary of the reports. Needs approval.",
            (ctx, in) -> {
              if (!ctx.isResumed()) {
                throw new ToolInterruptException(map("reason", "needs approval"));
              }
              return "published";
            });
    ContextCompressionMiddleware.Options o = new ContextCompressionMiddleware.Options();
    o.maxInputTokens = 700;
    o.toolResponses = new ContextCompressionMiddleware.ToolTruncation(150);
    o.toolResponses.preserveRecent = 1;
    ContextCompressionMiddleware mw = new ContextCompressionMiddleware(o);
    ViewRecorder rec = new ViewRecorder();
    List<Tool<?, ?>> tools = List.of(fetchReport(genkit), publish);
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.<Void>builder()
                .model(LIVE_MODEL)
                .tools(tools)
                .maxTurns(10)
                .use(mw, rec)
                .system(
                    "Fetch report-101, report-102 and report-103 with fetchReport, one call per"
                        + " turn. Then call publish once with a one-line summary, and reply with"
                        + " one word when it is published.")
                .prompt("Go.")
                .build());
    List<Part> interrupts = resp.getInterrupts();
    assertEquals(1, interrupts.size(), "finish " + resp.getFinishReason());
    assertEquals("publish", interrupts.get(0).getToolRequest().getName());
    int paused = rec.views.size();
    ToolRequest restart =
        new ToolRequest(
            interrupts.get(0).getToolRequest().getName(),
            interrupts.get(0).getToolRequest().getInput());
    restart.setRef(interrupts.get(0).getToolRequest().getRef());
    restart.setMetadata(map("resumed", map("approved", true)));
    resp =
        genkit.generate(
            GenerateOptions.<Void>builder()
                .model(LIVE_MODEL)
                .tools(tools)
                .maxTurns(10)
                .use(mw, rec)
                .messages(resp.getMessages())
                .resume(ResumeOptions.builder().restart(restart).build())
                .build());
    assertTrue(rec.views.size() > paused, "the resumed call never reached the model");
    for (int i = 0; i < rec.views.size(); i++) {
      List<Message> view = rec.views.get(i);
      for (int j = 0; j < view.size(); j++) {
        if (view.get(j).getRole() == Role.TOOL) {
          assertTrue(
              j > 0
                  && (view.get(j - 1).getRole() == Role.MODEL
                      || view.get(j - 1).getRole() == Role.TOOL),
              "call "
                  + i
                  + " view = "
                  + texts(view)
                  + ", want every tool message after its request");
        }
      }
    }
    boolean published = false;
    for (Message m : toolMessages(resp.getMessages())) {
      for (Part p : m.getContent()) {
        published =
            published
                || ("publish".equals(p.getToolResponse().getName())
                    && "published".equals(p.getToolResponse().getOutput()));
      }
    }
    assertTrue(published, "history holds no published response");
  }

  @Test
  void agentTurnsCompressAcrossTheSession() {
    Genkit genkit = newGenkit(true);
    ContextCompressionMiddleware.Options o = new ContextCompressionMiddleware.Options();
    o.maxInputTokens = 500;
    o.summarize = new ContextCompressionMiddleware.Summarizer(LIVE_SUMMARIZER);
    o.summarize.preserveRecent = 2;
    ContextCompressionMiddleware mw = new ContextCompressionMiddleware(o);
    AgentFn<Map<String, Object>> fn =
        (sess, fnCtx) -> {
          ModelResponse r =
              genkit.generate(
                  GenerateOptions.<Void>builder()
                      .model(LIVE_MODEL)
                      .system(
                          "You are a concise assistant. Acknowledge notes in one short sentence.")
                      .messages(sess.getMessages())
                      .use(mw)
                      .build());
          // The session keeps the compressed history, so later turns continue from it.
          List<Message> history = new ArrayList<>(r.getRequest().getMessages());
          history.removeIf(m -> m.getRole() == Role.SYSTEM);
          sess.session().setMessages(history);
          return AgentResult.builder().message(r.getMessage()).build();
        };
    Agent<Map<String, Object>> agent =
        genkit
            .beta()
            .defineCustomAgent(
                CustomAgentConfig.<Map<String, Object>>builder()
                    .name("notes")
                    .store(new InMemorySessionStore<>())
                    .build(),
                fn);
    List<String> turns = new ArrayList<>();
    turns.add("Remember this for later: my project codename is BLUE-HERON-7.");
    for (int i = 0; i < 3; i++) {
      turns.add("Background note " + i + ": " + reportDetails("note-" + i));
    }
    turns.add("What is my project codename? Answer with the codename only.");
    ActionContext ctx = new ActionContext(genkit.getRegistry());
    String sessionId = null;
    AgentOutput<Map<String, Object>> out = null;
    for (int i = 0; i < turns.size(); i++) {
      AgentInit<Map<String, Object>> init =
          sessionId != null
              ? AgentInit.<Map<String, Object>>builder().sessionId(sessionId).build()
              : new AgentInit<>();
      BufferedInputSource<AgentInput> in = new BufferedInputSource<>();
      in.offer(AgentInput.builder().message(Message.user(turns.get(i))).build());
      in.end();
      out = agent.runBidi(ctx, init, in, null);
      assertNull(
          out.getError(),
          "turn " + i + " failed: " + (out.getError() != null ? out.getError().getMessage() : ""));
      sessionId = out.getSessionId();
    }
    assertTrue(text(out.getMessage()).contains("BLUE-HERON-7"), text(out.getMessage()));
    SessionSnapshot<Map<String, Object>> snap =
        agent.getSnapshotData(GetSnapshotRequest.builder().sessionId(sessionId).build());
    int boundaries = 0;
    for (Message m : snap.getState().getMessages()) {
      if (cc(m.getMetadata()) != null
          && cc(m.getMetadata()).containsKey(CompressionHistory.SUMMARY)) {
        boundaries++;
      }
    }
    assertTrue(boundaries > 0, "session messages carry no boundary");
  }

  @Test
  void summaryCarriesFactsIntoLaterCalls() {
    Genkit genkit = newGenkit(false);
    ContextCompressionMiddleware.Options o = new ContextCompressionMiddleware.Options();
    o.maxInputTokens = 400;
    o.summarize = new ContextCompressionMiddleware.Summarizer(LIVE_SUMMARIZER);
    o.summarize.preserveRecent = 2;
    ContextCompressionMiddleware mw = new ContextCompressionMiddleware(o);
    List<Message> history = new ArrayList<>();
    history.add(userMsg("Remember this for later: my project codename is BLUE-HERON-7."));
    history.add(modelMsg("Noted. Your project codename is BLUE-HERON-7."));
    for (int i = 0; i < 4; i++) {
      history.add(userMsg("Background note " + i + ": " + reportDetails("note-" + i)));
      history.add(modelMsg("Thanks, I have read background note " + i + "."));
    }
    history.add(userMsg("What is my project codename? Answer with the codename only."));

    ViewRecorder rec = new ViewRecorder();
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.<Void>builder()
                .model(LIVE_MODEL)
                .messages(history)
                .use(mw, rec)
                .build());
    assertEquals(true, statsOrEmpty(resp).get("summarized"), String.valueOf(stats(resp)));
    List<Message> view = rec.views.get(0);
    assertFalse(text(view.get(0)).contains("Remember this for later"));
    assertTrue(CompressionHistory.hasMessageFlag(view.get(0), CompressionHistory.SUMMARY_MESSAGE));
    assertTrue(resp.getText().contains("BLUE-HERON-7"), resp.getText());

    // The next call continues from the stamped history.
    List<Message> next = new ArrayList<>(resp.getMessages());
    next.add(userMsg("Spell the codename backwards."));
    rec.views.clear();
    genkit.generate(
        GenerateOptions.<Void>builder().model(LIVE_MODEL).messages(next).use(mw, rec).build());
    assertTrue(
        CompressionHistory.hasMessageFlag(
            rec.views.get(0).get(0), CompressionHistory.SUMMARY_MESSAGE));
  }

  @Test
  void messageCapKeepsAValidToolConversation() {
    Genkit genkit = newGenkit(false);
    // A hard message cap drops the turns that recorded progress, so the tool tracks progress
    // itself and serves the next page whatever cursor the model passes.
    AtomicInteger pagesRead = new AtomicInteger();
    Tool<Map<String, Object>, Object> readPage =
        tool(
            genkit,
            "readPage",
            "Reads the next page of the Project Alpha audit.",
            (ctx, in) -> {
              if (pagesRead.get() == 4) {
                return map("done", true, "text", "No more pages. Reply now.");
              }
              int page = pagesRead.incrementAndGet();
              return map("page", page, "of", 4, "text", reportDetails("page-" + page));
            });
    ViewRecorder rec = new ViewRecorder();
    ContextCompressionMiddleware.Options o = new ContextCompressionMiddleware.Options();
    o.maxMessages = 5;
    ModelResponse resp =
        genkit.generate(
            GenerateOptions.<Void>builder()
                .model(LIVE_MODEL)
                .system(
                    "Read the audit with readPage, one call per turn, until it reports no more"
                        + " pages. Then reply with one sentence about the audit.")
                .prompt("Read the Project Alpha audit.")
                .tools(List.of(readPage))
                .maxTurns(10)
                .use(new ContextCompressionMiddleware(o), rec)
                .build());
    assertEquals(true, statsOrEmpty(resp).get("triggered"));
    for (int i = 0; i < rec.views.size(); i++) {
      List<Message> view = rec.views.get(i);
      assertTrue(view.size() <= 5, "call " + i + " received " + view.size() + " messages");
      if (view.size() > 1) {
        assertEquals(Role.USER, view.get(1).getRole(), "call " + i + " view = " + texts(view));
      }
    }
  }
}
