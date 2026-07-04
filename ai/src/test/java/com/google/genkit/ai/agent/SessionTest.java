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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** TDD tests for Session, ArtifactStore, and AgentSessionContext. */
class SessionTest {

  // ---- updateCustom: version increment + listener ----

  @Test
  void testGetCustomReturnsDeepcopy() {
    Map<String, Object> custom = new HashMap<>();
    custom.put("k", 1);

    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().custom(custom).build();

    Session<Map<String, Object>> session = new Session<>(state);

    // Get the custom state and mutate it
    Map<String, Object> returned = session.getCustom();
    returned.put("k", 999);

    // Fetch fresh and verify unchanged
    Map<String, Object> fresh = session.getCustom();
    assertEquals(1, fresh.get("k"), "mutating returned custom must not alter internal state");

    // Also verify via getState().getCustom()
    SessionState<Map<String, Object>> snapshot = session.getState();
    assertEquals(
        1,
        snapshot.getCustom().get("k"),
        "getState().getCustom() must also show unchanged internal state");
  }

  @Test
  void testUpdateCustomIncrementsVersion() {
    Map<String, Object> custom = new HashMap<>();
    custom.put("count", 0);

    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().custom(custom).build();

    Session<Map<String, Object>> session = new Session<>(state);
    long versionBefore = session.getVersion();

    session.updateCustom(
        c -> {
          Map<String, Object> next = new HashMap<>(c);
          next.put("count", 1);
          return next;
        });

    assertEquals(
        versionBefore + 1, session.getVersion(), "version must increment after updateCustom");
    assertEquals(1, session.getCustom().get("count"), "custom state must be updated");
  }

  @Test
  void testUpdateCustomFiresOnCustomChangedListener() {
    Map<String, Object> custom = new HashMap<>();
    custom.put("value", "initial");

    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().custom(custom).build();

    Session<Map<String, Object>> session = new Session<>(state);

    AtomicInteger callCount = new AtomicInteger(0);
    session.setOnCustomChanged(() -> callCount.incrementAndGet());

    session.updateCustom(
        c -> {
          Map<String, Object> next = new HashMap<>(c);
          next.put("value", "changed");
          return next;
        });

    assertEquals(1, callCount.get(), "onCustomChanged must be called once");
  }

  // ---- addArtifacts: dedup by name ----

  @Test
  void testAddArtifactsDeduplicatesByName() {
    SessionState<Map<String, Object>> state = SessionState.<Map<String, Object>>builder().build();
    Session<Map<String, Object>> session = new Session<>(state);

    Artifact v1 = Artifact.builder().name("doc1").build();
    session.addArtifacts(v1);
    assertEquals(1, session.getArtifacts().size());

    Artifact v2 = Artifact.builder().name("doc1").build();
    session.addArtifacts(v2);

    List<Artifact> artifacts = session.getArtifacts();
    assertEquals(1, artifacts.size(), "dedup: same name must not add a second entry");
    assertSame(
        v2.getName(),
        artifacts.get(0).getName(),
        "replaced artifact must have the same name reference");
  }

  @Test
  void testAddArtifactsDifferentNameAppends() {
    SessionState<Map<String, Object>> state = SessionState.<Map<String, Object>>builder().build();
    Session<Map<String, Object>> session = new Session<>(state);

    session.addArtifacts(Artifact.builder().name("doc1").build());
    session.addArtifacts(Artifact.builder().name("doc2").build());

    assertEquals(2, session.getArtifacts().size(), "different names must both be present");
  }

  @Test
  void testAddArtifactsUpdateFiresListener() {
    SessionState<Map<String, Object>> state = SessionState.<Map<String, Object>>builder().build();
    Session<Map<String, Object>> session = new Session<>(state);

    AtomicInteger addCount = new AtomicInteger(0);
    session.setOnArtifactChanged(a -> addCount.incrementAndGet());

    Artifact v1 = Artifact.builder().name("doc1").build();
    session.addArtifacts(v1);
    assertEquals(1, addCount.get(), "listener must fire on add");

    Artifact v2 = Artifact.builder().name("doc1").build();
    session.addArtifacts(v2);
    assertEquals(2, addCount.get(), "listener must fire on update/replace");
  }

  @Test
  void testAddArtifactsNullNameAlwaysAppends() {
    SessionState<Map<String, Object>> state = SessionState.<Map<String, Object>>builder().build();
    Session<Map<String, Object>> session = new Session<>(state);

    session.addArtifacts(Artifact.builder().build()); // name is null
    session.addArtifacts(Artifact.builder().build()); // name is null

    assertEquals(2, session.getArtifacts().size(), "null-named artifacts must always be appended");
  }

  // ---- getState deep copy ----

  @Test
  void testGetStateReturnsDeepCopy() {
    List<Message> messages = new ArrayList<>();
    messages.add(Message.user("hello"));

    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder()
            .sessionId("sess-deep")
            .messages(messages)
            .build();

    Session<Map<String, Object>> session = new Session<>(state);

    SessionState<Map<String, Object>> snapshot = session.getState();

    // Mutate the returned snapshot's messages list
    if (snapshot.getMessages() != null) {
      snapshot.setMessages(new ArrayList<>());
    }

    // Internal state must be unchanged
    SessionState<Map<String, Object>> snapshot2 = session.getState();
    assertNotNull(
        snapshot2.getMessages(), "internal messages must not be cleared by external mutation");
    assertFalse(
        snapshot2.getMessages().isEmpty(),
        "internal messages must retain original entries after external mutation");
  }

  // ---- constructor sessionId minting ----

  @Test
  void testConstructorMintsSessionIdWhenAbsent() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().build(); // no sessionId

    Session<Map<String, Object>> session = new Session<>(state);

    assertNotNull(session.sessionId(), "sessionId must not be null when not provided");
    assertFalse(session.sessionId().isEmpty(), "sessionId must not be empty when not provided");
  }

  @Test
  void testConstructorPreservesProvidedSessionId() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("my-session").build();

    Session<Map<String, Object>> session = new Session<>(state);

    assertEquals("my-session", session.sessionId(), "provided sessionId must be preserved");
  }

  // ---- getMessages returns a copy ----

  @Test
  void testGetMessagesReturnsCopy() {
    List<Message> messages = new ArrayList<>();
    messages.add(Message.user("initial"));

    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().messages(messages).build();

    Session<Map<String, Object>> session = new Session<>(state);

    List<Message> copy = session.getMessages();
    copy.add(Message.model("extra"));

    List<Message> copy2 = session.getMessages();
    assertEquals(1, copy2.size(), "mutating returned messages must not affect internal state");
  }

  // ---- AgentSessionContext ----

  @Test
  void testAgentSessionContextCurrentReturnsNullWhenNoSession() {
    // Ensure clean state
    assertNull(
        AgentSessionContext.current(),
        "current() must return null when no session is bound to the thread");
  }

  @Test
  void testAgentSessionContextRunBindsSession() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("ctx-sess").build();

    Session<Map<String, Object>> session = new Session<>(state);
    AtomicReference<Session<?>> captured = new AtomicReference<>();

    AgentSessionContext.run(session, () -> captured.set(AgentSessionContext.current()));

    assertSame(session, captured.get(), "run() must bind session to the thread context");
  }

  @Test
  void testAgentSessionContextCallBindsSession() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("ctx-call").build();

    Session<Map<String, Object>> session = new Session<>(state);

    Session<?> captured = AgentSessionContext.call(session, () -> AgentSessionContext.current());

    assertSame(
        session, captured, "call() must bind session to the thread context and return value");
  }

  @Test
  void testAgentSessionContextClearsAfterRun() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("ctx-clear").build();

    Session<Map<String, Object>> session = new Session<>(state);
    AgentSessionContext.run(session, () -> {});

    assertNull(AgentSessionContext.current(), "current() must be null after run() completes");
  }

  @Test
  void testAgentSessionContextNestedRunRestoresPrior() {
    SessionState<Map<String, Object>> outerState =
        SessionState.<Map<String, Object>>builder().sessionId("outer").build();
    Session<Map<String, Object>> outer = new Session<>(outerState);

    SessionState<Map<String, Object>> innerState =
        SessionState.<Map<String, Object>>builder().sessionId("inner").build();
    Session<Map<String, Object>> inner = new Session<>(innerState);

    AgentSessionContext.run(
        outer,
        () -> {
          assertSame(
              outer, AgentSessionContext.current(), "outer session must be bound in outer run");
          AgentSessionContext.run(
              inner,
              () -> {
                assertSame(
                    inner,
                    AgentSessionContext.current(),
                    "inner session must be bound in nested run");
              });
          assertSame(
              outer,
              AgentSessionContext.current(),
              "outer session must be restored after nested run");
        });

    assertNull(AgentSessionContext.current(), "current must be null after outermost run completes");
  }

  @Test
  void testAgentSessionContextNestedCallRestoresPrior() {
    SessionState<Map<String, Object>> outerState =
        SessionState.<Map<String, Object>>builder().sessionId("outer-call").build();
    Session<Map<String, Object>> outer = new Session<>(outerState);

    SessionState<Map<String, Object>> innerState =
        SessionState.<Map<String, Object>>builder().sessionId("inner-call").build();
    Session<Map<String, Object>> inner = new Session<>(innerState);

    String result =
        AgentSessionContext.call(
            outer,
            () -> {
              assertSame(
                  outer,
                  AgentSessionContext.current(),
                  "outer session must be bound in outer call");
              String innerResult =
                  AgentSessionContext.call(
                      inner,
                      () -> {
                        assertSame(
                            inner,
                            AgentSessionContext.current(),
                            "inner session must be bound in nested call");
                        return "inner-done";
                      });
              assertEquals("inner-done", innerResult, "inner call must return its value");
              assertSame(
                  outer,
                  AgentSessionContext.current(),
                  "outer session must be restored after nested call");
              return "outer-done";
            });

    assertEquals("outer-done", result, "outer call must return its value");
    assertNull(
        AgentSessionContext.current(), "current must be null after outermost call completes");
  }

  @Test
  void testCurrentArtifactStoreReturnsNullWhenNoSession() {
    assertNull(
        AgentSessionContext.currentArtifactStore(),
        "currentArtifactStore() must return null when no session is bound");
  }

  @Test
  void testCurrentArtifactStoreReturnsSessionAsArtifactStore() {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("ctx-art").build();

    Session<Map<String, Object>> session = new Session<>(state);

    AtomicReference<ArtifactStore> captured = new AtomicReference<>();
    AgentSessionContext.run(
        session, () -> captured.set(AgentSessionContext.currentArtifactStore()));

    assertNotNull(captured.get(), "currentArtifactStore() must return non-null inside run()");
    assertSame(session, captured.get(), "Session must be the ArtifactStore");
  }

  // ---- ArtifactStore interface via Session ----

  @Test
  void testSessionImplementsArtifactStore() {
    SessionState<Map<String, Object>> state = SessionState.<Map<String, Object>>builder().build();
    Session<Map<String, Object>> session = new Session<>(state);

    assertTrue(session instanceof ArtifactStore, "Session must implement ArtifactStore");

    ArtifactStore store = session;
    store.addArtifacts(Artifact.builder().name("via-store").build());
    assertEquals(1, store.getArtifacts().size());
  }
}
