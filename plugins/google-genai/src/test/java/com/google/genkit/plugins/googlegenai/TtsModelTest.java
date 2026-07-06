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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link TtsModel} prompt framing. */
class TtsModelTest {

  @Test
  void framePromptAddsPreambleForPlainText() {
    String framed = TtsModel.framePrompt("Have a wonderful day", null);
    assertTrue(framed.contains("Have a wonderful day"), "Should keep the transcript");
    assertTrue(framed.toLowerCase().contains("transcript"), "Should label the transcript");
    assertNotEquals(
        "Have a wonderful day", framed, "Plain text should be framed with a synthesis preamble");
  }

  @Test
  void framePromptRespectsEmptyInstructionOverride() {
    String framed = TtsModel.framePrompt("Say cheerfully: Hi", Map.of("ttsInstruction", ""));
    assertEquals(
        "Say cheerfully: Hi", framed, "Empty ttsInstruction should send the prompt verbatim");
  }

  @Test
  void framePromptUsesCustomInstruction() {
    String framed = TtsModel.framePrompt("Hello", Map.of("ttsInstruction", "Say slowly:"));
    assertEquals("Say slowly:\n\nHello", framed);
  }
}
