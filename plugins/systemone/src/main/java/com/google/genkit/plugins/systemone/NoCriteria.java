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
 * The {@link SystemOne.YesNo} of a plain {@link Noul}: the question is its description alone.
 *
 * <p>Preview: this API may change in any minor release.
 */
public final class NoCriteria implements SystemOne.YesNo {

  /** Creates the empty criteria. */
  public NoCriteria() {}

  @Override
  public String yes() {
    return "";
  }

  @Override
  public String no() {
    return "";
  }
}
