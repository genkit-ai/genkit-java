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

package com.google.genkit.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.ai.agent.AgentSessionContext;
import com.google.genkit.ai.agent.Session;
import com.google.genkit.ai.agent.SessionState;
import com.google.genkit.ai.telemetry.ModelTelemetryHelper;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests the core pieces the experimental budget and compression middleware build on. */
class ModelObserverTest {

  private static ModelResponse reply(int inputTokens) {
    ModelResponse r = new ModelResponse(List.of(new Candidate(Message.model("ok"))));
    Usage u = new Usage();
    u.setInputTokens(inputTokens);
    r.setUsage(u);
    return r;
  }

  @Test
  void observersSeeEveryModelCallInScope() {
    List<ModelObserver.ModelCall> outer = new ArrayList<>();
    List<ModelObserver.ModelCall> inner = new ArrayList<>();
    ModelRequest req = new ModelRequest(List.of(Message.user("hi")));
    ModelObserver.observe(
        outer::add,
        () -> {
          ModelTelemetryHelper.runWithTelemetry("m1", null, null, req, r -> reply(1));
          return ModelObserver.observe(
              inner::add,
              () -> ModelTelemetryHelper.runWithTelemetry("m2", null, null, req, r -> reply(2)));
        });
    assertEquals(2, outer.size());
    assertEquals(1, inner.size());
    assertEquals("m2", inner.get(0).model());
    assertSame(req, inner.get(0).request());
    assertEquals(2, inner.get(0).response().getUsage().getInputTokens());

    // Out of scope, nothing is reported.
    ModelTelemetryHelper.runWithTelemetry("m3", null, null, req, r -> reply(3));
    assertEquals(2, outer.size());
  }

  @Test
  void failedCallsAreReportedWithTheirError() {
    List<ModelObserver.ModelCall> calls = new ArrayList<>();
    ModelRequest req = new ModelRequest(List.of(Message.user("hi")));
    assertThrows(
        GenkitException.class,
        () ->
            ModelObserver.observe(
                calls::add,
                () ->
                    ModelTelemetryHelper.runWithTelemetry(
                        "bad",
                        null,
                        null,
                        req,
                        r -> {
                          throw new GenkitException("boom");
                        })));
    assertEquals(1, calls.size());
    assertNull(calls.get(0).response());
    assertEquals("boom", calls.get(0).error().getMessage());
  }

  @Test
  void observerFailuresDoNotBreakTheCall() {
    ModelRequest req = new ModelRequest(List.of(Message.user("hi")));
    ModelResponse r =
        ModelObserver.observe(
            call -> {
              throw new IllegalStateException("observer bug");
            },
            () -> ModelTelemetryHelper.runWithTelemetry("m", null, null, req, x -> reply(1)));
    assertEquals("ok", r.getText());
  }

  @Test
  void sumAddsFieldsAndCustomKeys() {
    Usage a = new Usage(1, 2, 3);
    a.setCustom(new LinkedHashMap<>(Map.of("cost", 0.5)));
    Usage b = new Usage(10, null, null);
    b.setThoughtsTokens(4);
    b.setCustom(new LinkedHashMap<>(Map.of("cost", 0.25, "credits", 1.0)));
    Usage sum = Usage.sum(a, null, b);
    assertEquals(11, sum.getInputTokens());
    assertEquals(2, sum.getOutputTokens());
    assertEquals(4, sum.getThoughtsTokens());
    assertEquals(0.75, sum.getCustom().get("cost"));
    assertEquals(1.0, sum.getCustom().get("credits"));
    // The arguments are left alone.
    assertEquals(0.5, a.getCustom().get("cost"));
    assertNull(Usage.sum((Usage) null));
  }

  @Test
  void newPartKindsRoundTripThroughJson() {
    ToolResponse tr = new ToolResponse("r1", "shot", "ok");
    tr.setContent(List.of(Part.media("image/png", "data:image/png;base64,AA")));
    List<Part> parts =
        List.of(
            Part.reasoning("thinking"),
            Part.resource("file:///a.md"),
            Part.custom(Map.of("k", "v")),
            Part.toolResponse(tr));
    Message m = new Message(Role.MODEL, parts);
    Message back = JsonUtils.fromJson(JsonUtils.toJson(m), Message.class);
    assertTrue(back.getContent().get(0).isReasoning());
    assertEquals("thinking", back.getContent().get(0).getReasoning());
    assertEquals("", back.getText());
    assertEquals("file:///a.md", back.getContent().get(1).getResource().get("uri"));
    assertEquals("v", back.getContent().get(2).getCustom().get("k"));
    assertEquals(
        "image/png",
        back.getContent().get(3).getToolResponse().getContent().get(0).getMedia().getContentType());
    Part copy = parts.get(0).copy();
    assertEquals("thinking", copy.getReasoning());
  }

  @Test
  void finishReasonAbortedHasItsWireName() {
    assertEquals("\"aborted\"", JsonUtils.toJson(FinishReason.ABORTED));
  }

  @Test
  void sessionUsageIsTrackedOnlyWhenBound() {
    assertTrue(AgentSessionContext.sessionUsage().isEmpty());
    Session<Map<String, Object>> session =
        new Session<>(SessionState.<Map<String, Object>>builder().build());
    session.addUsage(new Usage(5, null, null));
    Usage seen =
        AgentSessionContext.callWithSessionUsage(
            session::getUsage, () -> AgentSessionContext.sessionUsage().orElseThrow());
    assertEquals(5, seen.getInputTokens());
    // A client-managed (untracked) nested scope hides the enclosing session's usage.
    boolean hidden =
        AgentSessionContext.callWithSessionUsage(
            session::getUsage,
            () ->
                AgentSessionContext.callWithSessionUsage(
                    null, () -> AgentSessionContext.sessionUsage().isEmpty()));
    assertTrue(hidden);
    // The usage is part of the session state and survives a state copy.
    assertEquals(5, session.getState().getUsage().getInputTokens());
    assertEquals(5, new Session<>(session.getState()).getUsage().getInputTokens());
  }

  @Test
  void generateActionOptionsCarryTheResume() {
    GenerateActionOptions opts = new GenerateActionOptions();
    opts.setResume(ResumeOptions.builder().respond(new ToolResponse("r", "t", "x")).build());
    assertSame(opts.getResume(), opts.withMessages(List.of()).getResume());
  }
}
