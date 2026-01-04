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

package com.google.genkit.plugins.googlegenai;

import com.google.genkit.Genkit;

/** Attribution headers for calls made by the Google GenAI plugin. */
public final class GenkitClientHeaders {

  /** Standard Google client attribution header used by many Google APIs. */
  public static final String X_GOOG_API_CLIENT_HEADER = "x-goog-api-client";

  /** Genkit client attribution value, e.g. "genkit-java/1.0.0". */
  public static final String GENKIT_CLIENT_HEADER = "genkit-java/" + resolveGenkitVersion();

  private GenkitClientHeaders() {
  }

  private static String resolveGenkitVersion() {
    String version = Genkit.class.getPackage().getImplementationVersion();
    if (version == null || version.isBlank()) {
      return "dev";
    }
    return version;
  }
}
