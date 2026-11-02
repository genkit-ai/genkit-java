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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.ai.Document;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import com.google.genkit.core.GenkitException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateBuilderTest {

  private static final StateBuilder.Reads ALL = new StateBuilder.Reads(true, true, true);
  private static final StateBuilder.Reads IMAGES = new StateBuilder.Reads(true, false, false);

  static Message user(Part... parts) {
    return new Message(Role.USER, new ArrayList<>(List.of(parts)));
  }

  static Message model(Part... parts) {
    return new Message(Role.MODEL, new ArrayList<>(List.of(parts)));
  }

  private static StateBuilder.Built build(List<Message> messages, StateBuilder.Reads reads) {
    return StateBuilder.build(messages, null, false, reads);
  }

  @Test
  void stateJsonKeepsNumbersVerbatim() {
    // The JSON goes out as written, so an ID past a double's integer range is not rounded.
    StateBuilder.Built built =
        StateBuilder.build(
            List.of(Message.user("{\"id\": 9007199254740993}")),
            null,
            true,
            StateBuilder.Reads.NONE);
    assertEquals("{\"id\":9007199254740993}", Wire.json(built.state()));
  }

  @Test
  void media() {
    String png = "data:image/png;base64,iVBORw0KGgo=";
    String wav = "data:audio/wav;base64,UklGRg==";
    String mp4 = "data:video/mp4;base64,AAAAIGZ0eXA=";
    List<Message> messages =
        List.of(
            user(
                Part.text("Is this the product?"),
                Part.media("image/png", png),
                Part.media("audio/wav", wav)),
            model(Part.text("Which one?")),
            user(Part.media("", "data:image/jpeg;base64,/9j/4AAQ"), Part.media("video/mp4", mp4)));
    List<Document> docs =
        List.of(
            new Document(
                new ArrayList<>(
                    List.of(Part.media("image/webp", "data:image/webp;base64,UklGRg==")))),
            Document.fromText("The catalog photo."));
    StateBuilder.Built built = StateBuilder.build(messages, docs, false, ALL);
    // Each kind keeps its order, messages first, and leaves the state.
    assertEquals(
        new StateBuilder.Media(
            List.of(png, "data:image/jpeg;base64,/9j/4AAQ", "data:image/webp;base64,UklGRg=="),
            List.of(wav),
            List.of(mp4)),
        built.media());
    assertEquals(
        "{\"context\":[\"The catalog photo.\"],\"messages\":[{\"content\":\"Is this the"
            + " product?\",\"role\":\"user\"},{\"content\":\"Which one?\",\"role\":\"model\"},"
            + "{\"content\":\"\",\"role\":\"user\"}]}",
        Wire.json(built.state()));

    // An image alone is a request with an empty state.
    built = build(List.of(user(Part.media("image/png", png))), IMAGES);
    assertEquals("", built.state());
    assertEquals(1, built.media().images().size());

    // A data URL that is not base64 is encoded; the part's type names it.
    built = build(List.of(user(Part.media("image/svg+xml", "data:,<svg/>"))), IMAGES);
    assertEquals(List.of("data:image/svg+xml;base64,PHN2Zy8+"), built.media().images());

    // A payload that is not base64 is percent-decoded first.
    built = build(List.of(user(Part.media("", "data:image/png,%89PNG%0D%0A%1A%0A"))), IMAGES);
    assertEquals(List.of(png), built.media().images());

    // A message of images alone is still a turn, so another message is not sent as the state
    // with its role lost.
    built =
        build(
            List.of(user(Part.media("image/png", png)), Message.model("Looks like water damage.")),
            IMAGES);
    assertEquals(
        "[{\"content\":\"\",\"role\":\"user\"},{\"content\":\"Looks like water"
            + " damage.\",\"role\":\"model\"}]",
        Wire.json(built.state()));

    // The base64 token is read without regard to case, and the payload goes out as it is.
    built =
        build(List.of(user(Part.media("image/png", "data:image/png;BASE64,iVBORw0KGgo="))), IMAGES);
    assertEquals(List.of(png), built.media().images());

    // The scheme and the media type are read without regard to case.
    built = build(List.of(user(Part.media("", "DATA:Image/PNG;base64,iVBORw0KGgo="))), IMAGES);
    assertEquals(List.of(png), built.media().images());
  }

  @Test
  void noStateRefused() {
    // Documents are context for a state, not the state itself.
    GenkitException e =
        assertThrows(
            GenkitException.class,
            () ->
                StateBuilder.build(
                    List.of(Message.system("Judge the ticket.")),
                    List.of(Document.fromText("The catalog photo.")),
                    false,
                    IMAGES));
    assertTrue(e.getMessage().contains("no state"), e.getMessage());
  }

  @Test
  void mediaRefused() {
    Object[][] cases = {
      {
        "text-only model",
        Part.media("image/png", "data:image/png;base64,iVBORw0KGgo="),
        StateBuilder.Reads.NONE,
        "reads no image/png media"
      },
      {
        "audio to a vision model",
        Part.media("audio/wav", "data:audio/wav;base64,UklGRg=="),
        IMAGES,
        "reads no audio/wav media"
      },
      {
        "video to a vision model",
        Part.media("video/mp4", "data:video/mp4;base64,AAAAIGZ0eXA="),
        IMAGES,
        "reads no video/mp4 media"
      },
      {
        "remote URL",
        Part.media("image/png", "https://example.com/cat.png"),
        IMAGES,
        "inline media only"
      },
      {
        "upper-case remote URL",
        Part.media("image/png", "HTTPS://example.com/cat.png"),
        IMAGES,
        "inline media only"
      },
      {
        "not an image, audio, or video",
        Part.media("application/pdf", "data:application/pdf;base64,JVBERi0="),
        ALL,
        "not an image, audio, or video"
      },
    };
    for (Object[] c : cases) {
      GenkitException e =
          assertThrows(
              GenkitException.class,
              () -> build(List.of(user(Part.text("hi"), (Part) c[1])), (StateBuilder.Reads) c[2]),
              (String) c[0]);
      assertTrue(e.getMessage().contains((String) c[3]), c[0] + ": " + e.getMessage());
      assertEquals("INVALID_ARGUMENT", e.getErrorCode());
    }
  }

  @Test
  void documentsWithMetadataKeepIt() {
    StateBuilder.Built built =
        StateBuilder.build(
            List.of(Message.user("q")),
            List.of(Document.fromText("passage", Map.of("id", "p-1"))),
            false,
            StateBuilder.Reads.NONE);
    assertEquals(
        "{\"context\":[{\"content\":\"passage\",\"metadata\":{\"id\":\"p-1\"}}],"
            + "\"messages\":[{\"content\":\"q\",\"role\":\"user\"}]}",
        Wire.json(built.state()));
  }
}
