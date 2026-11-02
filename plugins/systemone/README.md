# Genkit System One Plugin

System One decision models for [Genkit Java](https://genkit.dev). Preview: the
API may change in any minor release.

A decision model does not generate text. It evaluates a state against typed
questions and returns one typed answer per question, with calibrated
probabilities. The questions are the fields of the output class, declared with
`Choice`, `Score`, `Noul`, and `NoulOf`.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-systemone</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
enum Dept implements SystemOne.Option {
  @JsonProperty("billing") BILLING("Payments, invoicing, refunds"),
  @JsonProperty("technical") TECHNICAL("Bugs, outages, integrations"),
  @JsonProperty("other") OTHER("None of the above");

  private final String criteria;
  Dept(String criteria) { this.criteria = criteria; }
  @Override public String criteria() { return criteria; }
}

record Triage(
    @JsonPropertyDescription("Which team should handle this?") Choice<Dept> department,
    @JsonPropertyDescription("Does the ticket explicitly communicate time pressure?") Noul isUrgent) {}

Genkit genkit = Genkit.builder().plugin(SystemOne.typeSafe()).build(); // TYPESAFE_API_KEY

Triage out = genkit.generateObject("typesafe/jev-1.13.0", ticket, Triage.class);
if (out.department().confidence() < 0.6) {
  // route to a human
}
```

## Servers

| Factory | Models | Key |
| --- | --- | --- |
| `SystemOne.typeSafe()` | `typesafe/<id>` | `TYPESAFE_API_KEY` |
| `SystemOne.openRouter()` | `openrouter-decisions/<id>` | `OPENROUTER_API_KEY` |
| `SystemOne.ollama()` | `ollama-decisions/<name>` | none |
| `SystemOne.workersAI(accountId)` | `cloudflare-decisions/<id>` | `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` |

Any other server that speaks System One takes `new SystemOne().provider(...).baseUrl(...)`.

See the [plugin documentation](https://genkit-ai.github.io/genkit-java/plugins/systemone/)
for the question types, runtime questions, the state, media, and every server
setting, and the [sample](../../samples/systemone) for complete flows.

## Tests

`mvn test` runs against a fake endpoint. With `OPENROUTER_API_KEY` set, the
OpenRouter live tests run against jev and d1; with `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` set, the Workers AI live tests run against Clef,
Clef-flash, and Clef-omni.
