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

package com.google.genkit.ai.agent.internal;

import com.google.genkit.ai.agent.SnapshotMutator;
import com.google.genkit.ai.agent.SnapshotStatus;

/**
 * Wraps a {@link SnapshotMutator} so that it returns {@code null} (no-op) when the current row is
 * already {@link SnapshotStatus#ABORTED}, preventing a completed or failed write from clobbering a
 * concurrent abort.
 *
 * <p>Usage: pass {@code AbortAwareMutator.wrap(inner)} to {@code SessionStore.saveSnapshot(...)}
 * instead of the bare inner mutator.
 */
public final class AbortAwareMutator {

  private AbortAwareMutator() {
    // utility class
  }

  /**
   * Wraps {@code inner} so that it returns {@code null} when the existing snapshot has status
   * {@link SnapshotStatus#ABORTED}; otherwise delegates to {@code inner}.
   *
   * @param <S> the type of custom session state
   * @param inner the underlying mutator to wrap
   * @return a new mutator that is abort-aware
   */
  public static <S> SnapshotMutator<S> wrap(SnapshotMutator<S> inner) {
    return existing -> {
      if (existing != null && SnapshotStatus.ABORTED.equals(existing.getStatus())) {
        // Existing row is ABORTED — decline the write so we don't clobber it
        return null;
      }
      return inner.apply(existing);
    };
  }
}
