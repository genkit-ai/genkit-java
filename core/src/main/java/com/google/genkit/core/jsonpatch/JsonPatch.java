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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genkit.core.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A tiny, dependency-free RFC 6902 (JSON Patch) implementation operating on Jackson {@link
 * JsonNode}.
 *
 * <p>Genkit uses JSON Patch to stream incremental changes to a session's custom state ({@code
 * AgentStreamChunk.customPatch}). The {@link #diff} helper only emits {@code add} / {@code remove}
 * / {@code replace} operations (a valid RFC 6902 subset; {@code move} / {@code copy} are
 * optimisations we deliberately skip from diff output), while {@link #apply} understands the full
 * operation set for interoperability.
 *
 * <p>Ported from {@code js/ai/src/json-patch.ts} in the Genkit upstream repository.
 */
public final class JsonPatch {

  /** Reference tokens that could be used for prototype-pollution-style attacks. */
  private static final Set<String> FORBIDDEN_TOKENS = new LinkedHashSet<>();

  static {
    FORBIDDEN_TOKENS.add("__proto__");
    FORBIDDEN_TOKENS.add("prototype");
    FORBIDDEN_TOKENS.add("constructor");
  }

  private JsonPatch() {}

  // ────────────────────────────────────────────────────────────────────────────
  // Public API
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Applies an RFC-6902 patch (a JSON array of ops) to {@code document}, returning the new
   * document.
   *
   * <p>The input document is not mutated; a deep copy is patched and returned.
   *
   * @param document the document to patch (may be {@code null} / {@link NullNode})
   * @param patch an {@link ArrayNode} of RFC-6902 operation objects
   * @return the patched document
   * @throws IllegalArgumentException if a {@code test} op fails or the patch is malformed
   */
  public static JsonNode apply(JsonNode document, JsonNode patch) {
    JsonNode doc = deepCopy(document);
    for (JsonNode op : patch) {
      doc = applyOperation(doc, op);
    }
    return doc;
  }

  /**
   * Computes a minimal RFC-6902 patch that transforms {@code from} into {@code to}.
   *
   * <p>Only {@code add}, {@code remove}, and {@code replace} ops are emitted. Arrays that differ
   * are replaced as a single atomic unit (one {@code replace} op for the whole array), matching the
   * JS reference implementation in {@code js/ai/src/json-patch.ts}.
   *
   * @param from the source document
   * @param to the target document
   * @return an {@link ArrayNode} of RFC-6902 operation objects
   */
  public static JsonNode diff(JsonNode from, JsonNode to) {
    ArrayNode patch = JsonUtils.getObjectMapper().createArrayNode();
    diffRecursive(from, to, "", patch);
    return patch;
  }

  /**
   * Returns a whole-document replace patch: {@code [{"op":"replace","path":"","value":value}]}.
   *
   * @param value the value to place at the document root
   * @return a single-element {@link ArrayNode}
   */
  public static JsonNode wholeDocumentReplace(JsonNode value) {
    ArrayNode patch = JsonUtils.getObjectMapper().createArrayNode();
    ObjectNode op = JsonUtils.getObjectMapper().createObjectNode();
    op.put("op", "replace");
    op.put("path", "");
    op.set("value", deepCopy(value));
    patch.add(op);
    return patch;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // apply internals
  // ────────────────────────────────────────────────────────────────────────────

  private static JsonNode applyOperation(JsonNode doc, JsonNode op) {
    String opName = op.path("op").asText();
    String path = op.path("path").asText();
    List<String> tokens = parsePointer(path);

    if (tokens.isEmpty()) {
      // Root-level operations
      switch (opName) {
        case "add":
        case "replace":
          return deepCopy(op.get("value"));
        case "remove":
          return NullNode.getInstance();
        case "test":
          {
            JsonNode expected = op.get("value");
            if (!deepEqual(doc, expected)) {
              throw new IllegalArgumentException("JSON Patch 'test' failed at root.");
            }
            return doc;
          }
        case "move":
        case "copy":
          {
            String fromPath = op.path("from").asText();
            List<String> fromTokens = parsePointer(fromPath);
            return deepCopy(getValue(doc, fromTokens));
          }
        default:
          throw new IllegalArgumentException("Unsupported JSON Patch op: " + opName);
      }
    }

    // Lenient: initialize missing root container for add/replace
    if ((doc == null || doc.isNull() || doc.isMissingNode())
        && ("add".equals(opName) || "replace".equals(opName))) {
      doc = JsonUtils.getObjectMapper().createObjectNode();
    }

    switch (opName) {
      case "add":
        setValue(doc, tokens, deepCopy(op.get("value")), true);
        return doc;
      case "replace":
        setValue(doc, tokens, deepCopy(op.get("value")), false);
        return doc;
      case "remove":
        removeValue(doc, tokens);
        return doc;
      case "test":
        {
          JsonNode actual = getValue(doc, tokens);
          JsonNode expected = op.get("value");
          if (!deepEqual(actual, expected)) {
            throw new IllegalArgumentException("JSON Patch 'test' failed at \"" + path + "\".");
          }
          return doc;
        }
      case "move":
        {
          String fromPath = op.path("from").asText();
          List<String> fromTokens = parsePointer(fromPath);
          JsonNode value = deepCopy(getValue(doc, fromTokens));
          removeValue(doc, fromTokens);
          setValue(doc, tokens, value, true);
          return doc;
        }
      case "copy":
        {
          String fromPath = op.path("from").asText();
          List<String> fromTokens = parsePointer(fromPath);
          JsonNode value = deepCopy(getValue(doc, fromTokens));
          setValue(doc, tokens, value, true);
          return doc;
        }
      default:
        throw new IllegalArgumentException("Unsupported JSON Patch op: " + opName);
    }
  }

  /** Reads the value at {@code tokens}, returning {@link NullNode} for any missing segment. */
  private static JsonNode getValue(JsonNode doc, List<String> tokens) {
    JsonNode cur = doc;
    for (String token : tokens) {
      if (cur == null || cur.isNull() || cur.isMissingNode()) {
        return NullNode.getInstance();
      }
      if (cur.isArray()) {
        int idx = parseIndex(token);
        if (idx < 0 || idx >= cur.size()) {
          return NullNode.getInstance();
        }
        cur = cur.get(idx);
      } else if (cur.isObject()) {
        cur = cur.path(token);
        if (cur.isMissingNode()) {
          return NullNode.getInstance();
        }
      } else {
        return NullNode.getInstance();
      }
    }
    return cur != null ? cur : NullNode.getInstance();
  }

  /**
   * Sets the value at {@code tokens}. When {@code isAdd} is true and the parent is an array, the
   * special {@code -} token appends and a numeric token inserts at that index.
   */
  private static void setValue(JsonNode doc, List<String> tokens, JsonNode value, boolean isAdd) {
    JsonNode parent = ensureParent(doc, tokens);
    if (parent == null || parent.isNull() || parent.isMissingNode()) {
      return; // lenient: nothing to set onto
    }
    String last = tokens.get(tokens.size() - 1);
    if (parent.isArray()) {
      ArrayNode arr = (ArrayNode) parent;
      if ("-".equals(last)) {
        arr.add(value);
        return;
      }
      int idx = parseIndex(last);
      if (idx < 0) {
        return;
      }
      if (isAdd) {
        arr.insert(idx, value);
      } else {
        arr.set(idx, value);
      }
      return;
    }
    if (parent.isObject()) {
      ((ObjectNode) parent).set(last, value);
    }
  }

  /** Removes the value at {@code tokens}. Missing members are a no-op. */
  private static void removeValue(JsonNode doc, List<String> tokens) {
    List<String> parentTokens = tokens.subList(0, tokens.size() - 1);
    JsonNode parent = getValue(doc, parentTokens);
    if (parent == null || parent.isNull() || parent.isMissingNode()) {
      return;
    }
    String last = tokens.get(tokens.size() - 1);
    if (parent.isArray()) {
      int idx = parseIndex(last);
      if (idx >= 0 && idx < parent.size()) {
        ((ArrayNode) parent).remove(idx);
      }
    } else if (parent.isObject()) {
      ((ObjectNode) parent).remove(last);
    }
  }

  /**
   * Walks to the parent container of {@code tokens}, lazily creating intermediate object nodes for
   * missing segments (lenient apply behaviour).
   */
  private static JsonNode ensureParent(JsonNode doc, List<String> tokens) {
    JsonNode cur = doc;
    for (int i = 0; i < tokens.size() - 1; i++) {
      String token = tokens.get(i);
      if (cur == null || cur.isNull() || cur.isMissingNode()) {
        return null;
      }
      JsonNode next;
      if (cur.isArray()) {
        int idx = parseIndex(token);
        if (idx < 0) {
          return null;
        }
        next = cur.get(idx);
      } else {
        next = cur.path(token);
      }
      if (next == null || next.isMissingNode() || next.isNull() || !next.isContainerNode()) {
        // Create an intermediate object container
        ObjectNode created = JsonUtils.getObjectMapper().createObjectNode();
        if (cur.isArray()) {
          int idx = parseIndex(token);
          ((ArrayNode) cur).set(idx, created);
        } else {
          ((ObjectNode) cur).set(token, created);
        }
        cur = created;
      } else {
        cur = next;
      }
    }
    return cur;
  }

  // ────────────────────────────────────────────────────────────────────────────
  // diff internals
  // ────────────────────────────────────────────────────────────────────────────

  private static void diffRecursive(JsonNode from, JsonNode to, String pointer, ArrayNode patch) {
    if (deepEqual(from, to)) {
      return;
    }

    // Both plain objects → recurse member-by-member
    if (isObject(from) && isObject(to)) {
      Set<String> keys = new LinkedHashSet<>();
      from.fieldNames().forEachRemaining(keys::add);
      to.fieldNames().forEachRemaining(keys::add);

      for (String key : keys) {
        String childPointer = pointer + "/" + escapeToken(key);
        boolean inFrom = from.has(key);
        boolean inTo = to.has(key);
        if (inFrom && !inTo) {
          ObjectNode op = JsonUtils.getObjectMapper().createObjectNode();
          op.put("op", "remove");
          op.put("path", childPointer);
          patch.add(op);
        } else if (!inFrom && inTo) {
          ObjectNode op = JsonUtils.getObjectMapper().createObjectNode();
          op.put("op", "add");
          op.put("path", childPointer);
          op.set("value", deepCopy(to.get(key)));
          patch.add(op);
        } else if (inFrom && inTo) {
          diffRecursive(from.get(key), to.get(key), childPointer, patch);
        }
      }
      return;
    }

    // Both arrays → treat the array as a single atomic value; emit one replace op
    if (from != null && from.isArray() && to != null && to.isArray()) {
      // deepEqual already returned false above, so they differ
      ObjectNode op = JsonUtils.getObjectMapper().createObjectNode();
      op.put("op", "replace");
      op.put("path", pointer);
      op.set("value", deepCopy(to));
      patch.add(op);
      return;
    }

    // Type mismatch or differing primitives → replace at this location
    ObjectNode op = JsonUtils.getObjectMapper().createObjectNode();
    op.put("op", "replace");
    op.put("path", pointer);
    op.set("value", deepCopy(to));
    patch.add(op);
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Pointer helpers
  // ────────────────────────────────────────────────────────────────────────────

  /**
   * Parses a JSON Pointer string (RFC-6901) into its reference tokens.
   *
   * <p>The root pointer ({@code ""}) returns an empty list. Forbidden tokens ({@code __proto__},
   * {@code prototype}, {@code constructor}) are rejected.
   */
  private static List<String> parsePointer(String pointer) {
    if (pointer == null || pointer.isEmpty()) {
      return new ArrayList<>();
    }
    if (pointer.charAt(0) != '/') {
      throw new IllegalArgumentException(
          "Invalid JSON Pointer: \"" + pointer + "\" must start with \"/\".");
    }
    String[] parts = pointer.substring(1).split("/", -1);
    List<String> tokens = new ArrayList<>(parts.length);
    for (String part : parts) {
      String token = unescapeToken(part);
      if (FORBIDDEN_TOKENS.contains(token)) {
        throw new IllegalArgumentException(
            "Invalid JSON Pointer: \""
                + pointer
                + "\" contains forbidden token \""
                + token
                + "\".");
      }
      tokens.add(token);
    }
    return tokens;
  }

  /**
   * Escapes a single reference token per RFC-6901 ({@code ~} → {@code ~0}, {@code /} → {@code ~1}).
   */
  private static String escapeToken(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }

  /**
   * Unescapes a single reference token per RFC-6901 ({@code ~1} → {@code /}, {@code ~0} → {@code
   * ~}).
   */
  private static String unescapeToken(String token) {
    // Order matters: unescape ~1 before ~0
    return token.replace("~1", "/").replace("~0", "~");
  }

  // ────────────────────────────────────────────────────────────────────────────
  // JsonNode helpers
  // ────────────────────────────────────────────────────────────────────────────

  private static boolean isObject(JsonNode node) {
    return node != null && node.isObject();
  }

  /**
   * Deep structural equality for {@link JsonNode} values, mirroring JSON semantics (missing nodes
   * equal null).
   */
  private static boolean deepEqual(JsonNode a, JsonNode b) {
    // Normalize nulls/missing
    boolean aNullish = a == null || a.isNull() || a.isMissingNode();
    boolean bNullish = b == null || b.isNull() || b.isMissingNode();
    if (aNullish && bNullish) {
      return true;
    }
    if (aNullish || bNullish) {
      return false;
    }
    return a.equals(b);
  }

  /** Returns a deep copy of {@code node}, or {@link NullNode} if {@code node} is null. */
  private static JsonNode deepCopy(JsonNode node) {
    if (node == null) {
      return NullNode.getInstance();
    }
    return node.deepCopy();
  }

  /** Parses an array index token. Returns -1 if the token is not a non-negative integer. */
  private static int parseIndex(String token) {
    try {
      int idx = Integer.parseInt(token);
      return idx >= 0 ? idx : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
