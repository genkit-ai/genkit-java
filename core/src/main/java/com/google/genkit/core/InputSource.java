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

package com.google.genkit.core;

import java.util.Optional;

/**
 * A blocking pull source of inputs for a bidirectional action.
 *
 * <p>The consumer calls {@link #next()} repeatedly to obtain successive inputs. When the stream is
 * exhausted, {@link #next()} returns {@link Optional#empty()}. After that, every subsequent call
 * also returns empty. The {@link #close()} method releases any resources held by this source.
 *
 * @param <I> the type of each input element
 */
public interface InputSource<I> extends AutoCloseable {

  /**
   * Blocks until the next input is available or the stream ends.
   *
   * @return an {@link Optional} containing the next input, or {@link Optional#empty()} when the
   *     stream has ended
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  Optional<I> next() throws InterruptedException;

  /** Releases any resources held by this source. Implementations must be idempotent. */
  @Override
  void close();
}
