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

import com.google.genkit.ai.ModelResponse;
import com.google.genkit.core.GenkitException;

/**
 * Thrown when a {@link BudgetMiddleware} stops a run that reached its usage limit.
 *
 * <p><b>Experimental:</b> this type may change in any minor release.
 *
 * <p>The run was stopped, not broken: its error code is {@code ABORTED}, and an agent turn it stops
 * finishes as aborted. {@link #getResponse()} holds the partial response: the conversation so far,
 * with finish reason {@link com.google.genkit.ai.FinishReason#ABORTED}.
 */
public class BudgetExceededException extends GenkitException {

  /** The error code a budget stop carries. */
  public static final String ERROR_CODE = "ABORTED";

  private final transient ModelResponse response;

  /**
   * Creates the exception.
   *
   * @param message the error message
   * @param response the partial response, or null
   */
  public BudgetExceededException(String message, ModelResponse response) {
    super(message, null, ERROR_CODE, null, null);
    this.response = response;
  }

  /**
   * Returns the partial response of the stopped run: the conversation so far, with finish reason
   * {@link com.google.genkit.ai.FinishReason#ABORTED}.
   *
   * @return the partial response, or null
   */
  public ModelResponse getResponse() {
    return response;
  }
}
