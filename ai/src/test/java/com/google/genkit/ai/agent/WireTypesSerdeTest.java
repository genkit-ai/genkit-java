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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.ModelResponseChunk;
import com.google.genkit.ai.Part;
import com.google.genkit.core.JsonUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** TDD tests for agent wire data types serialization/deserialization. */
class WireTypesSerdeTest {

  private static final ObjectMapper mapper = JsonUtils.getObjectMapper();

  // ---- SessionState tests ----

  @Test
  void testSessionStateSerializesExactFieldNames() throws Exception {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder()
            .sessionId("sess-1")
            .messages(Collections.singletonList(Message.user("hello")))
            .build();

    JsonNode node = mapper.valueToTree(state);
    assertTrue(node.has("sessionId"), "must have sessionId");
    assertTrue(node.has("messages"), "must have messages");
    assertFalse(node.has("custom"), "custom must be absent when null");
    assertFalse(node.has("artifacts"), "artifacts must be absent when null");
  }

  @Test
  void testSessionStateWithCustomRoundTrip() throws Exception {
    Map<String, Object> custom = new HashMap<>();
    custom.put("key", "value");

    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("sess-2").custom(custom).build();

    String json = mapper.writeValueAsString(state);
    assertTrue(json.contains("\"custom\""));
    assertTrue(json.contains("\"sessionId\""));

    SessionState<Map<String, Object>> deserialized =
        mapper.readValue(json, new TypeReference<SessionState<Map<String, Object>>>() {});
    assertEquals("sess-2", deserialized.getSessionId());
    assertEquals("value", deserialized.getCustom().get("key"));
  }

  // ---- AgentInit tests ----

  @Test
  void testAgentInitSerializesExactFieldNames() throws Exception {
    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder().snapshotId("snap-1").sessionId("sess-1").build();

    JsonNode node = mapper.valueToTree(init);
    assertTrue(node.has("snapshotId"), "must have snapshotId");
    assertTrue(node.has("sessionId"), "must have sessionId");
    assertFalse(node.has("state"), "state must be absent when null");
  }

  @Test
  void testAgentInitRoundTrip() throws Exception {
    SessionState<Map<String, Object>> state =
        SessionState.<Map<String, Object>>builder().sessionId("sess-x").build();

    AgentInit<Map<String, Object>> init =
        AgentInit.<Map<String, Object>>builder()
            .snapshotId("snap-rt")
            .sessionId("sess-rt")
            .state(state)
            .build();

    String json = mapper.writeValueAsString(init);
    AgentInit<Map<String, Object>> deserialized =
        mapper.readValue(json, new TypeReference<AgentInit<Map<String, Object>>>() {});
    assertEquals("snap-rt", deserialized.getSnapshotId());
    assertEquals("sess-rt", deserialized.getSessionId());
    assertNotNull(deserialized.getState());
    assertEquals("sess-x", deserialized.getState().getSessionId());
  }

  // ---- ToolResume tests ----

  @Test
  void testToolResumeSerializesExactFieldNames() throws Exception {
    ToolResume resume =
        ToolResume.builder().respond(Collections.singletonList(Part.text("response"))).build();

    JsonNode node = mapper.valueToTree(resume);
    assertTrue(node.has("respond"), "must have respond");
    assertFalse(node.has("restart"), "restart must be absent when null");
  }

  @Test
  void testToolResumeRoundTrip() throws Exception {
    ToolResume resume =
        ToolResume.builder()
            .respond(Arrays.asList(Part.text("resp1"), Part.text("resp2")))
            .restart(Collections.singletonList(Part.text("restart1")))
            .build();

    String json = mapper.writeValueAsString(resume);
    assertTrue(json.contains("\"respond\""));
    assertTrue(json.contains("\"restart\""));

    ToolResume deserialized = mapper.readValue(json, ToolResume.class);
    assertEquals(2, deserialized.getRespond().size());
    assertEquals(1, deserialized.getRestart().size());
  }

  // ---- AgentInput tests ----

  @Test
  void testAgentInputWithNoDetachOmitsKey() throws Exception {
    AgentInput input = AgentInput.builder().message(Message.user("hello")).build();

    JsonNode node = mapper.valueToTree(input);
    assertTrue(node.has("message"), "must have message");
    assertFalse(node.has("detach"), "detach must be absent when null");
    assertFalse(node.has("resume"), "resume must be absent when null");
  }

  @Test
  void testAgentInputWithDetachFalseOmitsKey() throws Exception {
    AgentInput input = AgentInput.builder().detach(false).build();

    JsonNode node = mapper.valueToTree(input);
    assertFalse(node.has("detach"), "detach must be absent when false");
  }

  @Test
  void testAgentInputWithDetachTrueIncludesKey() throws Exception {
    AgentInput input = AgentInput.builder().detach(true).build();

    JsonNode node = mapper.valueToTree(input);
    assertTrue(node.has("detach"), "detach must be present when true");
    assertTrue(node.get("detach").asBoolean(), "detach must be true");
  }

  @Test
  void testAgentInputWithDetachRoundTrip() throws Exception {
    AgentInput input = AgentInput.builder().detach(true).build();

    String json = mapper.writeValueAsString(input);
    assertTrue(json.contains("\"detach\":true"));

    AgentInput deserialized = mapper.readValue(json, AgentInput.class);
    assertTrue(deserialized.getDetach());
  }

  // ---- AgentOutput tests ----

  @Test
  void testAgentOutputSerializesExactFieldNames() throws Exception {
    AgentOutput<Map<String, Object>> output =
        AgentOutput.<Map<String, Object>>builder()
            .sessionId("sess-out")
            .finishReason(AgentFinishReason.STOP)
            .build();

    JsonNode node = mapper.valueToTree(output);
    assertTrue(node.has("sessionId"), "must have sessionId");
    assertTrue(node.has("finishReason"), "must have finishReason");
    assertFalse(node.has("snapshotId"), "snapshotId must be absent when null");
    assertFalse(node.has("state"), "state must be absent when null");
    assertFalse(node.has("message"), "message must be absent when null");
    assertFalse(node.has("artifacts"), "artifacts must be absent when null");
    assertFalse(node.has("error"), "error must be absent when null");
  }

  @Test
  void testAgentOutputRoundTrip() throws Exception {
    AgentOutput<Map<String, Object>> output =
        AgentOutput.<Map<String, Object>>builder()
            .sessionId("sess-out-rt")
            .snapshotId("snap-out-rt")
            .finishReason(AgentFinishReason.STOP)
            .message(Message.model("done"))
            .build();

    String json = mapper.writeValueAsString(output);
    AgentOutput<Map<String, Object>> deserialized =
        mapper.readValue(json, new TypeReference<AgentOutput<Map<String, Object>>>() {});
    assertEquals("sess-out-rt", deserialized.getSessionId());
    assertEquals("snap-out-rt", deserialized.getSnapshotId());
    assertEquals(AgentFinishReason.STOP, deserialized.getFinishReason());
  }

  // ---- AgentResult tests ----

  @Test
  void testAgentResultSerializesExactFieldNames() throws Exception {
    AgentResult result = AgentResult.builder().finishReason(AgentFinishReason.LENGTH).build();

    JsonNode node = mapper.valueToTree(result);
    assertTrue(node.has("finishReason"), "must have finishReason");
    assertFalse(node.has("message"), "message must be absent when null");
    assertFalse(node.has("artifacts"), "artifacts must be absent when null");
  }

  @Test
  void testAgentResultRoundTrip() throws Exception {
    AgentResult result =
        AgentResult.builder()
            .message(Message.model("result"))
            .finishReason(AgentFinishReason.STOP)
            .build();

    String json = mapper.writeValueAsString(result);
    AgentResult deserialized = mapper.readValue(json, AgentResult.class);
    assertEquals(AgentFinishReason.STOP, deserialized.getFinishReason());
    assertNotNull(deserialized.getMessage());
  }

  // ---- TurnEnd tests ----

  @Test
  void testTurnEndSerializesExactFieldNames() throws Exception {
    TurnEnd turnEnd =
        TurnEnd.builder().snapshotId("snap-turn").finishReason(AgentFinishReason.STOP).build();

    JsonNode node = mapper.valueToTree(turnEnd);
    assertTrue(node.has("snapshotId"), "must have snapshotId");
    assertTrue(node.has("finishReason"), "must have finishReason");
  }

  @Test
  void testTurnEndRoundTrip() throws Exception {
    TurnEnd turnEnd =
        TurnEnd.builder().snapshotId("snap-rt").finishReason(AgentFinishReason.INTERRUPTED).build();

    String json = mapper.writeValueAsString(turnEnd);
    TurnEnd deserialized = mapper.readValue(json, TurnEnd.class);
    assertEquals("snap-rt", deserialized.getSnapshotId());
    assertEquals(AgentFinishReason.INTERRUPTED, deserialized.getFinishReason());
  }

  // ---- Artifact tests ----

  @Test
  void testArtifactAlwaysEmitsParts() throws Exception {
    Artifact artifact =
        Artifact.builder()
            .name("my-artifact")
            .parts(Collections.singletonList(Part.text("data")))
            .build();

    JsonNode node = mapper.valueToTree(artifact);
    assertTrue(node.has("name"), "must have name");
    assertTrue(node.has("parts"), "parts must always be present");
    assertFalse(node.has("metadata"), "metadata must be absent when null");
  }

  @Test
  void testArtifactRoundTrip() throws Exception {
    Map<String, Object> meta = new HashMap<>();
    meta.put("version", 1);

    Artifact artifact =
        Artifact.builder()
            .name("art-rt")
            .parts(Arrays.asList(Part.text("p1"), Part.text("p2")))
            .metadata(meta)
            .build();

    String json = mapper.writeValueAsString(artifact);
    assertTrue(json.contains("\"parts\""));
    assertTrue(json.contains("\"metadata\""));

    Artifact deserialized = mapper.readValue(json, Artifact.class);
    assertEquals("art-rt", deserialized.getName());
    assertEquals(2, deserialized.getParts().size());
    assertEquals(1, deserialized.getMetadata().get("version"));
  }

  // ---- AgentStreamChunk tests ----

  @Test
  void testAgentStreamChunkSerializesExactFieldNames() throws Exception {
    ModelResponseChunk modelChunk = ModelResponseChunk.text("chunk text");
    AgentStreamChunk chunk = AgentStreamChunk.builder().modelChunk(modelChunk).build();

    JsonNode node = mapper.valueToTree(chunk);
    assertTrue(node.has("modelChunk"), "must have modelChunk");
    assertFalse(node.has("customPatch"), "customPatch must be absent when null");
    assertFalse(node.has("artifact"), "artifact must be absent when null");
    assertFalse(node.has("turnEnd"), "turnEnd must be absent when null");
  }

  @Test
  void testAgentStreamChunkWithCustomPatchRoundTrip() throws Exception {
    // customPatch is a JSON array of patch ops
    JsonNode patch = mapper.readTree("[{\"op\":\"add\",\"path\":\"/x\",\"value\":1}]");

    AgentStreamChunk chunk = AgentStreamChunk.builder().customPatch(patch).build();

    String json = mapper.writeValueAsString(chunk);
    assertTrue(json.contains("\"customPatch\""));
    assertTrue(json.contains("\"op\""));

    AgentStreamChunk deserialized = mapper.readValue(json, AgentStreamChunk.class);
    assertNotNull(deserialized.getCustomPatch());
    assertTrue(deserialized.getCustomPatch().isArray());
    assertEquals(1, deserialized.getCustomPatch().size());
    assertEquals("add", deserialized.getCustomPatch().get(0).get("op").asText());
  }

  // ---- RuntimeError tests ----

  @Test
  void testRuntimeErrorSerializesExactFieldNames() throws Exception {
    RuntimeError error =
        RuntimeError.builder().status("500").message("something went wrong").build();

    JsonNode node = mapper.valueToTree(error);
    assertTrue(node.has("status"), "must have status");
    assertTrue(node.has("message"), "must have message");
    assertFalse(node.has("details"), "details must be absent when null");
  }

  @Test
  void testRuntimeErrorRoundTrip() throws Exception {
    Map<String, Object> details = new HashMap<>();
    details.put("code", 500);

    RuntimeError error =
        RuntimeError.builder().status("500").message("error msg").details(details).build();

    String json = mapper.writeValueAsString(error);
    RuntimeError deserialized = mapper.readValue(json, RuntimeError.class);
    assertEquals("500", deserialized.getStatus());
    assertEquals("error msg", deserialized.getMessage());
    assertNotNull(deserialized.getDetails());
  }

  // ---- SessionSnapshot tests ----

  @Test
  void testSessionSnapshotWithOnlyRequiredFieldsOmitsRest() throws Exception {
    SessionSnapshot<Map<String, Object>> snapshot =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-min")
            .createdAt("2025-01-01T00:00:00Z")
            .build();

    JsonNode node = mapper.valueToTree(snapshot);
    assertTrue(node.has("snapshotId"), "must have snapshotId");
    assertTrue(node.has("createdAt"), "must have createdAt");
    assertFalse(node.has("sessionId"), "sessionId must be absent when null");
    assertFalse(node.has("parentId"), "parentId must be absent when null");
    assertFalse(node.has("updatedAt"), "updatedAt must be absent when null");
    assertFalse(node.has("heartbeatAt"), "heartbeatAt must be absent when null");
    assertFalse(node.has("status"), "status must be absent when null");
    assertFalse(node.has("finishReason"), "finishReason must be absent when null");
    assertFalse(node.has("error"), "error must be absent when null");
    assertFalse(node.has("state"), "state must be absent when null");
  }

  @Test
  void testSessionSnapshotFullRoundTrip() throws Exception {
    SessionSnapshot<Map<String, Object>> snapshot =
        SessionSnapshot.<Map<String, Object>>builder()
            .snapshotId("snap-full")
            .sessionId("sess-full")
            .parentId("parent-1")
            .createdAt("2025-01-01T00:00:00Z")
            .updatedAt("2025-01-02T00:00:00Z")
            .heartbeatAt("2025-01-02T01:00:00Z")
            .status(SnapshotStatus.COMPLETED)
            .finishReason(AgentFinishReason.STOP)
            .build();

    String json = mapper.writeValueAsString(snapshot);
    assertTrue(json.contains("\"snapshotId\""));
    assertTrue(json.contains("\"sessionId\""));
    assertTrue(json.contains("\"parentId\""));
    assertTrue(json.contains("\"createdAt\""));
    assertTrue(json.contains("\"updatedAt\""));
    assertTrue(json.contains("\"heartbeatAt\""));
    assertTrue(json.contains("\"status\""));
    assertTrue(json.contains("\"finishReason\""));

    SessionSnapshot<Map<String, Object>> deserialized =
        mapper.readValue(json, new TypeReference<SessionSnapshot<Map<String, Object>>>() {});
    assertEquals("snap-full", deserialized.getSnapshotId());
    assertEquals("sess-full", deserialized.getSessionId());
    assertEquals(SnapshotStatus.COMPLETED, deserialized.getStatus());
    assertEquals(AgentFinishReason.STOP, deserialized.getFinishReason());
  }

  // ---- GetSnapshotRequest tests ----

  @Test
  void testGetSnapshotRequestRoundTrip() throws Exception {
    GetSnapshotRequest req =
        GetSnapshotRequest.builder().snapshotId("snap-req").sessionId("sess-req").build();

    JsonNode node = mapper.valueToTree(req);
    assertTrue(node.has("snapshotId"), "must have snapshotId");
    assertTrue(node.has("sessionId"), "must have sessionId");

    String json = mapper.writeValueAsString(req);
    GetSnapshotRequest deserialized = mapper.readValue(json, GetSnapshotRequest.class);
    assertEquals("snap-req", deserialized.getSnapshotId());
    assertEquals("sess-req", deserialized.getSessionId());
  }

  // ---- AgentAbortRequest / AgentAbortResponse tests ----

  @Test
  void testAgentAbortRequestRoundTrip() throws Exception {
    AgentAbortRequest req = AgentAbortRequest.builder().snapshotId("snap-abort").build();

    JsonNode node = mapper.valueToTree(req);
    assertTrue(node.has("snapshotId"), "must have snapshotId");

    String json = mapper.writeValueAsString(req);
    AgentAbortRequest deserialized = mapper.readValue(json, AgentAbortRequest.class);
    assertEquals("snap-abort", deserialized.getSnapshotId());
  }

  @Test
  void testAgentAbortResponseRoundTrip() throws Exception {
    AgentAbortResponse resp =
        AgentAbortResponse.builder()
            .snapshotId("snap-abort-resp")
            .status(SnapshotStatus.ABORTED)
            .build();

    JsonNode node = mapper.valueToTree(resp);
    assertTrue(node.has("snapshotId"), "must have snapshotId");
    assertTrue(node.has("status"), "must have status");

    String json = mapper.writeValueAsString(resp);
    AgentAbortResponse deserialized = mapper.readValue(json, AgentAbortResponse.class);
    assertEquals("snap-abort-resp", deserialized.getSnapshotId());
    assertEquals(SnapshotStatus.ABORTED, deserialized.getStatus());
  }

  // ---- AgentMetadata tests ----

  @Test
  void testAgentMetadataSerializesExactFieldNames() throws Exception {
    AgentMetadata metadata =
        AgentMetadata.builder().stateManagement("server").abortable(true).build();

    JsonNode node = mapper.valueToTree(metadata);
    assertTrue(node.has("stateManagement"), "must have stateManagement");
    assertTrue(node.has("abortable"), "must have abortable");
    assertFalse(node.has("stateSchema"), "stateSchema must be absent when null");
  }

  @Test
  void testAgentMetadataRoundTrip() throws Exception {
    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");

    AgentMetadata metadata =
        AgentMetadata.builder()
            .stateManagement("client")
            .abortable(false)
            .stateSchema(schema)
            .build();

    String json = mapper.writeValueAsString(metadata);
    assertTrue(json.contains("\"stateManagement\""));
    assertTrue(json.contains("\"abortable\""));
    assertTrue(json.contains("\"stateSchema\""));

    AgentMetadata deserialized = mapper.readValue(json, AgentMetadata.class);
    assertEquals("client", deserialized.getStateManagement());
    assertFalse(deserialized.isAbortable());
    assertNotNull(deserialized.getStateSchema());
    assertEquals("object", deserialized.getStateSchema().get("type"));
  }
}
