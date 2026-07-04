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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** RuntimeError represents a runtime error that occurred during agent execution. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeError {

  @JsonProperty("status")
  private String status;

  @JsonProperty("message")
  private String message;

  @JsonProperty("details")
  private Object details;

  /** Default constructor. */
  public RuntimeError() {}

  private RuntimeError(Builder builder) {
    this.status = builder.status;
    this.message = builder.message;
    this.details = builder.details;
  }

  /**
   * Creates a builder for RuntimeError.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the status code of the error.
   *
   * @return the status
   */
  public String getStatus() {
    return status;
  }

  /**
   * Sets the status code.
   *
   * @param status the status code
   */
  public void setStatus(String status) {
    this.status = status;
  }

  /**
   * Returns the error message.
   *
   * @return the message
   */
  public String getMessage() {
    return message;
  }

  /**
   * Sets the error message.
   *
   * @param message the message
   */
  public void setMessage(String message) {
    this.message = message;
  }

  /**
   * Returns additional error details.
   *
   * @return the details
   */
  public Object getDetails() {
    return details;
  }

  /**
   * Sets additional error details.
   *
   * @param details the details
   */
  public void setDetails(Object details) {
    this.details = details;
  }

  /** Builder for RuntimeError. */
  public static class Builder {
    private String status;
    private String message;
    private Object details;

    public Builder status(String status) {
      this.status = status;
      return this;
    }

    public Builder message(String message) {
      this.message = message;
      return this;
    }

    public Builder details(Object details) {
      this.details = details;
      return this;
    }

    public RuntimeError build() {
      return new RuntimeError(this);
    }
  }
}
