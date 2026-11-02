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

import static com.google.genkit.plugins.systemone.StateBuilderTest.model;
import static com.google.genkit.plugins.systemone.StateBuilderTest.user;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.assertRefused;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.genkit.Genkit;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Part;
import com.google.genkit.core.ActionDesc;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class OllamaTest {

  @Test
  void ollama() {
    try (FakeServer fake = new FakeServer()) {
      SystemOne p = SystemOne.ollama().baseUrl(fake.url());
      Genkit g = Genkit.builder().plugin(p).build();
      String png = "iVBORw0KGgo=";

      // images as raw base64
      decide(
          g,
          "ollama-decisions/clef",
          b ->
              b.messages(
                  List.of(
                      user(
                          Part.text("Is the parcel damaged?"),
                          Part.media("image/png", "data:image/png;base64," + png)))));
      FakeServer.Received req = fake.last();
      assertEquals("/v1/systemone", req.rawPath());
      assertEquals("clef", req.body().get("model"));
      assertEquals(List.of(png), req.body().get("images"), "the base64 without the prefix");

      // a request needs text
      Part image = Part.media("image/png", "data:image/png;base64," + png);
      Object[][] cases = {
        {
          "images alone",
          (Consumer<GenerateOptions.Builder<?>>) b -> b.messages(List.of(user(image))),
          "images needs text"
        },
        {
          "turns of images alone",
          (Consumer<GenerateOptions.Builder<?>>)
              b -> b.messages(List.of(user(image), model(image))),
          "images needs text"
        },
        {"empty prompt", (Consumer<GenerateOptions.Builder<?>>) b -> b.prompt(""), "requires text"},
      };
      for (Object[] c : cases) {
        int calls = fake.calls();
        @SuppressWarnings("unchecked")
        Consumer<GenerateOptions.Builder<?>> opts = (Consumer<GenerateOptions.Builder<?>>) c[1];
        assertRefused(() -> decide(g, "ollama-decisions/clef", opts), (String) c[2]);
        assertEquals(calls, fake.calls(), c[0] + ": the request was sent");
      }

      // lists clef
      List<ActionDesc> descs = p.listActions();
      assertEquals(1, descs.size());
      assertEquals("ollama-decisions/clef", descs.get(0).getName());
    }
  }

  @Test
  void hasText() {
    assertEquals(false, SystemOne.hasText(null));
    assertEquals(false, SystemOne.hasText(""));
    assertEquals(true, SystemOne.hasText("x"));
    assertEquals(true, SystemOne.hasText(java.util.Map.of("ticket", "hi")));
    assertEquals(false, SystemOne.hasText(java.util.Map.of()));
    assertEquals(true, SystemOne.hasText(List.of()));
  }
}
