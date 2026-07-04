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

/**
 * Strategy controlling how artifacts produced by a sub-agent are surfaced back to the delegating
 * model.
 */
public enum ArtifactStrategy {
  /**
   * Include each sub-agent artifact's text content inline in the delegation tool result (in
   * addition to merging it into the parent session). This is the default.
   */
  INLINE,

  /**
   * Merge sub-agent artifacts into the parent session and return only their (namespaced) names in
   * the delegation tool result, not their content.
   */
  SESSION
}
