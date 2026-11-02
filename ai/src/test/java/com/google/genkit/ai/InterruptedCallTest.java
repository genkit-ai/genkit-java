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

package com.google.genkit.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.ai.middleware.ToolParams;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterruptedCallTest {

  private static Part hold(String raisedBy, Object data) {
    ToolRequest req = new ToolRequest("transfer", Map.of("amount", 100));
    req.setRef("r1");
    Part part = Part.toolRequest(req);
    Map<String, Object> md = new HashMap<>();
    md.put("interrupt", data);
    if (raisedBy != null) {
      md.put("interruptedBy", raisedBy);
    }
    md.put("releasedBy", List.of("other"));
    md.put("pendingOutput", "x");
    md.put("label", "kept");
    part.setMetadata(md);
    return part;
  }

  /** The answer type of a test middleware. */
  public static class Answer {
    public boolean ok;
    public String note;
  }

  @Test
  void claimsOnlyTheMiddlewaresOwnHolds() {
    assertTrue(InterruptedCall.ofMiddleware("mw", hold("mw", true)).isPresent());
    assertTrue(InterruptedCall.ofMiddleware("mw", hold("mw#2", true)).isPresent());
    assertFalse(InterruptedCall.ofMiddleware("mw", hold("other", true)).isPresent());
    assertFalse(InterruptedCall.ofMiddleware("mw", hold(null, true)).isPresent());
    assertFalse(InterruptedCall.ofMiddleware("mw", hold("mw#x", true)).isPresent());
    assertFalse(InterruptedCall.ofMiddleware("mw", Part.text("hi")).isPresent());
    assertFalse(InterruptedCall.ofMiddleware("mw", null).isPresent());
  }

  @Test
  void isStageOf() {
    assertTrue(InterruptedCall.isStageOf("a/b", "a/b"));
    assertTrue(InterruptedCall.isStageOf("a/b#12", "a/b"));
    assertFalse(InterruptedCall.isStageOf("a/b#", "a/b"));
    assertFalse(InterruptedCall.isStageOf("a/bc", "a/b"));
    assertFalse(InterruptedCall.isStageOf("", "a/b"));
    assertFalse(InterruptedCall.isStageOf("a/b", ""));
  }

  @Test
  void restartCarriesTheAnswerAndDropsInterruptState() {
    Part held = hold("mw", Map.of("message", "approve?"));
    InterruptedCall<Answer> call = InterruptedCall.<Answer>ofMiddleware("mw", held).orElseThrow();
    assertSame(held, call.getPart());
    assertEquals(Map.of("amount", 100), call.getInput());
    assertEquals(Map.of("message", "approve?"), call.getInterruptData());

    Answer a = new Answer();
    a.ok = true;
    Part restart = call.restart(a);
    assertEquals("transfer", restart.getToolRequest().getName());
    assertEquals("r1", restart.getToolRequest().getRef());
    assertEquals(Map.of("amount", 100), restart.getToolRequest().getInput());
    Map<String, Object> md = restart.getMetadata();
    assertEquals(true, ((Map<?, ?>) md.get("resumed")).get("ok"));
    assertEquals("kept", md.get("label"));
    for (String gone : List.of("interrupt", "interruptedBy", "releasedBy", "pendingOutput")) {
      assertFalse(md.containsKey(gone), gone);
    }
    assertEquals(md, restart.getToolRequest().getMetadata());
  }

  @Test
  void bareRestartAndReplacedInput() {
    InterruptedCall<Answer> call =
        InterruptedCall.<Answer>ofMiddleware("mw", hold("mw", true)).orElseThrow();
    assertEquals(true, call.restart(null).getMetadata().get("resumed"));

    Part replaced = call.restartWithInput(Map.of("amount", 5), null);
    assertEquals(Map.of("amount", 5), replaced.getToolRequest().getInput());
    assertEquals(Map.of("amount", 100), replaced.getMetadata().get("replacedInput"));
  }

  @Test
  void restartRejectsNonObjectAnswers() {
    InterruptedCall<Object> call =
        InterruptedCall.ofMiddleware("mw", hold("mw", true)).orElseThrow();
    assertThrows(RuntimeException.class, () -> call.restart("yes"));
  }

  @Test
  void respondAnswersWithOutput() {
    InterruptedCall<Answer> call =
        InterruptedCall.<Answer>ofMiddleware("mw", hold("mw", true)).orElseThrow();
    Part resp = call.respond("done");
    assertEquals("transfer", resp.getToolResponse().getName());
    assertEquals("r1", resp.getToolResponse().getRef());
    assertEquals("done", resp.getToolResponse().getOutput());
    assertEquals(true, resp.getMetadata().get("interruptResponse"));
  }

  @Test
  void resumeOptionsPartKeepsRestartMetadata() {
    InterruptedCall<Answer> call =
        InterruptedCall.<Answer>ofMiddleware("mw", hold("mw", true)).orElseThrow();
    ResumeOptions opts =
        ResumeOptions.builder().part(call.restart(null)).part(call.respond("x")).build();
    assertEquals(1, opts.getRestart().size());
    assertEquals(true, opts.getRestart().get(0).getMetadata().get("resumed"));
    assertEquals(1, opts.getRespond().size());
    assertThrows(
        IllegalArgumentException.class, () -> ResumeOptions.builder().part(Part.text("x")));
  }

  @Test
  void toolInterruptRecordsStages() {
    ToolInterruptException e = new ToolInterruptException();
    assertNull(e.getRaisedBy());
    e.recordRaisedBy("inner");
    e.recordRaisedBy("outer");
    assertEquals("inner", e.getRaisedBy());
    e.recordReleasedBy("b");
    e.recordReleasedBy("a");
    assertEquals(List.of("a", "b"), e.getReleasedBy());
  }

  @Test
  void toolFailFindsItselfInTheCauseChain() {
    ToolFailException fail = new ToolFailException("bad input");
    assertSame(fail, ToolFailException.find(new RuntimeException("wrapped", fail)));
    assertNull(ToolFailException.find(new RuntimeException("plain")));
    assertEquals("boom", new ToolFailException(new IllegalStateException("boom")).getMessage());
  }

  @Test
  void toolParamsReleased() {
    ToolParams p = new ToolParams(Part.toolRequest(new ToolRequest("t", null)), null);
    assertFalse(p.isReleased());
    assertTrue(p.withReleased(true).isReleased());
  }
}
