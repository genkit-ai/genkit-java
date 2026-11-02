---
title: System One (Decisions)
description: Typed decisions with calibrated probabilities from System One decision models such as TypeSafe's jev, Liquid AI's d1, and Cloudflare's Clef.
---

The System One plugin adds decision models to Genkit: models that speak System
One, the protocol TypeSafe AI introduced with jev and that Liquid AI's d1,
Cloudflare's Clef, and the gateways serving them also speak. A decision model
does not generate text. It evaluates a state against typed questions and
returns one typed answer per question, with calibrated probabilities, in a few
hundred milliseconds. That makes it a fit for the decisions inside an
application: routing, classification, scoring, guardrails, and verification.

:::caution[Preview]
This plugin is in preview. Its API may change in any minor release.
:::

One plugin class, `SystemOne`, serves any server that speaks the protocol.
Factory methods set it up for the known ones, and any other server takes a few
settings; see [Servers](#servers).

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-systemone</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## The output type is the question set

A decision model speaks only constrained JSON, which is the subset of the
generate API it fits exactly. The questions are the fields of the output
class, declared with `Choice`, `Score`, and `Noul`. Each field's
`@JsonPropertyDescription` is the question's instructions, and each field's
JSON is the answer, so a decision is one typed generate call and the answer
lands in typed fields.

The question types belong to the protocol, not to a server, so one decision
type serves every model that speaks it, and a fallback from one to another
needs no second type.

```java
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.genkit.plugins.systemone.*;

// The options of a choice belong to their type, with their criteria.
enum Dept implements SystemOne.Option {
  @JsonProperty("billing") BILLING("Payments, invoicing, refunds"),
  @JsonProperty("technical") TECHNICAL("Bugs, outages, integrations"),
  @JsonProperty("other") OTHER("None of the above");

  private final String criteria;
  Dept(String criteria) { this.criteria = criteria; }
  @Override public String criteria() { return criteria; }
}

// The levels of a score belong to their rubric, lowest first.
enum Anger implements SystemOne.Level {
  CALM("Calm"), CONCERNED("Concerned but civil"), VERY_ANGRY("Very angry");

  private final String description;
  Anger(String description) { this.description = description; }
  @Override public String description() { return description; }
}

// The decision: one question per field.
record Triage(
    @JsonPropertyDescription("Which team should handle this?") Choice<Dept> department,
    @JsonProperty("is_urgent")
        @JsonPropertyDescription("Does the ticket explicitly communicate time pressure?")
        Noul isUrgent,
    @JsonPropertyDescription("How frustrated is the customer?") Score<Anger> frustration) {}
```

```java
Genkit genkit = Genkit.builder()
    .plugin(SystemOne.typeSafe()) // TYPESAFE_API_KEY
    .build();

Triage out = genkit.generateObject("typesafe/jev-1.13.0", ticket, Triage.class);
if (out.department().confidence() < 0.6) { // the caller owns thresholds
  return escalate(ticket);
}
switch (out.department().choice()) { // typed
  case BILLING -> { /* ... */ }
  // ...
}
```

That is the whole call: the model, the state, and the type. The questions ride
on the output schema, which the model reads back, so no output format needs
naming.

Everything else is the generate API as it already is: prompt files, model
middleware such as fallback across servers, the trace with the question set on
the request and the distributions on the response, and the token counters.

## Question types

| Field type | Question | Answer accessors |
| --- | --- | --- |
| `Choice<T>` | pick one of the constants of `T` | `choice()`, `probabilities()` per option, `confidence()` |
| `Score<L>` | rate on the constants of `L`, lowest first | `score()` (expected level, fractional), `probabilities()`, `confidence()`, `legend()` |
| `Noul` | is this true? | `probability()`; near 0.5 means "could not tell" |
| `NoulOf<C>` | is this true, where `C` says what yes and no mean | as `Noul`, which is `NoulOf` with no criteria |

A `Choice` also answers `ranked()`, the options from most to least likely, and
`margin()`, the probability gap between the top two, which is how decisive the
choice is. A `Score` answers `level()`, the nearest whole level as its enum
constant, `levelIndex()`, its number, and `label()`, that level's description
from the rubric, with or without a legend on the answer.

An option's name on the wire is the name Jackson gives its enum constant, so
`@JsonProperty("billing")` on a constant makes it `billing`. A choice's options
go out sorted by name.

The criteria of every question belong to a type, so a pair of yes and no
criteria is a type too, and it is reused wherever the question is asked. It
needs a no-argument constructor:

```java
public static final class Urgent implements SystemOne.YesNo {
  @Override public String yes() { return "Names a deadline, or says now or today"; }
  @Override public String no() { return "No time pressure is expressed"; }
}

@JsonPropertyDescription("Does the ticket explicitly communicate time pressure?")
NoulOf<Urgent> isUrgent
```

Criteria and levels are strings, and the schema and the answers show those
strings. The wire format also takes a structured description, most often an
object with labeled parts. A type adds one with `guidance()`, the optional
companion of each interface: `Option.guidance()` per option,
`Level.guidance()` per level, and `YesNo.yesGuidance()` and `noGuidance()` per
side.

```java
enum Dept implements SystemOne.Option {
  @JsonProperty("billing")
  BILLING("Payments, invoicing, refunds") {
    @Override public Object guidance() {
      return Map.of(
          "not_for", "Where an order is, or when it arrives",
          "examples", List.of("I was charged twice for one order."));
    }
  },
  // ...
}
```

An object with no `what` gets the string as its `what`, so guidance adds to the
description. Any other value goes out as it is. A score's legend keeps the
rubric's strings, and the guidance the API echoes back is on
`SystemOne.responseInfo(response).answers()`.

### The enum output format

The built-in `enum` output format also works, with no decision type: the enum
values are the options of one choice question, the system message is the
question, and `response.getText()` is the option.

```java
ModelResponse resp = genkit.generate(GenerateOptions.builder()
    .model("typesafe/jev-1.13.0")
    .system("Which team should handle this ticket?")
    .output(OutputConfig.enums("billing", "technical", "sales"))
    .prompt(ticket)
    .build());
String team = resp.getText();
```

What the enum format does not give is criteria per option or a probability per
option; a `Choice` field in a decision type gives both.

## Questions built at run time

A question whose options come from data, such as the tools on hand, a tenant's
categories, or the nodes of a taxonomy, has no type to declare it with.
`SystemOne.schema` builds the output schema from values instead, and
`SystemOne.answers` reads the answers back as a map of `Answer`:

```java
List<ChoiceOption> options = tools.stream()
    .map(tool -> ChoiceOption.of(tool.name(), tool.description()))
    .toList();

ModelResponse resp = genkit.generate(GenerateOptions.builder()
    .model("typesafe/jev-1.13.0")
    .output(OutputConfig.json(SystemOne.schema(Map.of(
        "tool", new ChoiceQuestion("Which tool serves the request?", options),
        "personal", NoulQuestion.of(
            "Does the request involve the user's own data?",
            "Names the user's files, mail, or calendar",
            "Asks about the world at large")))))
    .prompt(request)
    .build());

Answer tool = SystemOne.answers(resp).get("tool");
if (tool.confidence() >= 0.8) {
  return run(tool.choice());
}
```

`ChoiceQuestion`, `ScoreQuestion`, and `NoulQuestion` take what the question
types take from their type arguments: options with criteria and guidance,
levels with guidance by index, and yes and no criteria. A choice's options go
out in the order given. Instructions can be a string or any JSON value, such as
a map that describes the field of the state a question is about; a system
message goes beside structured instructions as the first element of an array.

## State

The state is built from the user and model messages, with no instruction text
mixed in. A system message is never state: it goes in front of every
question's instructions, and for the enum format it is the question. That is
the place for shared context, such as what the state is and what its fields
mean. A description on the output class itself, from `@JsonClassDescription`,
follows the system message in every question's instructions. To judge a
transcript that has its own system prompt, leave that prompt out of the
messages.

- One message is sent as its value: the string of a text part, or the JSON of a
  data part. A user message with a `Part.data(value)` part sends any value as
  an object state, which the vendors recommend so that a question can name a
  field.
- Several messages are sent as an array of `{role, content}` records, roles
  included, so a question can refer to what the user said and what the model
  said.
- With documents attached (`.docs(...)`), the state is `{messages, context}`,
  each document as its content, read as a message's is, or as
  `{content, metadata}` when it has metadata.

A prompt template renders text, so a template that renders JSON needs
`stateJSON` in its config to produce an object state:

```java
.config(Config.builder().stateJson(true).build().toGenerationConfig())
```

`Config.builder().extra(...)` merges fields into the request body that the
plugin does not model, such as a gateway's `session_id` or `trace`; it cannot
replace a field the request builds itself.

Tools are rejected: the model never calls anything.

### Media

Media parts become the request's media, by kind: images go in `images`, audio
in `audio`, and video in `videos`, each in the order its parts appear, messages
first and documents after. The servers place them before the state, and the
text of the same messages stays the state; a message of media alone is a turn
with empty text. A request may carry media and no text, except to Ollama.
Media goes out as a base64 data URL, or as raw base64 to Ollama; a part given
as an `http`, `https`, or `gs` URL is refused rather than fetched, so download
it first and pass it as a data URL. Any other media type is refused.

```java
Damage damage = genkit.generate(GenerateOptions.builder()
    .model("liquid/d1")
    .messages(List.of(new Message(Role.USER, List.of(
        Part.text("Customer's note: arrived like this."),
        Part.media("image/jpeg", photoDataUrl)))))
    .outputClass(Damage.class)
    .build());
```

Media is an extension to the protocol, and a server that does not know a field
can drop it and answer as if nothing had been sent. The plugin therefore
refuses media before the request is sent, kind by kind, unless its `images`,
`audio`, or `video` setting is `MediaSupport.SUPPORTED`. `ollama()` and
`workersAI(accountId)` support images, and so does a server of your own that
takes images, such as Liquid's in [Any other server](#any-other-server). Then a
model is sent that kind, and the server refuses it for a model that cannot
read it, so a new model needs no setup. A model's `ModelSpec` overrides the
plugin's setting, kind by kind, either way: Clef-omni on Workers AI also reads
audio and video. Its default keeps the plugin's, so an entry that only sets a
label changes nothing else. How much media a request takes, and how large, is
the server's to say.

## Prompt files

A prompt file can hold the model, the config, and the template that renders
the state; the decision type is the output class of the call:

```yaml
---
model: typesafe/jev-1.13.0
config:
  stateJSON: true
---
{
  "ticket": {{{ticket}}}
}
```

```java
Triage out = genkit.prompt("triage")
    .generate(Map.of("ticket", JsonUtils.toJson(ticket)), Triage.class);
```

The template places the value as a JSON literal, so the rendered state always
parses.

## Servers

Models are named by the server they are reached through: the plugin's
provider, then the ID the server uses, so a model a server adds later works on
the day it ships.

| Factory | Models | Key | Notes |
| --- | --- | --- | --- |
| `SystemOne.typeSafe()` | `typesafe/jev-1.13.0` | `TYPESAFE_API_KEY` | `TYPESAFE_BASE_URL` is read too; lists models; text only |
| `SystemOne.openRouter()` | `openrouter-decisions/liquid/d1`, `openrouter-decisions/typesafe/jev-1.13`, `openrouter-decisions/~typesafe/jev-latest` | `OPENROUTER_API_KEY` | alpha Decisions API; lists OpenRouter's decision models; cost in `getUsage().getCustom().get("cost")`; text only |
| `SystemOne.ollama()` | `ollama-decisions/clef` | none | `http://localhost:11434` unless `baseUrl` is set; images as raw base64; a request needs text |
| `SystemOne.workersAI(accountId)` | `cloudflare-decisions/@cf/cloudflare/clef`, `cloudflare-decisions/@cf/cloudflare/clef-flash`, `cloudflare-decisions/@cf/cloudflare/clef-omni`, `cloudflare-decisions/typesafe/jev` | `CLOUDFLARE_API_TOKEN` | `CLOUDFLARE_ACCOUNT_ID` when `accountId` is null or empty; Clef models read images, Clef-omni audio and video too; a long state is truncated; no versions to pin |

A gateway serves several vendors' models under one key, so one plugin reaches
all of them. OpenRouter's is named `openrouter-decisions` so that it can sit
beside a plugin for OpenRouter's chat models; Ollama's is `ollama-decisions`
beside the Ollama chat plugin for the same reason.

A factory returns a `SystemOne` set up for its server, and its settings can
still be changed before Genkit is built, such as to pass a key from a secret
manager rather than the environment.

### TypeSafe

TypeSafe's own API serves jev, by version and as `jev-latest`, which follows
each release. Pin a version in production.

```java
Genkit genkit = Genkit.builder()
    .plugin(SystemOne.typeSafe().apiKey(key)) // when not in TYPESAFE_API_KEY
    .build();

Triage out = genkit.generateObject("typesafe/jev-1.13.0", ticket, Triage.class);
```

### OpenRouter

Models go by OpenRouter's IDs, and each response reports what the request
cost. An alias that follows the latest release starts with a tilde, as
`~typesafe/jev-latest` does; `typesafe/jev-latest` is not an ID.

```java
Genkit genkit = Genkit.builder().plugin(SystemOne.openRouter()).build(); // OPENROUTER_API_KEY

ModelResponse resp = genkit.generate(GenerateOptions.builder()
    .model("openrouter-decisions/liquid/d1")
    .output(OutputConfig.fromClass(Triage.class))
    .prompt(ticket)
    .build());
Triage out = JsonUtils.fromJson(resp.getText(), Triage.class);
double cost = resp.getUsage().getCustom().get("cost"); // in OpenRouter credits
```

The API is in alpha, and its models are text only here. It takes images
inside the state, and can route them to a provider that drops them and answers
anyway, so the plugin refuses media before the request is sent.

### Ollama

A local Ollama serves the decision models it has pulled, such as Clef, with no
key, and they read images. The Dev UI lists Clef, and `model(...)` adds the
other models pulled.

```java
Genkit genkit = Genkit.builder()
    .plugin(SystemOne.ollama().baseUrl("http://gpu-box:11434")) // when not on localhost
    .build();

Damage damage = genkit.generate(GenerateOptions.builder()
    .model("ollama-decisions/clef")
    .messages(List.of(new Message(Role.USER, List.of(
        Part.text("Customer's note: arrived like this."), // Ollama needs text beside images
        Part.media("image/jpeg", photoDataUrl)))))
    .outputClass(Damage.class)
    .build());
```

### Cloudflare Workers AI

Workers AI serves Cloudflare's Clef and Clef-flash, which read images,
Clef-omni, which reads images, audio, and video, and partners' models such as
jev, which reads text only. A model is named by its Workers AI ID, `@cf/`
included. The account ID is the argument, or `CLOUDFLARE_ACCOUNT_ID` when it
is null or empty.

```java
Genkit genkit = Genkit.builder()
    .plugin(SystemOne.workersAI(null)) // CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID
    .build();

Triage out = genkit.generateObject(
    "cloudflare-decisions/@cf/cloudflare/clef-flash", ticket, Triage.class);

// Clef-omni hears and sees: audio and video go beside the text.
FanCheck check = genkit.generate(GenerateOptions.builder()
    .model("cloudflare-decisions/@cf/cloudflare/clef-omni")
    .messages(List.of(new Message(Role.USER, List.of(
        Part.text("Clip from the server room."),
        Part.media("audio/wav", fanAudioDataUrl),
        Part.media("video/mp4", fanVideoDataUrl)))))
    .outputClass(FanCheck.class)
    .build());
```

Clef-omni takes up to 4 images, 4 audio clips of up to 5 minutes, and 2 videos
of up to a minute, sampled at 2 frames a second; a video's soundtrack is heard
only when every video in the request has one. Clef-flash takes 24k tokens of
state, media, and questions together, and Clef and Clef-omni 64k.

The base URL has an `{account}` segment, which the account ID fills. A proxy's
base URL with no such segment needs no account ID:

```java
SystemOne cf = SystemOne.workersAI(null)
    .baseUrl("https://ai-proxy.example.com/cloudflare"); // takes /ai/run/<model>
```

Workers AI names one model per release, with no versions, so a threshold tuned
against a model can move when Cloudflare updates it.

### Several servers

Each server is its own plugin, so one app can use several, and the fallback
middleware moves a request to another server when one is down. Each model's
answers are calibrated on their own, so check which model answered before
applying a threshold tuned for one.

```java
SystemOne cf = SystemOne.workersAI(null);
SystemOne or = SystemOne.openRouter();
Genkit genkit = Genkit.builder().plugin(cf).plugin(or).build();

// From the genkit-plugin-middleware module.
FallbackMiddleware.Options fallback = new FallbackMiddleware.Options();
fallback.models = List.of(or.modelName("typesafe/jev-1.13"));

ModelResponse resp = genkit.generate(GenerateOptions.builder()
    .model("cloudflare-decisions/typesafe/jev")
    .use(new FallbackMiddleware(fallback))
    .output(OutputConfig.fromClass(Triage.class))
    .prompt(ticket)
    .build());
double threshold = thresholds.get(SystemOne.responseInfo(resp).model());
```

A fallback keeps the request's config, so a `stateJSON` state works on every
model it falls back to; a data part needs no parsing at all.

### Any other server

Any other server that speaks the protocol takes a few settings. `provider`
names the plugin and prefixes its models, and must differ from every other
plugin's name; `baseUrl` is the root that `path` (default `/v1/systemone`, or
`/` for the base URL itself) and `modelsPath` hang under; `apiKey` is optional
for a server that takes none.

```java
// Liquid AI's d1, from Liquid's own API.
SystemOne liquid = new SystemOne()
    .provider("liquid")
    .baseUrl("https://api.liquid.ai/decisions")
    .modelsPath("/v1/models")
    .apiKey(System.getenv("LIQUID_API_KEY"))
    .images(SystemOne.MediaSupport.SUPPORTED); // d1 reads images

Genkit genkit = Genkit.builder().plugin(liquid).build(); // liquid/d1
```

A proxy that forwards the native protocol, such as LiteLLM, is reached the same
way. A server whose wire is not the native one takes two hooks over the native
body, so it needs no plugin release:

```java
SystemOne wrapped = new SystemOne()
    .provider("wrapped")
    .baseUrl("https://decisions.example.com")
    .path("/run")
    // The request: the native body under input, the model in the path.
    .route((model, body) -> {
      body.remove("model");
      return new SystemOne.Routed("/" + model, Map.of("input", body));
    })
    // The response: the native body out of the server's envelope.
    .unwrap(body -> JsonUtils.parseJson(body).get("result").toString());
```

### Every server

`model(id, spec)` describes the models known ahead, keyed by the server's ID
or by the full model name; they are listed in the Dev UI with whatever the
server's listing adds, which is kept for five minutes. The settings are read
once, when Genkit initializes the plugin. `httpClient` (an OkHttp client) and
`header` are the escape hatches to the transport.

Requests that fail to connect, time out, are rate limited, or hit a server
error are retried twice, with `Retry-After` honored.

Pin a version in production where the server offers one. Confidence thresholds
tuned against one release do not carry over to the next, nor from one model to
another, and `SystemOne.responseInfo(response).model()` is the version that
answered, on every call. A gateway's cost is
`response.getUsage().getCustom().get("cost")`. Where a model name is needed,
such as a fallback list, the plugin's `modelName("jev-1.13.0")` builds the full
name.

## Limits

- No per-item questions. Score a list of passages with one call per passage.
- No nested decision types: a question is a top-level field.
- The instructions of a field in a decision type are a string, since a field's
  description is an annotation. Structured instructions need a runtime
  question.
- Each server sets its own limits on questions and tokens; jev takes up to 64k
  tokens of state and questions together, and up to 32k of state and its
  longest question.
- A server can cut a long state to fit its limit and answer from what is left,
  with no error. Check a long document against the model's limit, or split it.
- No streaming; the answer arrives whole.

## Sample

See the [systemone sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/systemone)
for triage, screening, ranking, routing to answer models, the enum format, and
runtime questions.
