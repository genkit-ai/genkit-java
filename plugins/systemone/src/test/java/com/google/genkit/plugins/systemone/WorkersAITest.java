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
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.assertRefused;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.decide;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.messages;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.output;
import static com.google.genkit.plugins.systemone.SystemOnePluginTest.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genkit.Genkit;
import com.google.genkit.ai.GenerateOptions;
import com.google.genkit.ai.Part;
import com.google.genkit.plugins.systemone.TestTypes.Dept;
import com.google.genkit.plugins.systemone.TestTypes.Triage;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkersAITest {

  private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";

  private FakeServer fake;
  private Genkit g;

  /**
   * Starts a fake endpoint that answers in the Workers AI envelope, and a Genkit with the plugin
   * pointed at it under the path the account's API has.
   */
  @BeforeEach
  void start() {
    fake = new FakeServer();
    fake.wrap =
        reply ->
            Map.of("result", reply, "success", true, "errors", List.of(), "messages", List.of());
    SystemOne p =
        SystemOne.workersAI("acct/1")
            .apiKey("test-token")
            .baseUrl(fake.url() + "/client/v4/accounts/{account}");
    g = Genkit.builder().plugin(p).build();
  }

  @AfterEach
  void stop() {
    fake.close();
  }

  @Test
  void cloudflaresOwnModel() {
    Triage out =
        output(
            decide(
                g,
                "cloudflare-decisions/@cf/cloudflare/clef",
                b ->
                    b.messages(
                        List.of(
                            user(
                                Part.text("Is the parcel damaged?"),
                                Part.media("image/png", PNG))))),
            Triage.class);
    FakeServer.Received req = fake.last();
    // The account is escaped into the path.
    assertEquals("/client/v4/accounts/acct%2F1/ai/run/@cf/cloudflare/clef", req.rawPath());
    assertEquals("clef", req.body().get("model"));
    assertEquals("Is the parcel damaged?", req.body().get("state"));
    assertEquals(List.of(PNG), req.body().get("images"));
    assertEquals(Dept.BILLING, out.department().choice(), "the envelope was not unwrapped");
  }

  @Test
  void clefOmniHearsAndSees() {
    String wav = "data:audio/wav;base64,UklGRg==";
    String mp4 = "data:video/mp4;base64,AAAAIGZ0eXA=";
    Consumer<GenerateOptions.Builder<?>> clip =
        b ->
            b.messages(
                List.of(
                    user(
                        Part.text("Is the fan running?"),
                        Part.media("audio/wav", wav),
                        Part.media("video/mp4", mp4))));
    decide(g, "cloudflare-decisions/@cf/cloudflare/clef-omni", clip);
    Map<String, Object> body = fake.last().body();
    assertEquals("clef-omni", body.get("model"));
    assertEquals(List.of(wav), body.get("audio"));
    assertEquals(List.of(mp4), body.get("videos"));
    // Clef reads images only, so it refuses the clip before sending.
    int calls = fake.calls();
    assertRefused(
        () -> decide(g, "cloudflare-decisions/@cf/cloudflare/clef", clip), "audio/wav media");
    assertEquals(calls, fake.calls());
  }

  @Test
  void partnersModel() {
    decide(g, "cloudflare-decisions/typesafe/jev", b -> b.prompt("hi"));
    FakeServer.Received req = fake.last();
    assertEquals("/client/v4/accounts/acct%2F1/ai/run", req.rawPath());
    assertEquals("typesafe/jev", req.body().get("model"));
    Map<String, Object> input = Wire.map(req.body().get("input"));
    assertEquals("hi", input.get("state"));
    assertNotNull(input.get("questions"));
    assertNull(input.get("model"));
    assertNull(req.body().get("state"));
  }

  @Test
  void textOnlyPartnersModel() {
    assertRefused(
        () ->
            decide(
                g,
                "cloudflare-decisions/typesafe/jev",
                b -> b.messages(List.of(user(Part.text("hi"), Part.media("image/png", PNG))))),
        "media");
  }

  @Test
  void modelIdEscapedIntoThePath() {
    decide(g, "cloudflare-decisions/@cf/x?y", b -> b.prompt("hi"));
    FakeServer.Received req = fake.last();
    assertEquals("/client/v4/accounts/acct%2F1/ai/run/@cf/x%3Fy", req.rawPath());
    assertNull(req.query());
    int calls = fake.calls();
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () -> decide(g, "cloudflare-decisions/@cf/../../tokens", b -> b.prompt("hi")));
    assertEquals("INVALID_ARGUMENT", status(e), messages(e));
    assertEquals(calls, fake.calls(), "a dot segment was sent");
  }

  @Test
  void bareOutput() {
    fake.wrap = null;
    Triage out =
        output(
            decide(g, "cloudflare-decisions/@cf/cloudflare/clef-flash", b -> b.prompt("hi")),
            Triage.class);
    assertEquals(Dept.BILLING, out.department().choice(), "the bare output was not read");
  }

  @Test
  void failedEnvelope() {
    fake.wrap =
        reply ->
            Map.of(
                "success",
                false,
                "errors",
                List.of(Map.of("code", 7000, "message", "No route for that URI")));
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () -> decide(g, "cloudflare-decisions/@cf/cloudflare/clef-flash", b -> b.prompt("hi")));
    assertEquals("UNKNOWN", status(e), messages(e));
    assertTrue(messages(e).contains("cloudflare-decisions: No route"), messages(e));
  }

  @Test
  void requiresAnAccount() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> SystemOne.workersAI("").apiKey("test-token").env(name -> null).init());
    assertTrue(e.getMessage().contains("CLOUDFLARE_ACCOUNT_ID"), e.getMessage());
  }

  @Test
  void behindAProxy() {
    // A base URL with no {account} segment needs no account ID.
    try (FakeServer proxy = new FakeServer()) {
      proxy.wrap = reply -> Map.of("result", reply, "success", true);
      SystemOne p =
          SystemOne.workersAI("")
              .apiKey("test-token")
              .baseUrl(proxy.url() + "/proxy")
              .env(name -> null);
      Genkit pg = Genkit.builder().plugin(p).build();
      decide(pg, "cloudflare-decisions/@cf/cloudflare/clef", b -> b.prompt("hi"));
      assertEquals("/proxy/ai/run/@cf/cloudflare/clef", proxy.last().rawPath());
    }
  }
}
