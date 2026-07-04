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

import java.util.List;

/**
 * ArtifactStore is a state-agnostic view of artifact storage used by middleware and tools that do
 * not need to know the custom state type {@code S} of the session.
 *
 * <p>{@link Session} implements this interface.
 */
public interface ArtifactStore {

  /**
   * Returns a copy of the current list of artifacts.
   *
   * @return a copy of the artifacts (never null)
   */
  List<Artifact> getArtifacts();

  /**
   * Adds artifacts, deduplicating by name. If an artifact with the same non-null name already
   * exists, it is replaced in place. Artifacts with null names are always appended.
   *
   * @param artifacts the artifacts to add
   */
  void addArtifacts(Artifact... artifacts);
}
