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
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A thread-safe {@link InputSource} that is fed by a producer via {@link #offer} and signals
 * end-of-stream via {@link #end}.
 *
 * <p>Internally uses a {@link LinkedBlockingQueue} of {@code Optional<I>} values. The end-of-stream
 * sentinel is {@link Optional#empty()}. Once the sentinel has been consumed, every subsequent call
 * to {@link #next()} returns empty immediately.
 *
 * <p>Thread-safety: safe for one producer thread calling {@link #offer}/{@link #end} and one
 * consumer thread calling {@link #next}.
 *
 * @param <I> the type of each input element
 */
public final class BufferedInputSource<I> implements InputSource<I> {

  // Sentinel value placed in the queue by end() to signal end-of-stream.
  private static final Object END_SENTINEL = new Object();

  private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
  private volatile boolean ended = false;

  /** Creates a new {@code BufferedInputSource}. */
  public BufferedInputSource() {}

  /**
   * Enqueues one input for the consumer. Must not be called after {@link #end()}.
   *
   * @param input the input to enqueue; must not be {@code null}
   */
  public void offer(I input) {
    if (input == null) {
      throw new NullPointerException("input must not be null");
    }
    queue.offer(input);
  }

  /**
   * Signals end-of-stream. After this call, {@link #next()} will return {@link Optional#empty()}
   * once all previously enqueued inputs have been consumed. This method is idempotent.
   */
  public void end() {
    queue.offer(END_SENTINEL);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Blocks until an input is available or end-of-stream is signalled. Once end-of-stream is
   * reached, all subsequent calls return {@link Optional#empty()} without blocking.
   */
  @Override
  @SuppressWarnings("unchecked")
  public Optional<I> next() throws InterruptedException {
    if (ended) {
      return Optional.empty();
    }
    Object item = queue.take();
    if (item == END_SENTINEL) {
      ended = true;
      return Optional.empty();
    }
    return Optional.of((I) item);
  }

  /** No-op; the queue needs no explicit resource release. */
  @Override
  public void close() {
    // Nothing to close; end() is handled by the sentinel pattern.
  }
}
