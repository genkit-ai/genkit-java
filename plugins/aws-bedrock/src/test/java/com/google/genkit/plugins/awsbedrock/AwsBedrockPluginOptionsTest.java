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

package com.google.genkit.plugins.awsbedrock;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AwsBedrockPluginOptionsTest {

  @Test
  void testDefaultBuilder() {
    AwsBedrockPluginOptions options = AwsBedrockPluginOptions.builder().build();

    assertNotNull(options);
  }

  @Test
  void testBuilderWithRegion() {
    AwsBedrockPluginOptions options = AwsBedrockPluginOptions.builder().region("us-west-2").build();

    assertEquals("us-west-2", options.getRegion().toString());
  }

  @Test
  void testBuilderChaining() {
    AwsBedrockPluginOptions options =
        AwsBedrockPluginOptions.builder().region("eu-central-1").build();

    assertNotNull(options);
    assertEquals("eu-central-1", options.getRegion().toString());
  }
}
