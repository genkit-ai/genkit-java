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

/**
 * RFC 6902 (JSON Patch) support for Genkit Java.
 *
 * <p>This package provides a hand-rolled, dependency-free RFC 6902 JSON Patch implementation
 * operating on Jackson {@link com.fasterxml.jackson.databind.JsonNode}. It is intentionally
 * self-contained so that the diff output shape is controlled exactly — no external JSON-patch
 * library is used.
 *
 * <p>Genkit uses JSON Patch to stream incremental changes to a session's custom state ({@code
 * AgentStreamChunk.customPatch}). The first patch of every agent turn is a whole-document replace
 * at the root pointer ({@code ""}); subsequent patches are incremental diffs.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * JsonNode from = mapper.readTree("{\"counter\":1}");
 * JsonNode to   = mapper.readTree("{\"counter\":2}");
 *
 * // Compute a minimal patch
 * JsonNode patch = JsonPatch.diff(from, to);
 * // → [{"op":"replace","path":"/counter","value":2}]
 *
 * // Apply the patch (does not mutate 'from')
 * JsonNode result = JsonPatch.apply(from, patch);
 * // → {"counter":2}
 *
 * // Whole-document replace (first patch of a turn)
 * JsonNode firstPatch = JsonPatch.wholeDocumentReplace(to);
 * // → [{"op":"replace","path":"","value":{"counter":2}}]
 * }</pre>
 *
 * @see com.google.genkit.core.jsonpatch.JsonPatch
 */
package com.google.genkit.core.jsonpatch;
