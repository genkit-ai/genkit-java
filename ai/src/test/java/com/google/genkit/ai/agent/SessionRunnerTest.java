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

package com.google.genkit.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.ai.agent.internal.AbortAwareMutator;
import com.google.genkit.core.GenkitException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TDD tests for SessionRunner (Task 4.1). */
class SessionRunnerTest {

  private static final SessionStoreOptions OPTS = SessionStoreOptions.empty();

  private InMemorySessionStore<Map<String, Object>> store;
  private Session<Map<String, Object>> session;

  @BeforeEach
  void setUp() {
    store = new InMemorySessionStore<>();
    SessionState<Map<String, Object>> initialState =
        SessionState.<Map<String, Object>>builder()
            .sessionId("test-session-1")
            .custom(new HashMap<>())
            .build();
    session = new Session<>(initialState);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private static Message userMessage(String text) {
    return new Message(Role.USER, java.util.List.of(Part.text(text)));
  }

  private static Message modelMessage(String text) {
    return new Message(Role.MODEL, java.util.List.of(Part.text(text)));
  }

  // ── Two successful turns with parentId chaining ──────────────────────────────

  @Test
  void testTwoSuccessfulTurns_parentIdChaining_andTurnIndex() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);
    assertEquals(0, runner.turnIndex());
    assertNull(runner.lastSnapshot());
    assertNull(runner.lastSnapshotId());

    // Turn 1: user sends "hello", model replies "hi"
    AgentInput input1 = AgentInput.builder().message(userMessage("hello")).build();
    runner.runTurn(
        input1,
        (inp, ctx) -> {
          runner.addMessages(modelMessage("hi"));
          return AgentFinishReason.STOP;
        });

    assertEquals(1, runner.turnIndex());
    assertNotNull(runner.lastSnapshot());
    String snap1Id = runner.lastSnapshotId();
    assertNotNull(snap1Id);
    assertFalse(snap1Id.isEmpty());
    assertEquals(SnapshotStatus.COMPLETED, runner.lastSnapshot().getStatus());
    assertNull(runner.lastSnapshot().getParentId()); // first turn has no parent

    // Turn 2: user sends "world", model replies "there"
    AgentInput input2 = AgentInput.builder().message(userMessage("world")).build();
    runner.runTurn(
        input2,
        (inp, ctx) -> {
          runner.addMessages(modelMessage("there"));
          return AgentFinishReason.STOP;
        });

    assertEquals(2, runner.turnIndex());
    String snap2Id = runner.lastSnapshotId();
    assertNotNull(snap2Id);
    assertFalse(snap2Id.isEmpty());
    assertNotEquals(snap1Id, snap2Id);

    // Second snapshot's parentId must be the first snapshot's id
    assertEquals(snap1Id, runner.lastSnapshot().getParentId());
    assertEquals(SnapshotStatus.COMPLETED, runner.lastSnapshot().getStatus());

    // getSnapshot by sessionId returns the leaf (second) snapshot
    GetSnapshotOptions getOpts = GetSnapshotOptions.builder().sessionId("test-session-1").build();
    SessionSnapshot<?> leaf = store.getSnapshot(getOpts);
    assertNotNull(leaf);
    assertEquals(snap2Id, leaf.getSnapshotId());
    assertEquals(snap1Id, leaf.getParentId());

    // Messages accumulate: user+model x2 = 4 messages
    assertEquals(4, runner.getMessages().size());

    // lastTurnFinishReason should be STOP (not FAILED)
    assertEquals(AgentFinishReason.STOP, runner.lastTurnFinishReason());
    assertNull(runner.lastTurnError());
  }

  // ── Throwing turnBody → FAILED snapshot, no rethrow ─────────────────────────

  @Test
  void testThrowingTurnBody_gracefulFailedSnapshot_noRethrow() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);

    AgentInput input = AgentInput.builder().message(userMessage("hello")).build();

    // Should NOT throw even though turnBody throws
    assertDoesNotThrow(
        () ->
            runner.runTurn(
                input,
                (inp, ctx) -> {
                  throw new RuntimeException("simulated turn failure");
                }));

    // turnIndex advances even on failure
    assertEquals(1, runner.turnIndex());

    // FAILED snapshot was persisted
    assertNotNull(runner.lastSnapshot());
    assertEquals(SnapshotStatus.FAILED, runner.lastSnapshot().getStatus());
    assertEquals(AgentFinishReason.FAILED, runner.lastTurnFinishReason());

    // Error was recorded on the runner
    assertNotNull(runner.lastTurnError());
    assertEquals("simulated turn failure", runner.lastTurnError().getMessage());

    // Snapshot error was recorded
    assertNotNull(runner.lastSnapshot().getError());

    // Store also has the failed snapshot
    String failedId = runner.lastSnapshotId();
    assertNotNull(failedId);
    GetSnapshotOptions getOpts = GetSnapshotOptions.builder().snapshotId(failedId).build();
    SessionSnapshot<?> stored = store.getSnapshot(getOpts);
    assertNotNull(stored);
    assertEquals(SnapshotStatus.FAILED, stored.getStatus());
  }

  // ── Client-managed (store=null) ──────────────────────────────────────────────

  @Test
  void testClientManaged_nullStore_noException_stateReflected() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, null, OPTS);

    AgentInput input = AgentInput.builder().message(userMessage("hi")).build();

    assertDoesNotThrow(
        () ->
            runner.runTurn(
                input,
                (inp, ctx) -> {
                  runner.addMessages(modelMessage("hello"));
                  return AgentFinishReason.STOP;
                }));

    assertEquals(1, runner.turnIndex());
    // Client-managed: lastSnapshotId is "" (empty string)
    assertEquals("", runner.lastSnapshotId());
    // But lastSnapshot is still tracked in memory
    assertNotNull(runner.lastSnapshot());
    assertEquals(SnapshotStatus.COMPLETED, runner.lastSnapshot().getStatus());

    // State reflects the added messages
    assertEquals(2, runner.getMessages().size());
  }

  // ── Invalid input validation → INVALID_ARGUMENT thrown ───────────────────────

  @Test
  void testInvalidInput_toolRequestPart_throwsGenkitException() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);

    Part toolRequestPart = new Part();
    toolRequestPart.setToolRequest(new com.google.genkit.ai.ToolRequest());
    Message badMsg = new Message(Role.USER, java.util.List.of(toolRequestPart));
    AgentInput input = AgentInput.builder().message(badMsg).build();

    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () -> runner.runTurn(input, (inp, ctx) -> AgentFinishReason.STOP));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
  }

  @Test
  void testInvalidInput_toolResponsePart_throwsGenkitException() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);

    Part toolResponsePart = new Part();
    toolResponsePart.setToolResponse(new com.google.genkit.ai.ToolResponse());
    Message badMsg = new Message(Role.USER, java.util.List.of(toolResponsePart));
    AgentInput input = AgentInput.builder().message(badMsg).build();

    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () -> runner.runTurn(input, (inp, ctx) -> AgentFinishReason.STOP));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
  }

  @Test
  void testInvalidInput_nonUserRole_throwsGenkitException() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);

    Message badMsg = modelMessage("should not be allowed");
    AgentInput input = AgentInput.builder().message(badMsg).build();

    GenkitException ex =
        assertThrows(
            GenkitException.class,
            () -> runner.runTurn(input, (inp, ctx) -> AgentFinishReason.STOP));
    assertEquals("INVALID_ARGUMENT", ex.getErrorCode());
  }

  // ── AbortAwareMutator unit test ──────────────────────────────────────────────

  @Test
  void testAbortAwareMutator_existingAborted_returnsNull() {
    SessionSnapshot<Map<String, Object>> existing =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-1")
            .sessionId("test-session-1")
            .status(SnapshotStatus.ABORTED)
            .build();

    SessionSnapshot<Map<String, Object>> toWrite =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-1")
            .sessionId("test-session-1")
            .status(SnapshotStatus.COMPLETED)
            .build();

    SnapshotMutator<Map<String, Object>> inner = (e) -> toWrite;
    SnapshotMutator<Map<String, Object>> wrapped = AbortAwareMutator.wrap(inner);

    // When existing is ABORTED, wrapped mutator must return null (no-op)
    assertNull(wrapped.apply(existing));
  }

  @Test
  void testAbortAwareMutator_existingNotAborted_delegates() {
    SessionSnapshot<Map<String, Object>> existing =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-1")
            .sessionId("test-session-1")
            .status(SnapshotStatus.COMPLETED)
            .build();

    SessionSnapshot<Map<String, Object>> toWrite =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-1")
            .sessionId("test-session-1")
            .status(SnapshotStatus.COMPLETED)
            .build();

    SnapshotMutator<Map<String, Object>> inner = (e) -> toWrite;
    SnapshotMutator<Map<String, Object>> wrapped = AbortAwareMutator.wrap(inner);

    // When existing is not ABORTED, wrapped mutator delegates to inner
    SessionSnapshot<Map<String, Object>> result = wrapped.apply(existing);
    assertNotNull(result);
    assertSame(toWrite, result);
  }

  @Test
  void testAbortAwareMutator_existingNull_delegates() {
    SessionSnapshot<Map<String, Object>> toWrite =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-1")
            .sessionId("test-session-1")
            .status(SnapshotStatus.COMPLETED)
            .build();

    SnapshotMutator<Map<String, Object>> inner = (e) -> toWrite;
    SnapshotMutator<Map<String, Object>> wrapped = AbortAwareMutator.wrap(inner);

    // When existing is null (no prior snapshot), wrapped mutator delegates to inner
    SessionSnapshot<Map<String, Object>> result = wrapped.apply(null);
    assertNotNull(result);
    assertSame(toWrite, result);
  }

  // ── InterruptedException → ABORTED (not FAILED) ──────────────────────────────

  @Test
  void testInterruptedTurnBody_abortedStatus() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);

    AgentInput input = AgentInput.builder().message(userMessage("hello")).build();

    // Should NOT throw even though turnBody throws InterruptedException
    assertDoesNotThrow(
        () ->
            runner.runTurn(
                input,
                (inp, ctx) -> {
                  throw new InterruptedException("aborted by user");
                }));

    assertEquals(1, runner.turnIndex());
    // For InterruptedException, status should be ABORTED, not FAILED
    assertNotNull(runner.lastSnapshot());
    assertEquals(SnapshotStatus.ABORTED, runner.lastSnapshot().getStatus());
    assertEquals(AgentFinishReason.ABORTED, runner.lastTurnFinishReason());
  }

  // ── Message deep-copy: external mutation should not corrupt history ─────────

  @Test
  void testMessageDeepCopy_externalMutationDoesNotAffectHistory() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);

    // Create a user message and pass it via input
    Message originalMsg = userMessage("original text");
    AgentInput input = AgentInput.builder().message(originalMsg).build();

    runner.runTurn(
        input,
        (inp, ctx) -> {
          runner.addMessages(modelMessage("response"));
          return AgentFinishReason.STOP;
        });

    // Now mutate the original message object (caller's reference)
    originalMsg.setContent(java.util.List.of(Part.text("mutated text")));

    // Verify that the session's stored message is unchanged
    java.util.List<Message> messages = runner.getMessages();
    assertEquals(2, messages.size());

    Message storedUserMsg = messages.get(0);
    assertEquals("original text", storedUserMsg.getText());
    assertNotEquals("mutated text", storedUserMsg.getText());
  }

  // ── behavior 16: sess.addMessages splices messages directly into history ────

  @Test
  void testAddMessagesManuallySplicedIntoHistory() {
    SessionRunner<Map<String, Object>> runner = new SessionRunner<>(session, store, OPTS);

    AgentInput input = AgentInput.builder().message(userMessage("hello")).build();
    runner.runTurn(
        input,
        (inp, ctx) -> {
          // Directly splice extra messages into history from within the turn body, simulating
          // an AgentFn that records e.g. tool-call bookkeeping messages via sess.addMessages(...).
          runner.addMessages(modelMessage("spliced-1"), modelMessage("spliced-2"));
          return AgentFinishReason.STOP;
        });

    java.util.List<Message> messages = runner.getMessages();
    // user "hello" + 2 spliced messages = 3
    assertEquals(3, messages.size());
    assertEquals("spliced-1", messages.get(1).getText());
    assertEquals("spliced-2", messages.get(2).getText());

    // The spliced messages must also be present in the persisted snapshot after the turn.
    GetSnapshotOptions getOpts =
        GetSnapshotOptions.builder().snapshotId(runner.lastSnapshotId()).build();
    SessionSnapshot<Map<String, Object>> stored = store.getSnapshot(getOpts);
    assertNotNull(stored);
    java.util.List<Message> storedMsgs = stored.getState().getMessages();
    assertEquals(3, storedMsgs.size());
    assertEquals("spliced-1", storedMsgs.get(1).getText());
    assertEquals("spliced-2", storedMsgs.get(2).getText());
  }
}
