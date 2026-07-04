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

package com.google.genkit.conformance.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.genkit.ai.agent.Agent;
import com.google.genkit.ai.agent.GetSnapshotRequest;
import com.google.genkit.ai.agent.SessionSnapshot;
import com.google.genkit.ai.agent.SnapshotStatus;
import com.google.genkit.core.ActionContext;
import com.google.genkit.core.BufferedInputSource;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Cross-language agent conformance harness, driven by {@code conformance/agent.yaml} (a verbatim
 * copy of the upstream {@code tests/specs/agent.yaml}).
 *
 * <p>The harness ports the JS ({@code js/ai/tests/agents_spec_test.ts}) and Go ({@code
 * go/ai/exp/agents_conformance_test.go}) reference runners. It drives the in-process Agent API:
 * {@code agent.runBidiJson(ctx, init, inputs, chunkSink)} for {@code send}, {@code
 * agent.getSnapshotData(...)} for snapshot lookups, and {@code agent.abort(...)} for aborts.
 * Everything is matched as JSON (the wire format), mirroring the Go harness's canonical-JSON
 * approach, since every Java agent wire type serializes to the exact spec field names.
 *
 * <p>Each case is a dynamic test. A case may PASS, SKIP (a documented first-cut gap — e.g.
 * prompt-agent interrupt/restart, tool-response stream chunks), or FAIL (a real mismatch). Skips do
 * not fail the suite; the class passes overall and prints a {@code N passed / M skipped / K failed}
 * summary with per-case reasons.
 */
class AgentConformanceTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
  private static final ObjectMapper JSON = JsonUtils.getObjectMapper();

  // ── result accounting ────────────────────────────────────────────────────────────

  private enum Outcome {
    PASS,
    SKIP,
    FAIL
  }

  private record CaseResult(String name, Outcome outcome, String reason) {}

  private static final List<CaseResult> RESULTS = new ArrayList<>();

  /** Thrown by a step runner to mark the whole case as a documented skip. */
  private static final class SkipException extends RuntimeException {
    SkipException(String reason) {
      super(reason);
    }
  }

  // ── test factory ──────────────────────────────────────────────────────────────────

  @TestFactory
  Iterable<DynamicTest> agentConformance() throws Exception {
    JsonNode suite;
    try (InputStream in =
        AgentConformanceTest.class.getResourceAsStream("/conformance/agent.yaml")) {
      if (in == null) {
        throw new IllegalStateException("conformance/agent.yaml not found on the test classpath");
      }
      suite = YAML.readTree(in);
    }
    JsonNode tests = suite.get("tests");
    assertFalse(tests == null || tests.isEmpty(), "spec contains no tests");

    List<DynamicTest> dynamic = new ArrayList<>();
    for (JsonNode tc : tests) {
      String name = tc.get("name").asText();
      dynamic.add(
          DynamicTest.dynamicTest(
              name,
              () -> {
                try {
                  runCase(tc);
                  RESULTS.add(new CaseResult(name, Outcome.PASS, null));
                } catch (SkipException se) {
                  RESULTS.add(new CaseResult(name, Outcome.SKIP, se.getMessage()));
                } catch (AssertionError | Exception e) {
                  RESULTS.add(new CaseResult(name, Outcome.FAIL, oneLine(e.getMessage())));
                  // Re-throw so the dynamic test is reported as failed individually, but the
                  // suite-level @AfterAll guard below decides whether the build fails.
                  throw e;
                }
              }));
    }
    return dynamic;
  }

  /**
   * Runs one case's steps in order, stopping at the first failed step (later steps need captures).
   */
  private void runCase(JsonNode tc) throws Exception {
    String agentName = tc.get("agent").asText();
    Fixtures fixtures = new Fixtures();
    Agent<Map<String, Object>> agent = fixtures.agent(agentName);
    if (agent == null) {
      throw new AssertionError("unknown agent '" + agentName + "'");
    }
    ActionContext ctx = new ActionContext(fixtures.genkit().getRegistry());
    Map<String, JsonNode> captures = new HashMap<>();

    JsonNode steps = tc.get("steps");
    for (int i = 0; i < steps.size(); i++) {
      JsonNode step = steps.get(i);
      String type = step.get("type").asText();
      String label = "step[" + i + "] (" + type + ")";
      switch (type) {
        case "send" -> runSend(label, fixtures, agent, ctx, step, captures);
        case "getSnapshotData" -> runGetSnapshotData(label, agent, step, captures);
        case "abort" -> runAbort(label, agent, step, captures);
        case "waitUntilCompleted" -> runWaitUntilCompleted(label, agent, step, captures);
        default -> throw new AssertionError(label + ": unknown step type '" + type + "'");
      }
    }
  }

  // ── send ────────────────────────────────────────────────────────────────────────

  private void runSend(
      String label,
      Fixtures fixtures,
      Agent<Map<String, Object>> agent,
      ActionContext ctx,
      JsonNode rawStep,
      Map<String, JsonNode> captures) {
    JsonNode step = resolve(rawStep, captures);

    // Program the model for this step.
    List<JsonNode> modelResponses = toNodeList(step.get("modelResponses"));
    List<List<JsonNode>> streamChunks = new ArrayList<>();
    if (step.has("streamChunks")) {
      for (JsonNode perCall : step.get("streamChunks")) {
        streamChunks.add(toNodeList(perCall));
      }
    }
    fixtures.programmableModel().program(modelResponses, streamChunks);

    // Build init JSON from the step's init (empty object when absent).
    JsonNode init =
        step.has("init") && !step.get("init").isNull() ? step.get("init") : JSON.createObjectNode();

    // Feed inputs.
    BufferedInputSource<JsonNode> inputs = new BufferedInputSource<>();
    if (step.has("inputs")) {
      for (JsonNode input : step.get("inputs")) {
        inputs.offer(input);
      }
    }
    inputs.end();

    List<JsonNode> chunks = new ArrayList<>();
    JsonNode output = null;
    GenkitException thrown = null;
    try {
      output = agent.runBidiJson(ctx, init, inputs, chunks::add);
    } catch (GenkitException ge) {
      thrown = ge;
    }

    // expectError: API misuse — the turn must throw; assert the status (not the message).
    if (step.has("expectError")) {
      JsonNode ee = step.get("expectError");
      if (thrown == null) {
        throw new AssertionError(label + ": expected the turn to throw, but it resolved");
      }
      if (ee.has("status")) {
        String want = ee.get("status").asText();
        String got = thrown.getErrorCode();
        if (!want.equals(got)) {
          throw new AssertionError(
              label
                  + ": expectError.status: want '"
                  + want
                  + "', got '"
                  + got
                  + "' (msg: "
                  + thrown.getMessage()
                  + ")");
        }
      }
      return;
    }

    if (thrown != null) {
      throw new AssertionError(label + ": invocation threw unexpectedly: " + thrown.getMessage());
    }

    // expectChunks: semi-strict ordered comparison.
    if (step.has("expectChunks")) {
      assertChunks(label, chunks, step.get("expectChunks"));
    }

    // expectOutput.
    if (step.has("expectOutput")) {
      assertOutput(label, output, step.get("expectOutput"));
    }

    // captures.
    final JsonNode out = output;
    if (out != null) {
      capture(
          rawStep, "captureSnapshotId", () -> textOrNull(out.get("snapshotId")), captures, label);
      capture(rawStep, "captureSessionId", () -> stateSessionId(out), captures, label);
      if (rawStep.has("captureState")) {
        JsonNode state = out.get("state");
        if (state == null || state.isNull()) {
          throw new AssertionError(label + ": captureState requested but output has no state");
        }
        captures.put(rawStep.get("captureState").asText(), state);
      }
    }
  }

  // ── getSnapshotData ───────────────────────────────────────────────────────────────

  private void runGetSnapshotData(
      String label,
      Agent<Map<String, Object>> agent,
      JsonNode rawStep,
      Map<String, JsonNode> captures) {
    JsonNode step = resolve(rawStep, captures);
    String snapshotId = textOrNull(step.get("snapshotId"));
    String sessionId = textOrNull(step.get("sessionId"));
    if ((snapshotId == null) == (sessionId == null)) {
      throw new AssertionError(label + ": requires exactly one of snapshotId / sessionId");
    }

    GetSnapshotRequest.Builder req = GetSnapshotRequest.builder();
    if (snapshotId != null) {
      req.snapshotId(snapshotId);
    } else {
      req.sessionId(sessionId);
    }

    SessionSnapshot<Map<String, Object>> snap;
    try {
      snap = agent.getSnapshotData(req.build());
    } catch (RuntimeException e) {
      if (step.has("expectError")) {
        return; // expected to throw; message wording is not asserted cross-language.
      }
      throw new AssertionError(label + ": getSnapshotData threw: " + e.getMessage());
    }

    if (step.has("expectError")) {
      throw new AssertionError(label + ": expected getSnapshotData to throw, but it succeeded");
    }
    if (snap == null) {
      throw new AssertionError(label + ": snapshot not found");
    }
    if (step.has("expectSnapshot")) {
      assertSnapshot(label, JsonUtils.toJsonNode(snap), step.get("expectSnapshot"));
    }
  }

  // ── abort ──────────────────────────────────────────────────────────────────────

  private void runAbort(
      String label,
      Agent<Map<String, Object>> agent,
      JsonNode rawStep,
      Map<String, JsonNode> captures) {
    JsonNode step = resolve(rawStep, captures);
    String snapshotId = textOrNull(step.get("snapshotId"));
    if (snapshotId == null) {
      throw new AssertionError(label + ": abort requires snapshotId");
    }

    // The spec's expectPreviousStatus is the status *before* the abort. agent.abort() returns the
    // status after the attempt: for a PENDING snapshot it returns ABORTED (so the previous was
    // PENDING); for terminal snapshots it returns the unchanged terminal status (== previous); for
    // a non-existent snapshot it returns null (== previous absent). Read the prior status directly
    // so the assertion matches the spec's semantics exactly.
    SnapshotStatus previous = null;
    SessionSnapshot<Map<String, Object>> before =
        agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
    if (before != null) {
      previous = before.getStatus() != null ? before.getStatus() : SnapshotStatus.COMPLETED;
    }
    agent.abort(snapshotId);

    if (rawStep.has("expectPreviousStatus")) {
      JsonNode want = rawStep.get("expectPreviousStatus");
      String wantStr = (want == null || want.isNull()) ? null : want.asText();
      String gotStr = previous != null ? previous.getValue() : null;
      if (!java.util.Objects.equals(wantStr, gotStr)) {
        throw new AssertionError(
            label + ": expectPreviousStatus: want '" + wantStr + "', got '" + gotStr + "'");
      }
    }
  }

  // ── waitUntilCompleted ────────────────────────────────────────────────────────────

  private void runWaitUntilCompleted(
      String label,
      Agent<Map<String, Object>> agent,
      JsonNode rawStep,
      Map<String, JsonNode> captures)
      throws InterruptedException {
    JsonNode step = resolve(rawStep, captures);
    String snapshotId = textOrNull(step.get("snapshotId"));
    if (snapshotId == null) {
      throw new AssertionError(label + ": waitUntilCompleted requires snapshotId");
    }
    long timeoutMs = step.has("timeoutMs") ? step.get("timeoutMs").asLong() : 5000L;

    long deadline = System.currentTimeMillis() + timeoutMs;
    SessionSnapshot<Map<String, Object>> snap = null;
    while (System.currentTimeMillis() < deadline) {
      snap = agent.getSnapshotData(GetSnapshotRequest.builder().snapshotId(snapshotId).build());
      if (snap != null && isTerminal(snap.getStatus())) {
        break;
      }
      Thread.sleep(50);
    }
    if (snap == null || !isTerminal(snap.getStatus())) {
      throw new AssertionError(
          label
              + ": snapshot did not reach a terminal status within "
              + timeoutMs
              + "ms (status="
              + (snap == null ? "<missing>" : snap.getStatus())
              + ")");
    }
    if (step.has("expectSnapshot")) {
      assertSnapshot(label, JsonUtils.toJsonNode(snap), step.get("expectSnapshot"));
    }
  }

  private static boolean isTerminal(SnapshotStatus s) {
    return s == SnapshotStatus.COMPLETED
        || s == SnapshotStatus.FAILED
        || s == SnapshotStatus.ABORTED;
  }

  // ── chunk assertions ──────────────────────────────────────────────────────────────

  /**
   * Semi-strict ordered chunk comparison: same length and order, with type-aware matching per chunk
   * (turnEnd asserts presence and, when specified, finishReason ignoring the dynamic snapshotId;
   * modelChunk / artifact / customPatch use CONTAINS).
   */
  private void assertChunks(String label, List<JsonNode> actual, JsonNode expected) {
    if (expected.size() != actual.size()) {
      throw new AssertionError(
          label
              + ": expected "
              + expected.size()
              + " chunks, got "
              + actual.size()
              + "\n  expected: "
              + expected
              + "\n  actual:   "
              + actual);
    }
    for (int i = 0; i < expected.size(); i++) {
      JsonNode exp = expected.get(i);
      JsonNode act = actual.get(i);
      String err = matchChunk(act, exp);
      if (err != null) {
        throw new AssertionError(
            label + ": chunk[" + i + "]: " + err + "\n  expected: " + exp + "\n  actual:   " + act);
      }
    }
  }

  private String matchChunk(JsonNode actual, JsonNode expected) {
    if (expected.has("turnEnd")) {
      JsonNode te = actual.get("turnEnd");
      if (te == null || te.isNull()) {
        return "expected a turnEnd chunk";
      }
      JsonNode exTe = expected.get("turnEnd");
      if (exTe != null && exTe.has("finishReason")) {
        // snapshotId is dynamic; only finishReason is asserted when specified.
        if (!nodeEquals(te.get("finishReason"), exTe.get("finishReason"))) {
          return "turnEnd.finishReason: want "
              + exTe.get("finishReason")
              + ", got "
              + te.get("finishReason");
        }
      }
      return null;
    }
    if (expected.has("modelChunk")) {
      JsonNode exMc = expected.get("modelChunk");
      JsonNode acMc = actual.get("modelChunk");
      return contains(acMc, exMc, "modelChunk");
    }
    for (String key : new String[] {"artifact", "customPatch"}) {
      if (expected.has(key)) {
        return contains(actual.get(key), expected.get(key), key);
      }
    }
    return contains(actual, expected, "chunk");
  }

  // ── output assertions ─────────────────────────────────────────────────────────────

  private void assertOutput(String label, JsonNode output, JsonNode expect) {
    if (output == null) {
      throw new AssertionError(label + ": no output");
    }
    if (expect.has("message")) {
      String err = contains(output.get("message"), expect.get("message"), "output.message");
      if (err != null) {
        throw new AssertionError(label + ": " + err);
      }
    }
    if (boolField(expect, "hasSnapshotId") && isBlank(output.get("snapshotId"))) {
      throw new AssertionError(label + ": expected output.snapshotId to be non-empty");
    }
    if (boolField(expect, "hasSessionId") && stateSessionId(output) == null) {
      throw new AssertionError(label + ": expected output.state.sessionId to be non-empty");
    }
    if (expect.has("stateContains")) {
      String err = contains(output.get("state"), expect.get("stateContains"), "output.state");
      if (err != null) {
        throw new AssertionError(label + ": " + err);
      }
    }
    if (expect.has("artifactsContain")) {
      assertArtifactsContain(label, output.get("artifacts"), expect.get("artifactsContain"));
    }
    if (expect.has("finishReason")) {
      if (!nodeEquals(output.get("finishReason"), expect.get("finishReason"))) {
        throw new AssertionError(
            label
                + ": output.finishReason: want "
                + expect.get("finishReason")
                + ", got "
                + output.get("finishReason"));
      }
    }
    if (expect.has("errorContains")) {
      assertErrorContains(label, "output.error", output.get("error"), expect.get("errorContains"));
    }
  }

  private void assertSnapshot(String label, JsonNode snap, JsonNode expect) {
    if (expect.has("parentId")) {
      JsonNode actualParent = snap.get("parentId");
      if (!nodeEquals(actualParent, expect.get("parentId"))) {
        throw new AssertionError(
            label
                + ": snapshot.parentId: want "
                + expect.get("parentId")
                + ", got "
                + actualParent);
      }
    }
    if (expect.has("status")) {
      JsonNode status = snap.get("status");
      // A null/absent status is COMPLETED on the wire.
      String got = (status == null || status.isNull()) ? "completed" : status.asText();
      if (!expect.get("status").asText().equals(got)) {
        throw new AssertionError(
            label + ": snapshot.status: want " + expect.get("status").asText() + ", got " + got);
      }
    }
    if (expect.has("finishReason")) {
      if (!nodeEquals(snap.get("finishReason"), expect.get("finishReason"))) {
        throw new AssertionError(
            label
                + ": snapshot.finishReason: want "
                + expect.get("finishReason")
                + ", got "
                + snap.get("finishReason"));
      }
    }
    if (boolField(expect, "hasSessionId") && stateSessionId(snap) == null) {
      throw new AssertionError(label + ": expected snapshot.state.sessionId to be non-empty");
    }
    if (expect.has("stateContains")) {
      String err = contains(snap.get("state"), expect.get("stateContains"), "snapshot.state");
      if (err != null) {
        throw new AssertionError(label + ": " + err);
      }
    }
    if (expect.has("errorContains")) {
      assertErrorContains(label, "snapshot.error", snap.get("error"), expect.get("errorContains"));
    }
  }

  private void assertArtifactsContain(String label, JsonNode actual, JsonNode expected) {
    if (actual == null || !actual.isArray()) {
      throw new AssertionError(label + ": expected output.artifacts to be a list, got " + actual);
    }
    for (JsonNode ea : expected) {
      String name = ea.path("name").asText(null);
      JsonNode found = null;
      for (JsonNode a : actual) {
        if (name != null && name.equals(a.path("name").asText(null))) {
          found = a;
          break;
        }
      }
      if (found == null) {
        throw new AssertionError(
            label + ": expected artifact '" + name + "' not found in " + actual);
      }
      String err = contains(found, ea, "artifact(" + name + ")");
      if (err != null) {
        throw new AssertionError(label + ": " + err);
      }
    }
  }

  /**
   * Matches a structured error: presence + status (exactly). The message is intentionally NOT
   * asserted — wording is implementation-specific; the cross-language contract is the status.
   */
  private void assertErrorContains(String label, String path, JsonNode actual, JsonNode expect) {
    if (actual == null || actual.isNull() || !actual.isObject()) {
      throw new AssertionError(label + ": expected " + path + " to be present, got " + actual);
    }
    if (expect.has("status")) {
      if (!nodeEquals(actual.get("status"), expect.get("status"))) {
        throw new AssertionError(
            label
                + ": "
                + path
                + ".status: want "
                + expect.get("status")
                + ", got "
                + actual.get("status"));
      }
    }
  }

  // ── contains / subsequence matchers ────────────────────────────────────────────────

  /**
   * Asserts that {@code actual} contains all fields specified in {@code expected}. Objects match
   * key-by-key (extra actual keys ignored); arrays match as an ordered subsequence; scalars must be
   * deep-equal. Returns {@code null} on match, or an error message describing the first mismatch.
   */
  private String contains(JsonNode actual, JsonNode expected, String path) {
    if (expected == null || expected.isNull()) {
      return null;
    }
    if (expected.isArray()) {
      if (actual == null || !actual.isArray()) {
        return path + ": expected array, got " + actual;
      }
      return subsequence(actual, expected, path);
    }
    if (expected.isObject()) {
      if (actual == null || !actual.isObject()) {
        return path + ": expected object, got " + actual;
      }
      Iterator<String> fields = expected.fieldNames();
      while (fields.hasNext()) {
        String k = fields.next();
        String err = contains(actual.get(k), expected.get(k), path + "." + k);
        if (err != null) {
          return err;
        }
      }
      return null;
    }
    if (!nodeEquals(actual, expected)) {
      return path + ": want " + expected + ", got " + actual;
    }
    return null;
  }

  /** Each expected item must appear in {@code actual} in order (not necessarily contiguous). */
  private String subsequence(JsonNode actual, JsonNode expected, String path) {
    int idx = 0;
    for (int i = 0; i < expected.size(); i++) {
      JsonNode want = expected.get(i);
      boolean found = false;
      while (idx < actual.size()) {
        if (contains(actual.get(idx), want, path + "[" + idx + "]") == null) {
          found = true;
          idx++;
          break;
        }
        idx++;
      }
      if (!found) {
        return path + ": expected item " + i + " not found in order: " + want;
      }
    }
    return null;
  }

  /** Numeric-tolerant deep equality (1 == 1.0; matches YAML ints vs JSON longs/doubles). */
  private static boolean nodeEquals(JsonNode a, JsonNode b) {
    if (a == null || a.isNull()) {
      return b == null || b.isNull();
    }
    if (b == null || b.isNull()) {
      return false;
    }
    if (a.isNumber() && b.isNumber()) {
      return a.asDouble() == b.asDouble();
    }
    return a.equals(b);
  }

  // ── template resolution ────────────────────────────────────────────────────────────

  private static final Pattern FULL = Pattern.compile("^\\{\\{(\\w+)\\}\\}$");
  private static final Pattern INLINE = Pattern.compile("\\{\\{(\\w+)\\}\\}");

  /**
   * Recursively replaces {@code {{name}}} references with previously captured values. A value that
   * is exactly {@code {{name}}} is replaced by the captured node (which may be a non-string, e.g. a
   * captured state object); inline occurrences are string-substituted.
   */
  private JsonNode resolve(JsonNode v, Map<String, JsonNode> captures) {
    if (v == null) {
      return null;
    }
    if (v.isTextual()) {
      String s = v.asText();
      Matcher full = FULL.matcher(s);
      if (full.matches()) {
        JsonNode val = captures.get(full.group(1));
        if (val == null) {
          throw new AssertionError("template reference {{" + full.group(1) + "}} not found");
        }
        return val;
      }
      Matcher inline = INLINE.matcher(s);
      StringBuilder out = new StringBuilder();
      while (inline.find()) {
        JsonNode val = captures.get(inline.group(1));
        if (val == null) {
          throw new AssertionError("template reference {{" + inline.group(1) + "}} not found");
        }
        String rep = val.isTextual() ? val.asText() : val.toString();
        inline.appendReplacement(out, Matcher.quoteReplacement(rep));
      }
      inline.appendTail(out);
      return JSON.getNodeFactory().textNode(out.toString());
    }
    if (v.isObject()) {
      ObjectNode out = JSON.createObjectNode();
      Iterator<Map.Entry<String, JsonNode>> it = v.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        out.set(e.getKey(), resolve(e.getValue(), captures));
      }
      return out;
    }
    if (v.isArray()) {
      var out = JSON.createArrayNode();
      for (JsonNode e : v) {
        out.add(resolve(e, captures));
      }
      return out;
    }
    return v;
  }

  // ── small helpers ──────────────────────────────────────────────────────────────────

  private interface Supplier {
    String get();
  }

  private void capture(
      JsonNode rawStep, String key, Supplier value, Map<String, JsonNode> captures, String label) {
    if (!rawStep.has(key)) {
      return;
    }
    String v = value.get();
    if (v == null) {
      throw new AssertionError(label + ": " + key + " requested but the value was absent");
    }
    captures.put(rawStep.get(key).asText(), JSON.getNodeFactory().textNode(v));
  }

  private static String stateSessionId(JsonNode container) {
    if (container == null) {
      return null;
    }
    JsonNode state = container.get("state");
    if (state == null || state.isNull()) {
      return null;
    }
    return isBlank(state.get("sessionId")) ? null : state.get("sessionId").asText();
  }

  private static String textOrNull(JsonNode n) {
    return (n == null || n.isNull()) ? null : n.asText();
  }

  private static boolean isBlank(JsonNode n) {
    return n == null || n.isNull() || n.asText().isEmpty();
  }

  private static boolean boolField(JsonNode obj, String key) {
    JsonNode n = obj.get(key);
    return n != null && n.asBoolean();
  }

  private static List<JsonNode> toNodeList(JsonNode arr) {
    List<JsonNode> out = new ArrayList<>();
    if (arr != null && arr.isArray()) {
      arr.forEach(out::add);
    }
    return out;
  }

  private static String oneLine(String s) {
    if (s == null) {
      return "(no message)";
    }
    String t = s.replaceAll("\\s+", " ").trim();
    return t.length() > 200 ? t.substring(0, 200) + "…" : t;
  }

  // ── summary ──────────────────────────────────────────────────────────────────────

  @AfterAll
  static void printSummary() {
    long passed = RESULTS.stream().filter(r -> r.outcome() == Outcome.PASS).count();
    long skipped = RESULTS.stream().filter(r -> r.outcome() == Outcome.SKIP).count();
    long failed = RESULTS.stream().filter(r -> r.outcome() == Outcome.FAIL).count();

    StringBuilder sb = new StringBuilder();
    sb.append("\n========================================================================\n");
    sb.append("Agent conformance summary: ")
        .append(passed)
        .append(" passed / ")
        .append(skipped)
        .append(" skipped / ")
        .append(failed)
        .append(" failed  (of ")
        .append(RESULTS.size())
        .append(")\n");
    sb.append("------------------------------------------------------------------------\n");
    for (CaseResult r : RESULTS) {
      sb.append(String.format("  %-7s %s", r.outcome(), r.name()));
      if (r.reason() != null) {
        sb.append("  — ").append(r.reason());
      }
      sb.append('\n');
    }
    sb.append("========================================================================\n");
    System.out.println(sb);
  }
}
