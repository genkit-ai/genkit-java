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

import static com.google.genkit.plugins.systemone.StateBuilderTest.user;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.decide;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.output;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.Genkit;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.ai.Part;
import com.google.genkit.plugins.systemone.TestTypes.Dept;
import com.google.genkit.plugins.systemone.TestTypes.Triage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs Cloudflare's own models on the account the token belongs to, with media each model can
 * answer about only by reading it. Skipped unless CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID
 * are set.
 */
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_API_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CLOUDFLARE_ACCOUNT_ID", matches = ".+")
class WorkersAILiveTest {

  private static final String OMNI = "cloudflare-decisions/@cf/cloudflare/clef-omni";
  private static Genkit g;

  @BeforeAll
  static void start() {
    g = Genkit.builder().plugin(SystemOne.workersAI(null)).build();
  }

  /** Asks whether the media is red, which a model can answer only by reading the media. */
  record RedCheck(
      @JsonProperty("red")
          @JsonPropertyDescription("Is the attached image or video mostly the color red?")
          Noul red) {}

  /** Asks whether a recording holds a tone. */
  record ToneCheck(
      @JsonProperty("tone")
          @JsonPropertyDescription("Does the audio clip contain an audible tone or beep?")
          Noul tone) {}

  private static <T> T check(String model, Class<T> type, Part... parts) {
    return g.generate(
        GenerateOptions.builder()
            .model(model)
            .messages(List.of(user(parts)))
            .outputClass(type)
            .build());
  }

  @Test
  void decision() {
    ModelResponse resp =
        decide(
            g,
            "cloudflare-decisions/@cf/cloudflare/clef-flash",
            b ->
                b.prompt(
                    "I was charged twice for one order and I need the duplicate refunded today."));
    Triage out = output(resp, Triage.class);
    assertEquals(Dept.BILLING, out.department().choice(), "billing for a double charge");
    assertEquals("clef-flash", SystemOne.responseInfo(resp).model());
    assertNotNull(resp.getUsage());
    assertTrue(resp.getUsage().getInputTokens() > 0);
  }

  @Test
  void images() throws IOException {
    for (String model :
        List.of("@cf/cloudflare/clef", "@cf/cloudflare/clef-flash", "@cf/cloudflare/clef-omni")) {
      for (Object[] c : new Object[][] {{"red", Color.RED, true}, {"blue", Color.BLUE, false}}) {
        RedCheck out =
            check(
                "cloudflare-decisions/" + model,
                RedCheck.class,
                Part.text("Photo from the customer."),
                Part.media("image/png", solidPng((Color) c[1])));
        assertEquals(
            c[2], out.red().probability() > 0.5, model + " on a " + c[0] + " square: " + out.red());
      }
    }
  }

  @Test
  void audio() {
    for (Object[] c : new Object[][] {{"tone", 880.0, true}, {"silence", 0.0, false}}) {
      ToneCheck out =
          check(
              OMNI,
              ToneCheck.class,
              Part.text("A recording from the device."),
              Part.media("audio/wav", sineWav((double) c[1])));
      assertEquals(c[2], out.tone().probability() > 0.5, c[0] + ": " + out.tone());
    }
  }

  @Test
  void video() throws IOException {
    byte[] clip;
    try (InputStream in = WorkersAILiveTest.class.getResourceAsStream("/testdata/red.mp4")) {
      clip = in.readAllBytes();
    }
    RedCheck out =
        check(
            OMNI,
            RedCheck.class,
            Part.text("A clip from the camera."),
            Part.media(
                "video/mp4", "data:video/mp4;base64," + Base64.getEncoder().encodeToString(clip)));
    assertTrue(out.red().probability() > 0.5, "red on a red clip: " + out.red());
  }

  /** A data URL of a small PNG of one color. */
  static String solidPng(Color color) throws IOException {
    BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    for (int x = 0; x < 64; x++) {
      for (int y = 0; y < 64; y++) {
        img.setRGB(x, y, color.getRGB());
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
  }

  /** A data URL of two seconds of 16 kHz mono WAV: a sine at hz, or silence at 0. */
  static String sineWav(double hz) {
    int rate = 16000;
    int seconds = 2;
    int samples = rate * seconds;
    int size = samples * 2;
    ByteBuffer buf = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
    buf.put("RIFF".getBytes()).putInt(36 + size).put("WAVEfmt ".getBytes());
    buf.putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
    buf.putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(size);
    for (int i = 0; i < samples; i++) {
      buf.putShort((short) (12000 * Math.sin(2 * Math.PI * hz * i / rate)));
    }
    return "data:audio/wav;base64," + Base64.getEncoder().encodeToString(buf.array());
  }
}
