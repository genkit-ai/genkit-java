/*
 * Copyright 2026 Google LLC
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

package com.google.genkit.ai;

/**
 * An error a tool, or a middleware's {@code wrapTool} hook, returns to the model rather than to the
 * caller.
 *
 * <p>When a tool throws this exception, the generate loop answers the tool call with {@code
 * {"error": "<message>"}} (a tool response part whose metadata has {@code isError: true}) and keeps
 * going, so the model can correct its input and try again. Throw any other exception for an error
 * the model cannot act on.
 *
 * <pre>{@code
 * if (!Files.exists(path)) {
 *   throw new ToolFailException("no file named " + name + "; list the directory first");
 * }
 * }</pre>
 *
 * <p>To return every error of a tool you do not own to the model, use the {@code SoftToolErrors}
 * middleware.
 */
public class ToolFailException extends RuntimeException {

  /**
   * Creates a failure whose message the model reads.
   *
   * @param message the message the model reads
   */
  public ToolFailException(String message) {
    super(message);
  }

  /**
   * Creates a failure from an error, whose message the model reads.
   *
   * @param cause the error the model reads
   */
  public ToolFailException(Throwable cause) {
    super(cause != null ? cause.getMessage() : null, cause);
  }

  /**
   * Creates a failure with its own message and cause.
   *
   * @param message the message the model reads
   * @param cause the underlying error
   */
  public ToolFailException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Returns the {@link ToolFailException} in {@code error}'s cause chain, or {@code null} when
   * there is none.
   *
   * @param error the error to inspect
   * @return the failure, or null
   */
  public static ToolFailException find(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof ToolFailException) {
        return (ToolFailException) t;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return null;
  }
}
