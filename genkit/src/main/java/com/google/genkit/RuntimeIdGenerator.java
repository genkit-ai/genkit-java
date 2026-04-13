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

package com.google.genkit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates globally unique runtime IDs for Genkit instances.
 *
 * <p>The ID format is {@code $prefix$suffix} where:
 *
 * <ul>
 *   <li>Prefix: instance name if provided, otherwise the PID
 *   <li>Suffix: empty for the first instance, {@code _N} for subsequent instances
 * </ul>
 *
 * <p>This ensures uniqueness even when multiple Genkit instances exist in the same process.
 */
class RuntimeIdGenerator {

  /** Global counter for Genkit instances, shared across all reflection servers. */
  private static final AtomicInteger instanceCounter = new AtomicInteger(0);

  private RuntimeIdGenerator() {}

  /**
   * Generates a globally unique runtime ID.
   *
   * @param instanceName optional instance name (may be null); if absent, the PID is used as prefix
   * @return a unique runtime ID
   */
  static String generate(String instanceName) {
    String prefix =
        (instanceName != null && !instanceName.isEmpty())
            ? instanceName
            : String.valueOf(ProcessHandle.current().pid());
    int count = instanceCounter.incrementAndGet();
    String suffix = (count == 1) ? "" : "_" + count;
    return prefix + suffix;
  }
}
