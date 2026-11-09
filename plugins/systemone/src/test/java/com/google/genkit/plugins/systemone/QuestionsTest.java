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

import static com.google.genkit.plugins.systemone.TestTypes.TRIAGE_ANSWERS;
import static com.google.genkit.plugins.systemone.TestTypes.triageQuestions;
import static com.google.genkit.plugins.systemone.TestTypes.triageSchema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import com.google.genkit.core.SchemaUtils;
import com.google.genkit.plugins.systemone.TestTypes.Anger;
import com.google.genkit.plugins.systemone.TestTypes.Dept;
import com.google.genkit.plugins.systemone.TestTypes.GuidedTriage;
import com.google.genkit.plugins.systemone.TestTypes.Triage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class QuestionsTest {

  private static final ObjectMapper MAPPER = JsonUtils.getObjectMapper();

  @SuppressWarnings("unchecked")
  private static Map<String, Object> property(Map<String, Object> schema, String name) {
    Object prop = Wire.map(schema.get("properties")).get(name);
    assertTrue(prop instanceof Map, "schema has no property " + name + ": " + schema);
    return (Map<String, Object>) prop;
  }

  private static Map<String, Map<String, Object>> answers(String json) throws Exception {
    return MAPPER.readValue(
        json, new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
  }

  private static Object parse(String json) throws Exception {
    return MAPPER.readValue(json, Object.class);
  }

  @Test
  void schemaEncodesQuestions() {
    Map<String, Object> schema = triageSchema();

    Map<String, Object> department = property(schema, "department");
    assertEquals(Wire.KIND_CHOICE, department.get(Wire.KIND_KEYWORD));
    assertEquals(
        "Which team should handle this?",
        department.get("description"),
        "the field description was not merged onto the type's schema");
    assertEquals(false, department.get("additionalProperties"));
    List<?> oneOf = (List<?>) property(department, "choice").get("oneOf");
    List<String> consts = new java.util.ArrayList<>();
    for (Object option : oneOf) {
      Map<String, Object> o = Wire.map(option);
      consts.add((String) o.get("const"));
      assertNotEquals("", o.get("description"), "option has no description");
    }
    assertEquals(List.of("billing", "other", "technical"), consts, "options sorted by name");

    Map<String, Object> urgent = property(schema, "is_urgent");
    assertEquals(Wire.KIND_NOUL, urgent.get(Wire.KIND_KEYWORD));
    assertEquals("Explicitly time-sensitive", urgent.get(Wire.TRUE_KEYWORD));
    assertEquals("No urgency expressed", urgent.get(Wire.FALSE_KEYWORD));
    assertEquals("number", property(urgent, "noul").get("type"));

    Map<String, Object> frustration = property(schema, "frustration");
    assertEquals(Wire.KIND_SCORE, frustration.get(Wire.KIND_KEYWORD));
    assertEquals(
        "[\"Calm\",\"Concerned but civil\",\"Very angry\"]",
        Wire.json(frustration.get(Wire.LEVELS_KEYWORD)));
    assertEquals(2, ((Number) property(frustration, "score").get("maximum")).intValue());

    assertEquals(
        new TreeSet<>(List.of("department", "frustration", "is_urgent")),
        new TreeSet<>((List<?>) schema.get("required")));
  }

  @Test
  void compileQuestions() {
    Map<String, WireQuestion> got = Questions.compile(triageSchema(), "");
    assertEquals(triageQuestions(), got);
    assertEquals(Wire.json(triageQuestions()), Wire.json(got));
  }

  @Test
  void preambleLeadsEveryQuestion() {
    Map<String, WireQuestion> got =
        Questions.compile(triageSchema(), "The state is a support ticket.");
    got.forEach((id, q) ->
        assertEquals(
            "The state is a support ticket.\n\n" + triageQuestions().get(id).instructions,
            q.instructions));

    // The schema's own description follows the system text.
    Map<String, Object> schema = new LinkedHashMap<>(triageSchema());
    schema.put("description", "Triage a ticket for the support queue.");
    got = Questions.compile(schema, "The state is a support ticket.");
    got.forEach((id, q) ->
        assertEquals(
            "The state is a support ticket.\n\nTriage a ticket for the support queue.\n\n"
                + triageQuestions().get(id).instructions,
            q.instructions));
  }

  @Test
  void answersFillTheType() throws Exception {
    String text = Questions.answersText(answers(TRIAGE_ANSWERS), triageQuestions(), false);

    // The text must validate against the type's own schema, extension keywords included.
    assertEquals(List.of(), SchemaValidator.validate(parse(text), triageSchema()), text);

    Triage out = MAPPER.readValue(text, Triage.class);
    assertEquals(Dept.BILLING, out.department().choice());
    assertEquals(0.6, out.department().confidence());
    assertEquals(0.84, out.department().probabilities().get(Dept.BILLING));
    assertEquals(0.93, out.isUrgent().probability());
    assertEquals(1.3, out.frustration().score());
    assertEquals("Concerned but civil", out.frustration().legend().get("1"));
    assertEquals(0.3, out.frustration().probabilities().get("2"));
    assertFalse(text.contains("\"type\""), "the type discriminator leaked: " + text);
  }

  static final class NoCriteriaPair implements SystemOne.YesNo {
    @Override
    public String yes() {
      return "";
    }

    @Override
    public String no() {
      return "";
    }
  }

  static final class HalfCriteria implements SystemOne.YesNo {
    @Override
    public String yes() {
      return "yes means this";
    }

    @Override
    public String no() {
      return "";
    }
  }

  record Handoff(
      @JsonProperty("wants_human") @JsonPropertyDescription("Does the user ask for a person?")
          Noul wantsHuman,
      @JsonProperty("empty")
          @JsonPropertyDescription(
              "Is the sky blue? The type gives an empty pair, so this is a plain noul.")
          NoulOf<NoCriteriaPair> empty) {}

  @Test
  void noulWithoutCriteriaOmitsTheField() {
    Map<String, WireQuestion> questions =
        Questions.compile(SchemaUtils.inferSchema(Handoff.class), "");
    for (String id : List.of("wants_human", "empty")) {
      String wire = Wire.json(questions.get(id));
      assertFalse(wire.contains("criteria"), id + ": " + wire);
    }
  }

  record HalfDecision(
      @JsonProperty("half") @JsonPropertyDescription("d") NoulOf<HalfCriteria> half) {}

  @Test
  void noulOfRejectsHalfCriteria() {
    GenkitException e =
        assertThrows(
            GenkitException.class,
            () -> Questions.compile(SchemaUtils.inferSchema(HalfDecision.class), ""));
    assertTrue(e.getMessage().contains("only one side"), e.getMessage());
  }

  @Test
  void noulOfFillsFromThePlainAnswer() throws Exception {
    NoulOf<TestTypes.Urgent> out =
        MAPPER.readValue("{\"noul\": 0.93}", new TypeReference<NoulOf<TestTypes.Urgent>>() {});
    assertEquals(0.93, out.probability());
    assertEquals("{\"noul\":0.93}", MAPPER.writeValueAsString(out));
    // A helper that takes the plain Noul takes any of them by conversion.
    assertEquals(0.93, out.toNoul().probability());
  }

  @Test
  void scoreLevelAndLabel() {
    Object[][] cases = {
      {1.3, 1, "Concerned but civil"},
      {1.5, 2, "Very angry"},
      {2.7, 2, "Very angry"},
      {-0.4, 0, "Calm"},
    };
    for (Object[] c : cases) {
      Score<Anger> s = new Score<>(Anger.class, (double) c[0]);
      assertEquals(c[1], s.levelIndex(), "score " + c[0]);
      assertEquals(c[2], s.label(), "score " + c[0]);
      assertEquals(Anger.values()[(int) c[1]], s.level());
    }
  }

  @Test
  void choiceRankedAndMargin() {
    Map<Dept, Double> p = new HashMap<>();
    p.put(Dept.BILLING, 0.75);
    p.put(Dept.TECHNICAL, 0.25);
    p.put(Dept.OTHER, 0.0);
    Choice<Dept> c = new Choice<>(Dept.BILLING, p, 0);
    assertEquals(List.of(Dept.BILLING, Dept.TECHNICAL, Dept.OTHER), c.ranked());
    assertEquals(0.5, c.margin());

    Choice<Dept> tied = new Choice<>(null, Map.of(Dept.TECHNICAL, 0.5, Dept.BILLING, 0.5), 0);
    assertEquals(List.of(Dept.BILLING, Dept.TECHNICAL), tied.ranked(), "ties by name");
    assertEquals(0, tied.margin());

    Choice<Dept> empty = new Choice<>();
    assertTrue(empty.ranked().isEmpty());
    assertEquals(0, empty.margin());
  }

  @Test
  void guidanceOnTheWire() {
    Map<String, WireQuestion> questions =
        Questions.compile(SchemaUtils.inferSchema(GuidedTriage.class), "");
    // An object without a what gets the string as its what; one with a what keeps it; a null
    // value and an absent key keep the string.
    Map<String, String> want = new TreeMap<>();
    want.put(
        "department",
        "{\"billing\":{\"examples\":[\"I was charged twice.\"],\"not_for\":\"Where an order"
            + " is\",\"what\":\"Payments, invoicing, refunds\"},"
            + "\"other\":\"None of the above\","
            + "\"technical\":{\"examples\":[\"The app crashes.\"],\"what\":\"Anything that is"
            + " broken\"}}");
    want.put(
        "frustration",
        "[\"Calm\",\"Concerned but civil\",{\"signals\":[\"threats\",\"all"
            + " caps\"],\"what\":\"Very angry\"}]");
    want.put(
        "is_urgent",
        "{\"false\":\"No urgency expressed\",\"true\":{\"examples\":[\"today\",\"by"
            + " Friday\"],\"what\":\"Explicitly time-sensitive\"}}");
    want.forEach((id, criteria) -> assertEquals(criteria, Wire.json(questions.get(id).criteria)));

    // The schema a model reads keeps the strings as the descriptions.
    Map<String, Object> schema = SchemaUtils.inferSchema(GuidedTriage.class);
    for (Object raw : (List<?>) property(property(schema, "department"), "choice").get("oneOf")) {
      Map<String, Object> option = Wire.map(raw);
      if ("billing".equals(option.get("const"))) {
        assertEquals("Payments, invoicing, refunds", option.get("description"));
      }
    }
  }

  @Test
  void legendKeepsTheRubricStrings() throws Exception {
    Map<String, WireQuestion> questions =
        Questions.compile(SchemaUtils.inferSchema(GuidedTriage.class), "");
    Map<String, Map<String, Object>> answers =
        answers(
            "{\"department\": {\"type\": \"choice\", \"choice\": \"billing\", \"probabilities\":"
                + " {\"billing\": 1.0}, \"confidence\": 1.0},"
                + "\"is_urgent\": {\"type\": \"noul\", \"noul\": 0.9},"
                + "\"frustration\": {\"type\": \"score\", \"score\": 1.8, \"probabilities\": {\"1\":"
                + " 0.2, \"2\": 0.8}, \"confidence\": 0.7, \"legend\": {\"0\": \"Calm\", \"1\":"
                + " \"Concerned but civil\", \"2\": {\"what\": \"Very angry\", \"signals\":"
                + " [\"threats\"]}}}}");
    String text = Questions.answersText(answers, questions, false);
    assertEquals(
        List.of(),
        SchemaValidator.validate(parse(text), SchemaUtils.inferSchema(GuidedTriage.class)),
        text);
    GuidedTriage out = MAPPER.readValue(text, GuidedTriage.class);
    assertEquals("Very angry", out.frustration().legend().get("2"));
    assertEquals("Very angry", out.frustration().label());
  }

  @Test
  void scoreClampedToTheRubric() throws Exception {
    Map<String, Object> schema = triageSchema();
    Map<String, WireQuestion> questions = Questions.compile(schema, "");
    for (String score : List.of("2.0000000000000004", "-1e-17")) {
      String json = TRIAGE_ANSWERS.replace("\"score\": 1.3", "\"score\": " + score);
      String text = Questions.answersText(answers(json), questions, false);
      assertEquals(List.of(), SchemaValidator.validate(parse(text), schema), "score " + score);
    }
  }

  @Test
  void runtimeQuestions() throws Exception {
    Map<String, Question> set = new HashMap<>();
    set.put(
        "tool",
        new ChoiceQuestion(
            "Which tool serves the request?",
            List.of(
                ChoiceOption.of("search", "Look something up on the web"),
                new ChoiceOption(
                    "calendar",
                    "Read or change the user's calendar",
                    Map.of("not_for", "Reminders")),
                ChoiceOption.of("none"))));
    set.put(
        "effort",
        new ScoreQuestion(
            Map.of("field", Map.of("name", "request", "description", "What the user asked for")),
            List.of("Trivial", "Some work", "A project"),
            Map.of(2, Map.of("signals", List.of("several steps")))));
    set.put(
        "personal",
        NoulQuestion.of(
            "Does the request involve the user's own data?",
            "Names the user's files, mail, or calendar",
            "Asks about the world at large"));
    Map<String, Object> schema = SystemOne.schema(set);

    Map<String, WireQuestion> questions = Questions.compile(schema, "");
    Map<String, String> want = new TreeMap<>();
    // The options keep the order given, not the sorted one.
    want.put(
        "tool",
        "{\"type\":\"choice\",\"instructions\":\"Which tool serves the request?\","
            + "\"criteria\":{\"search\":\"Look something up on the web\",\"calendar\":{\"not_for\":"
            + "\"Reminders\",\"what\":\"Read or change the user's calendar\"},\"none\":\"none\"}}");
    want.put(
        "effort",
        "{\"type\":\"score\",\"instructions\":{\"field\":{\"description\":\"What the user asked"
            + " for\",\"name\":\"request\"}},"
            + "\"criteria\":[\"Trivial\",\"Some work\",{\"signals\":[\"several steps\"],\"what\":\"A"
            + " project\"}]}");
    want.put(
        "personal",
        "{\"type\":\"noul\",\"instructions\":\"Does the request involve the user's own data?\","
            + "\"criteria\":{\"false\":\"Asks about the world at large\",\"true\":\"Names the"
            + " user's files, mail, or calendar\"}}");
    final Map<String, WireQuestion> compiled = questions;
    want.forEach((id, wire) -> assertEquals(wire, Wire.json(compiled.get(id)), id));

    // A preamble goes beside structured instructions, and in front of text.
    questions = Questions.compile(schema, "The state is a user request.");
    assertEquals(
        "[\"The state is a user request.\",{\"field\":{\"description\":\"What the user asked"
            + " for\",\"name\":\"request\"}}]",
        Wire.json(questions.get("effort").instructions));
    assertEquals(
        "The state is a user request.\n\nWhich tool serves the request?",
        questions.get("tool").instructions);

    // An array takes the preamble as its first element rather than nested. A raw schema map can
    // carry the array as a list of strings or as a JSON tree, and those spread the same way;
    // bytes encode as a string and stay nested.
    Map<String, Question> rawSet = new HashMap<>();
    rawSet.put(
        "q", NoulQuestion.of(List.of("Is the request urgent?", Map.of("field", "deadline"))));
    rawSet.put("steps", NoulQuestion.of("placeholder"));
    rawSet.put("json", NoulQuestion.of("placeholder"));
    rawSet.put("bytes", NoulQuestion.of("placeholder"));
    Map<String, Object> raw = SystemOne.schema(rawSet);
    Map<String, Object> props = Wire.map(raw.get("properties"));
    Wire.map(props.get("steps"))
        .put(Wire.INSTRUCTIONS_KEYWORD, List.of("Read the request.", "Is it urgent?"));
    Wire.map(props.get("json"))
        .put(
            Wire.INSTRUCTIONS_KEYWORD,
            Wire.tree("[\"Read the request.\",{\"field\":\"deadline\"}]"));
    Wire.map(props.get("bytes"))
        .put(
            Wire.INSTRUCTIONS_KEYWORD,
            "Is it urgent?".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Map<String, WireQuestion> listed = Questions.compile(raw, "The state is a user request.");
    Map<String, String> wantListed = new TreeMap<>();
    wantListed.put(
        "q",
        "[\"The state is a user request.\",\"Is the request urgent?\",{\"field\":\"deadline\"}]");
    wantListed.put(
        "steps", "[\"The state is a user request.\",\"Read the request.\",\"Is it urgent?\"]");
    wantListed.put(
        "json",
        "[\"The state is a user request.\",\"Read the request.\",{\"field\":\"deadline\"}]");
    wantListed.put("bytes", "[\"The state is a user request.\",\"SXMgaXQgdXJnZW50Pw==\"]");
    wantListed.forEach((id, w) -> assertEquals(w, Wire.json(listed.get(id).instructions), id));

    // The answers fill a map of Answer.
    Map<String, Map<String, Object>> answers =
        answers(
            "{\"tool\":{\"type\":\"choice\",\"choice\":\"calendar\",\"probabilities\":{\"search\":0.1,"
                + "\"calendar\":0.85,\"none\":0.05},\"confidence\":0.7},"
                + "\"effort\":{\"type\":\"score\",\"score\":0.4,\"probabilities\":{\"0\":0.6,\"1\":0.4,"
                + "\"2\":0},\"confidence\":0.3,\"legend\":{\"0\":\"Trivial\",\"1\":\"Some"
                + " work\",\"2\":{\"what\":\"A project\"}}},"
                + "\"personal\":{\"type\":\"noul\",\"noul\":0.91}}");
    String text = Questions.answersText(answers, questions, false);
    assertEquals(List.of(), SchemaValidator.validate(parse(text), schema), text);
    Map<String, Answer> parsed =
        SystemOne.answers(
            ModelResponse.builder()
                .addCandidate(
                    new com.google.genkit.ai.Candidate(com.google.genkit.ai.Message.model(text)))
                .build());
    assertEquals("calendar", parsed.get("tool").choice());
    assertEquals(0.85, parsed.get("tool").probabilities().get("calendar"));
    assertEquals(0.7, parsed.get("tool").confidence());
    assertEquals(0.4, parsed.get("effort").score());
    assertEquals("A project", parsed.get("effort").legend().get("2"));
    assertEquals(0.91, parsed.get("personal").probability());
  }

  @Test
  void runtimeQuestionsRejects() {
    Map<String, Question> nullQuestion = new HashMap<>();
    nullQuestion.put("q", null);
    Object[][] cases = {
      {"nil question", nullQuestion, "not a question"},
      {"no instructions", Map.of("q", NoulQuestion.of(null)), "no instructions"},
      {"no options", Map.of("q", new ChoiceQuestion("d", List.of())), "no options"},
      {
        "option twice",
        Map.of("q", ChoiceQuestion.of("d", ChoiceOption.of("a"), ChoiceOption.of("a"))),
        "twice"
      },
      {"one level", Map.of("q", ScoreQuestion.of("d", "only")), "two levels"},
      {"half criteria", Map.of("q", NoulQuestion.of("d", "y", null)), "only one side"},
    };
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      Map<String, Question> questions = (Map<String, Question>) c[1];
      GenkitException e =
          assertThrows(
              GenkitException.class,
              () -> Questions.compile(SystemOne.schema(questions), ""),
              (String) c[0]);
      assertTrue(e.getMessage().contains((String) c[2]), c[0] + ": " + e.getMessage());
    }
  }

  @Test
  void answerRoundTripKeepsZeros() throws Exception {
    // A zero is a real reading: a noul of 0, a score clamped to 0, the confidence of a flat
    // distribution. Re-encoding an answer must keep it, and must not add another kind's fields.
    Map<String, Answer> answers =
        MAPPER.readValue(
            "{\"flat\":  {\"choice\": \"a\", \"probabilities\": {\"a\": 0.5, \"b\": 0.5},"
                + " \"confidence\": 0},"
                + "\"low\":   {\"score\": 0, \"probabilities\": {\"0\": 1}, \"confidence\": 1,"
                + " \"legend\": {\"0\": \"None\"}},"
                + "\"never\": {\"noul\": 0}}",
            new TypeReference<TreeMap<String, Answer>>() {});
    assertEquals(Wire.KIND_CHOICE, answers.get("flat").type());
    assertEquals(Wire.KIND_SCORE, answers.get("low").type());
    assertEquals(Wire.KIND_NOUL, answers.get("never").type());
    String want =
        "{\"flat\":{\"choice\":\"a\",\"confidence\":0,\"probabilities\":{\"a\":0.5,\"b\":0.5},\"type\":\"choice\"},"
            + "\"low\":{\"confidence\":1,\"legend\":{\"0\":\"None\"},\"probabilities\":{\"0\":1},\"score\":0,\"type\":\"score\"},"
            + "\"never\":{\"noul\":0,\"type\":\"noul\"}}";
    assertEquals(want, MAPPER.writeValueAsString(answers));
  }

  @Test
  void responseInfoOfAResponseWithoutOne() {
    assertEquals(Info.EMPTY, SystemOne.responseInfo(new ModelResponse()));
    assertEquals(Info.EMPTY, SystemOne.responseInfo(null));
    // Another model's raw response that does not read as an info is the empty info.
    ModelResponse other = new ModelResponse();
    other.setCustom(Map.of("answers", "another model's"));
    assertEquals(Info.EMPTY, SystemOne.responseInfo(other));
  }

  // Ported from the protocol package's own tests.

  @Test
  void compileQuestionsRejects() {
    java.util.function.Function<Map<String, Object>, Map<String, Object>> prop =
        fields -> Map.of("type", "object", "properties", Map.of("q", fields));
    Object[][] cases = {
      {"no properties", Map.of("type", "string"), "no properties"},
      {"plain field", prop.apply(Map.of("type", "string", "description", "d")), "not a question"},
      {"no instructions", prop.apply(Map.of(Wire.KIND_KEYWORD, Wire.KIND_NOUL)), "no instructions"},
      {
        "unknown kind",
        prop.apply(Map.of(Wire.KIND_KEYWORD, "vibe", "description", "d")),
        "unknown type"
      },
      {
        "choice without options",
        prop.apply(Map.of(Wire.KIND_KEYWORD, Wire.KIND_CHOICE, "description", "d")),
        "no options"
      },
      {
        "one level",
        prop.apply(
            Map.of(
                Wire.KIND_KEYWORD,
                Wire.KIND_SCORE,
                "description",
                "d",
                Wire.LEVELS_KEYWORD,
                List.of("only"))),
        "at least two levels"
      },
      {
        "half a noul",
        prop.apply(
            Map.of(
                Wire.KIND_KEYWORD, Wire.KIND_NOUL, "description", "d", Wire.TRUE_KEYWORD, "yes")),
        "only one side"
      },
      {
        "guidance for no option",
        prop.apply(
            Map.of(
                Wire.KIND_KEYWORD,
                Wire.KIND_CHOICE,
                "description",
                "d",
                "properties",
                Map.of("choice", Map.of("oneOf", List.of(Map.of("const", "a")))),
                Wire.GUIDANCE_KEYWORD,
                Map.of("b", "x"))),
        "not one of its options"
      },
      {
        "guidance for no level",
        prop.apply(
            Map.of(
                Wire.KIND_KEYWORD,
                Wire.KIND_SCORE,
                "description",
                "d",
                Wire.LEVELS_KEYWORD,
                List.of("low", "high"),
                Wire.GUIDANCE_KEYWORD,
                Map.of("2", "x"))),
        "does not have"
      },
      {
        "guidance for one side",
        prop.apply(
            Map.of(
                Wire.KIND_KEYWORD,
                Wire.KIND_NOUL,
                "description",
                "d",
                Wire.GUIDANCE_KEYWORD,
                Map.of("true", Map.of("examples", List.of("today"))))),
        "only one side"
      },
    };
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      Map<String, Object> schema = (Map<String, Object>) c[1];
      GenkitException e =
          assertThrows(GenkitException.class, () -> Questions.compile(schema, ""), (String) c[0]);
      assertTrue(e.getMessage().contains((String) c[2]), c[0] + ": " + e.getMessage());
      assertEquals("INVALID_ARGUMENT", e.getErrorCode());
    }
  }

  /** Options whose guidance is given by a constant's own body. */
  enum Team implements SystemOne.Option {
    @JsonProperty("billing")
    BILLING("Payments") {
      @Override
      public Object guidance() {
        return Map.of("not_for", "Deliveries");
      }
    },
    @JsonProperty("support")
    SUPPORT("Everything else");

    private final String criteria;

    Team(String criteria) {
      this.criteria = criteria;
    }

    @Override
    public String criteria() {
      return criteria;
    }
  }

  @com.fasterxml.jackson.annotation.JsonClassDescription("The state is a support ticket.")
  record Routed(@JsonPropertyDescription("Which team?") Choice<Team> team) {}

  @Test
  void constantBodiesAndTheClassDescription() throws Exception {
    Map<String, Object> schema = SchemaUtils.inferSchema(Routed.class);
    Map<String, WireQuestion> questions = Questions.compile(schema, "Be brief.");
    // The class's own description follows the system text.
    assertEquals(
        "Be brief.\n\nThe state is a support ticket.\n\nWhich team?",
        questions.get("team").instructions);
    assertEquals(
        "{\"billing\":{\"not_for\":\"Deliveries\",\"what\":\"Payments\"},\"support\":\"Everything else\"}",
        Wire.json(questions.get("team").criteria));
    Routed out =
        MAPPER.readValue(
            "{\"team\":{\"choice\":\"billing\",\"probabilities\":{\"billing\":0.9,\"support\":0.1},\"confidence\":0.8}}",
            Routed.class);
    assertEquals(Team.BILLING, out.team().choice());
    assertEquals(0.9, out.team().probabilities().get(Team.BILLING));
    assertEquals(List.of(Team.BILLING, Team.SUPPORT), out.team().ranked());
    assertEquals(
        "{\"choice\":\"billing\",\"probabilities\":{\"billing\":0.9,\"support\":0.1},\"confidence\":0.8}",
        MAPPER.writeValueAsString(out.team()));
  }

  @Test
  void enumQuestion() {
    // The options keep the order the values were given in.
    Map<String, WireQuestion> got =
        Questions.enumQuestion(
            Map.of("enum", List.of("technical", "billing"), "description", "Which team?"), "");
    Map<String, WireQuestion> want =
        Map.of(
            Questions.ENUM_QUESTION_ID,
            new WireQuestion(
                Wire.KIND_CHOICE,
                "Which team?",
                WireQuestion.Options.of(
                    new WireQuestion.Option("technical", "technical"),
                    new WireQuestion.Option("billing", "billing"))));
    assertEquals(want, got);

    got = Questions.enumQuestion(Map.of("enum", List.of("a", "b")), "");
    assertNotEquals("", got.get(Questions.ENUM_QUESTION_ID).instructions);

    // The system text is the question, and a schema description follows it.
    got = Questions.enumQuestion(Map.of("enum", List.of("a", "b")), "Which team?");
    assertEquals("Which team?", got.get(Questions.ENUM_QUESTION_ID).instructions);
    got =
        Questions.enumQuestion(
            Map.of("enum", List.of("a", "b"), "description", "Pick one."), "Context.");
    assertEquals("Context.\n\nPick one.", got.get(Questions.ENUM_QUESTION_ID).instructions);

    assertThrows(GenkitException.class, () -> Questions.enumQuestion(Map.of("type", "string"), ""));

    Map<String, Map<String, Object>> answers =
        Map.of(Questions.ENUM_QUESTION_ID, Map.of("type", "choice", "choice", "billing"));
    assertEquals("billing", Questions.answersText(answers, got, true));
  }

  @Test
  void nullGuidanceKeepsTheDescription() {
    assertEquals("Payments", Questions.withWhat(null, "Payments"));
  }

  @Test
  void answersTextRejectsMissingAnswer() {
    Map<String, Map<String, Object>> answers =
        Map.of("department", Map.of("type", "choice", "choice", "billing"));
    GenkitException e =
        assertThrows(
            GenkitException.class,
            () -> Questions.answersText(answers, ClientTest.testQuestions(), false));
    assertTrue(e.getMessage().contains("\"frustration\""), e.getMessage());
  }

  @Test
  void answersProjectedOntoDeclaredFields() throws Exception {
    // A field the API or a gateway adds to an answer must not reach the message: the answer
    // schemas are closed, so it would fail validation on every call.
    Map<String, Map<String, Object>> answers =
        answers(
            "{\"department\": {\"type\": \"choice\", \"choice\": \"billing\", \"probabilities\":"
                + " {\"billing\": 1.0}, \"confidence\": 1.0, \"explanation\": \"new\"},"
                + "\"is_urgent\": {\"type\": \"noul\", \"noul\": 0.9, \"reasoning\": \"new\"},"
                + "\"frustration\": {\"type\": \"score\", \"score\": 1.0, \"legend\": {\"0\":"
                + " \"Calm\"}, \"probabilities\": {\"0\": 1.0}, \"confidence\": 1.0, \"rank\": 3}}");
    String text = Questions.answersText(answers, ClientTest.testQuestions(), false);
    Map<String, Map<String, Object>> got = answers(text);
    assertEquals(
        List.of("choice", "confidence", "probabilities"),
        List.copyOf(new TreeSet<>(got.get("department").keySet())));
    assertEquals(List.of("noul"), List.copyOf(new TreeSet<>(got.get("is_urgent").keySet())));
    assertEquals(
        List.of("confidence", "legend", "probabilities", "score"),
        List.copyOf(new TreeSet<>(got.get("frustration").keySet())));
  }

  @Test
  void rawChoiceIsRejectedFromTheSchema() {
    assertNull(new QuestionSchemas().jsonSchema(String.class));
  }
}
