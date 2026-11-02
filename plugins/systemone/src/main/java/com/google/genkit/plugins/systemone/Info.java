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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a decision model's response carries beside the answers in the message: the model version
 * that answered, which is what confidence thresholds are tuned against, a gateway's provider and
 * generation ID, and the answers as the API sent them, guidance echoes and fields the answer types
 * do not declare included. {@link SystemOne#responseInfo(com.google.genkit.ai.ModelResponse)} reads
 * it.
 *
 * <p>Preview: this API may change in any minor release.
 *
 * @param model the model version that answered
 * @param provider a gateway's upstream provider, or the empty string
 * @param id a gateway's generation ID, or the empty string
 * @param answers the answers as the API sent them, keyed by question
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Info(
    @JsonProperty("model") String model,
    @JsonProperty("provider") @JsonInclude(JsonInclude.Include.NON_EMPTY) String provider,
    @JsonProperty("id") @JsonInclude(JsonInclude.Include.NON_EMPTY) String id,
    @JsonProperty("answers") @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Map<String, Object>> answers) {

  /** The info of a response from any other model: every field empty. */
  public static final Info EMPTY = new Info("", "", "", Map.of());

  /**
   * Creates the info, with absent fields empty.
   *
   * @param model the model version that answered
   * @param provider a gateway's upstream provider
   * @param id a gateway's generation ID
   * @param answers the answers as the API sent them
   */
  public Info {
    model = model == null ? "" : model;
    provider = provider == null ? "" : provider;
    id = id == null ? "" : id;
    answers =
        answers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
  }
}
