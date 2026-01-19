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

package com.google.genkit.plugins.evaluators;

/**
 * Enumeration of supported evaluation metric types.
 *
 * <p>These metrics are thin wrappers around RAGAS evaluators for assessing the quality of LLM
 * outputs.
 *
 * @see <a href="https://docs.ragas.io/en/stable/">RAGAS Documentation</a>
 */
public enum GenkitMetric {

  /**
   * Measures the factual consistency of the generated answer against the given context. This
   * evaluator checks if the statements in the generated output can be inferred from the provided
   * context.
   */
  FAITHFULNESS,

  /**
   * Assesses how pertinent the generated answer is to the given prompt. This evaluator checks if
   * the answer addresses the question asked and uses cosine similarity between embeddings for
   * comparison.
   */
  ANSWER_RELEVANCY,

  /**
   * Measures the accuracy of the generated answer against a reference answer. Uses bidirectional
   * comparison to check semantic equivalence.
   */
  ANSWER_ACCURACY,

  /**
   * Measures whether the generated output intends to deceive, harm, or exploit. Useful for safety
   * evaluations of LLM outputs.
   */
  MALICIOUSNESS,

  /**
   * Tests output against a provided regular expression pattern. Simple pattern-matching evaluation
   * that doesn't require an LLM.
   */
  REGEX,

  /** Tests deep equality between output and reference. Compares JSON structures for exact match. */
  DEEP_EQUAL,

  /** Evaluates output using JSONata expressions. Allows complex JSON path-based assertions. */
  JSONATA;

  /**
   * Returns whether this metric requires an LLM judge for evaluation.
   *
   * @return true if this metric requires an LLM, false otherwise
   */
  public boolean requiresJudge() {
    switch (this) {
      case FAITHFULNESS:
      case ANSWER_RELEVANCY:
      case ANSWER_ACCURACY:
      case MALICIOUSNESS:
        return true;
      case REGEX:
      case DEEP_EQUAL:
      case JSONATA:
        return false;
      default:
        return false;
    }
  }

  /**
   * Returns whether this metric requires an embedder.
   *
   * @return true if this metric requires an embedder, false otherwise
   */
  public boolean requiresEmbedder() {
    return this == ANSWER_RELEVANCY;
  }

  /**
   * Returns whether using this metric may incur API costs.
   *
   * @return true if this metric may be billed, false otherwise
   */
  public boolean isBilled() {
    return requiresJudge() || requiresEmbedder();
  }
}
