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

package com.google.genkit.core.jsonpatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.core.JsonUtils;
import org.junit.jupiter.api.Test;

/** Unit tests for JsonPatch (RFC-6902). */
class JsonPatchTest {

  private final ObjectMapper mapper = JsonUtils.getObjectMapper();

  // ──────────────────────────────────────────────────────────────────────────
  // (a) apply: whole-document replace at path ""
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyWholeDocumentReplace() throws Exception {
    JsonNode doc = mapper.readTree("{\"x\":9}");
    JsonNode patch = mapper.readTree("[{\"op\":\"replace\",\"path\":\"\",\"value\":{\"a\":1}}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"a\":1}"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (b) apply: replace a specific field
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyReplaceField() throws Exception {
    JsonNode doc = mapper.readTree("{\"counter\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"replace\",\"path\":\"/counter\",\"value\":2}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"counter\":2}"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (c) apply: add/remove object key; add into array at index and at "-"; remove array element
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyAddNewObjectKey() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"a\":1,\"b\":2}"), result);
  }

  @Test
  void applyRemoveObjectKey() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1,\"b\":2}");
    JsonNode patch = mapper.readTree("[{\"op\":\"remove\",\"path\":\"/b\"}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"a\":1}"), result);
  }

  @Test
  void applyAddIntoArrayAtIndex() throws Exception {
    JsonNode doc = mapper.readTree("{\"items\":[1,3]}");
    JsonNode patch = mapper.readTree("[{\"op\":\"add\",\"path\":\"/items/1\",\"value\":2}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"items\":[1,2,3]}"), result);
  }

  @Test
  void applyAddIntoArrayAtEnd() throws Exception {
    JsonNode doc = mapper.readTree("{\"items\":[1]}");
    JsonNode patch = mapper.readTree("[{\"op\":\"add\",\"path\":\"/items/-\",\"value\":2}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"items\":[1,2]}"), result);
  }

  @Test
  void applyRemoveArrayElement() throws Exception {
    JsonNode doc = mapper.readTree("{\"items\":[1,2,3]}");
    JsonNode patch = mapper.readTree("[{\"op\":\"remove\",\"path\":\"/items/1\"}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"items\":[1,3]}"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (d) pointer escaping: ~1 → /, ~0 → ~
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyPointerEscapingSlash() throws Exception {
    JsonNode doc = mapper.readTree("{}");
    JsonNode patch = mapper.readTree("[{\"op\":\"add\",\"path\":\"/a~1b\",\"value\":1}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"a/b\":1}"), result);
  }

  @Test
  void applyPointerEscapingTilde() throws Exception {
    JsonNode doc = mapper.readTree("{}");
    JsonNode patch = mapper.readTree("[{\"op\":\"add\",\"path\":\"/c~0d\",\"value\":2}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"c~d\":2}"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (e) test op: success leaves doc unchanged; failure raises
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyTestOpSuccess() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"test\",\"path\":\"/a\",\"value\":1}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"a\":1}"), result);
  }

  @Test
  void applyTestOpFailureThrows() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"test\",\"path\":\"/a\",\"value\":2}]");

    assertThrows(IllegalArgumentException.class, () -> JsonPatch.apply(doc, patch));
  }

  @Test
  void applyTestOpRootSuccess() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"test\",\"path\":\"\",\"value\":{\"a\":1}}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"a\":1}"), result);
  }

  @Test
  void applyTestOpRootFailureThrows() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"test\",\"path\":\"\",\"value\":{\"b\":2}}]");

    assertThrows(IllegalArgumentException.class, () -> JsonPatch.apply(doc, patch));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (f) diff: simple replace
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void diffSimpleReplace() throws Exception {
    JsonNode from = mapper.readTree("{\"counter\":1}");
    JsonNode to = mapper.readTree("{\"counter\":2}");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(
        mapper.readTree("[{\"op\":\"replace\",\"path\":\"/counter\",\"value\":2}]"), result);
  }

  @Test
  void diffAddMember() throws Exception {
    JsonNode from = mapper.readTree("{\"a\":1}");
    JsonNode to = mapper.readTree("{\"a\":1,\"b\":2}");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(mapper.readTree("[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]"), result);
  }

  @Test
  void diffRemoveMember() throws Exception {
    JsonNode from = mapper.readTree("{\"a\":1,\"b\":2}");
    JsonNode to = mapper.readTree("{\"a\":1}");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(mapper.readTree("[{\"op\":\"remove\",\"path\":\"/b\"}]"), result);
  }

  @Test
  void diffEqualValues() throws Exception {
    JsonNode from = mapper.readTree("{\"a\":1}");
    JsonNode to = mapper.readTree("{\"a\":1}");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(mapper.readTree("[]"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (g) round-trip: apply(from, diff(from, to)) equals to
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void roundTripObjectAddRemoveReplace() throws Exception {
    JsonNode from = mapper.readTree("{\"a\":1,\"b\":2}");
    JsonNode to = mapper.readTree("{\"a\":3,\"c\":4}");

    assertRoundTrip(from, to);
  }

  @Test
  void roundTripNestedObject() throws Exception {
    JsonNode from = mapper.readTree("{\"nested\":{\"x\":1}}");
    JsonNode to = mapper.readTree("{\"nested\":{\"x\":2,\"y\":3}}");

    assertRoundTrip(from, to);
  }

  @Test
  void roundTripArrayChange() throws Exception {
    JsonNode from = mapper.readTree("{\"items\":[1,2]}");
    JsonNode to = mapper.readTree("{\"items\":[1,2,3]}");

    assertRoundTrip(from, to);
  }

  @Test
  void roundTripArrayShrink() throws Exception {
    JsonNode from = mapper.readTree("{\"items\":[1,2,3]}");
    JsonNode to = mapper.readTree("{\"items\":[1]}");

    assertRoundTrip(from, to);
  }

  @Test
  void roundTripComplexMutation() throws Exception {
    JsonNode from = mapper.readTree("{\"status\":\"a\",\"items\":[1,2],\"nested\":{\"x\":1}}");
    JsonNode to =
        mapper.readTree("{\"status\":\"b\",\"items\":[1,2,3],\"nested\":{\"x\":1,\"y\":2}}");

    assertRoundTrip(from, to);
  }

  @Test
  void roundTripTypeChange() throws Exception {
    // object → array: triggers whole-document replace
    JsonNode from = mapper.readTree("{\"a\":1}");
    JsonNode to = mapper.readTree("[1,2]");

    assertRoundTrip(from, to);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (h) wholeDocumentReplace
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void wholeDocumentReplaceShape() throws Exception {
    JsonNode value = mapper.readTree("{\"a\":1}");

    JsonNode result = JsonPatch.wholeDocumentReplace(value);

    assertEquals(
        mapper.readTree("[{\"op\":\"replace\",\"path\":\"\",\"value\":{\"a\":1}}]"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (i) apply does not mutate input document
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyDoesNotMutateInput() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    String originalJson = doc.toString();

    JsonNode patch = mapper.readTree("[{\"op\":\"replace\",\"path\":\"/a\",\"value\":2}]");
    JsonPatch.apply(doc, patch);

    // Original document must be unchanged
    assertEquals(originalJson, doc.toString());
    assertEquals(1, doc.get("a").asInt());
  }

  @Test
  void applyDoesNotMutateInputOnWholeDocReplace() throws Exception {
    JsonNode doc = mapper.readTree("{\"x\":9}");
    String originalJson = doc.toString();

    JsonNode patch = mapper.readTree("[{\"op\":\"replace\",\"path\":\"\",\"value\":{\"a\":1}}]");
    JsonPatch.apply(doc, patch);

    assertEquals(originalJson, doc.toString());
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Additional: move and copy ops
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyMoveOp() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"move\",\"from\":\"/a\",\"path\":\"/b\"}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"b\":1}"), result);
  }

  @Test
  void applyCopyOp() throws Exception {
    JsonNode doc = mapper.readTree("{\"a\":1}");
    JsonNode patch = mapper.readTree("[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/b\"}]");

    JsonNode result = JsonPatch.apply(doc, patch);

    assertEquals(mapper.readTree("{\"a\":1,\"b\":1}"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Additional: prototype pollution guard
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void applyRejectsForbiddenTokenInPath() throws Exception {
    JsonNode doc = mapper.readTree("{}");
    JsonNode patch = mapper.readTree("[{\"op\":\"add\",\"path\":\"/__proto__/x\",\"value\":1}]");

    assertThrows(IllegalArgumentException.class, () -> JsonPatch.apply(doc, patch));
  }

  // ──────────────────────────────────────────────────────────────────────────
  // (j) diff: arrays are replaced as a single atomic unit
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void diffArrayShrinkEmitsSingleReplaceOp() throws Exception {
    // diff([1,2,3], [1,2]) must emit exactly ONE replace op with the full new array
    JsonNode from = mapper.readTree("{\"items\":[1,2,3]}");
    JsonNode to = mapper.readTree("{\"items\":[1,2]}");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(1, result.size(), "Expected exactly one op, got: " + result);
    JsonNode op = result.get(0);
    assertEquals("replace", op.get("op").asText());
    assertEquals("/items", op.get("path").asText());
    assertEquals(mapper.readTree("[1,2]"), op.get("value"));
  }

  @Test
  void diffArrayGrowEmitsSingleReplaceOp() throws Exception {
    // diff([1,2], [1,2,3]) must emit exactly ONE replace op with the full new array
    JsonNode from = mapper.readTree("{\"items\":[1,2]}");
    JsonNode to = mapper.readTree("{\"items\":[1,2,3]}");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(1, result.size(), "Expected exactly one op, got: " + result);
    JsonNode op = result.get(0);
    assertEquals("replace", op.get("op").asText());
    assertEquals("/items", op.get("path").asText());
    assertEquals(mapper.readTree("[1,2,3]"), op.get("value"));
  }

  @Test
  void diffRootLevelArraysEmitsSingleReplaceOpWithEmptyPath() throws Exception {
    // When the root documents are both arrays and differ, path must be ""
    JsonNode from = mapper.readTree("[1,2,3]");
    JsonNode to = mapper.readTree("[1,2]");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(1, result.size(), "Expected exactly one op, got: " + result);
    JsonNode op = result.get(0);
    assertEquals("replace", op.get("op").asText());
    assertEquals("", op.get("path").asText());
    assertEquals(mapper.readTree("[1,2]"), op.get("value"));
  }

  @Test
  void diffEqualArraysEmitsNoOps() throws Exception {
    JsonNode from = mapper.readTree("{\"items\":[1,2,3]}");
    JsonNode to = mapper.readTree("{\"items\":[1,2,3]}");

    JsonNode result = JsonPatch.diff(from, to);

    assertEquals(mapper.readTree("[]"), result);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Additional: pointer escaping in diff output
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  void diffEscapesPointerTokens() throws Exception {
    // Keys with "/" and "~" must be escaped in pointer paths
    ObjectNode from = mapper.createObjectNode();
    ObjectNode to = mapper.createObjectNode();
    to.put("a/b", 1);
    to.put("c~d", 2);

    JsonNode result = JsonPatch.diff(from, to);

    // The paths must use ~1 for "/" and ~0 for "~"
    String resultStr = result.toString();
    assertTrue(resultStr.contains("/a~1b"), "Expected escaped /a~1b in: " + resultStr);
    assertTrue(resultStr.contains("/c~0d"), "Expected escaped /c~0d in: " + resultStr);
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  private void assertRoundTrip(JsonNode from, JsonNode to) {
    JsonNode patch = JsonPatch.diff(from, to);
    JsonNode result = JsonPatch.apply(from, patch);
    assertEquals(to, result, "Round-trip failed. patch=" + patch);
  }
}
