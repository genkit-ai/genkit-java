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

package com.google.genkit.plugins.systemone;

/**
 * One question of a set built at run time, for questions whose options, levels, or instructions
 * come from data: the tools or products on hand, the nodes of a taxonomy, a tenant's own
 * categories. {@link ChoiceQuestion}, {@link ScoreQuestion}, and {@link NoulQuestion} are the
 * kinds, and {@link SystemOne#schema(java.util.Map)} turns a set of them into an output schema. A
 * question known when the code is written reads better as a field of a decision type.
 *
 * <p>Preview: this API may change in any minor release.
 */
public sealed interface Question permits ChoiceQuestion, ScoreQuestion, NoulQuestion {}
