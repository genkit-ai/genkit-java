# Genkit Go 1.14 parity: System One and middleware

## Intent

Bring genkit-java to parity with Genkit Go v1.14.0 for two areas Go has and
Java lacks:

1. The **System One** decision-model plugin (`go/plugins/systemone/exp` and
   `go/plugins/internal/systemone`).
2. The generation middlewares in `go/plugins/middleware` and
   `go/plugins/middleware/exp` that Java does not have yet.

Parity is the goal: Go v1.14.0 is the spec. Behavior, wire format, names,
defaults, error cases, and plugin/middleware registration names match Go. The
API surface is adapted to Java idioms, not transliterated.

The Go source of truth is the module cache copy:
`$(go env GOMODCACHE)/github.com/firebase/genkit/go@v1.14.0`. Its tests are the
behavioral oracle: port them alongside the code.

## Scope

| Area | Go source | Java target |
|---|---|---|
| System One plugin | `plugins/systemone/exp/*`, `plugins/internal/systemone/*` | new module `plugins/systemone` (`genkit-plugin-systemone`) |
| ToolApproval (allowlist, holds, Judge) | `plugins/middleware/tool_approval.go` | `plugins/middleware` |
| SoftToolErrors | `plugins/middleware/soft_tool_errors.go` | `plugins/middleware` |
| Filesystem | `plugins/middleware/filesystem.go` | `plugins/middleware` |
| Skills | `plugins/middleware/skills*.go` | `plugins/middleware` |
| Compression (exp) | `plugins/middleware/exp/compression.go` | `plugins/middleware`, marked experimental |
| Budget (exp) | `plugins/middleware/exp/budget.go` | `plugins/middleware`, marked experimental |

Java already has Fallback, Retry, Agents, Artifacts, and inject-style
delegation. Bring them in line only where a Go 1.14 change to them is needed
by the new pieces (for example, Fallback across System One servers).

### Non-goals

- Anything in Go 1.14 outside the table above.
- Streaming decisions, nested decision types, per-item questions (Go has none).

## Design decisions

### System One typed questions in Java

Go declares questions with generic types whose type parameter supplies the
criteria (`Choice[T]` calls `T.Criteria()`). Java maps this to:

- `Choice<T extends Enum<T> & SystemOne.Option>`: the option set is an enum;
  each constant supplies its criteria and, optionally, guidance. Answer
  accessors: `choice()`, `probabilities()`, `confidence()`, `ranked()`,
  `margin()`.
- `Score<L extends Enum<L> & SystemOne.Level>`: the levels are the enum
  constants, lowest first. Accessors: `score()`, `probabilities()`,
  `confidence()`, `legend()`, `level()`, `label()`.
- `Noul` and `NoulOf<C extends SystemOne.YesNo>`: `C` is a class with a
  no-arg constructor that returns yes/no criteria and optional guidance.
- A field's question instructions come from `@JsonPropertyDescription`.
- Runtime questions: `ChoiceQuestion`, `ScoreQuestion`, `NoulQuestion`,
  `SystemOne.schema(Map<String, Question>)`, and `Answer` for
  `Map<String, Answer>` output.
- `SystemOne.responseInfo(ModelResponse)` mirrors Go's `ResponseInfo`.

The JSON schema keywords (`x-systemone`, `x-levels`, `x-true`, `x-false`,
`x-guidance`, `x-instructions`) and wire JSON are byte-for-byte Go's.

### Schema extension point in core

Go emits the question schema through a type's `JSONSchema()` method. Java
gets a small, general extension point in `core` `SchemaUtils` so a type can
supply its own schema given its resolved generic type. This is the only
change to `core` for System One; it must not alter schemas for existing
types.

### Core gaps the ports may fill

The Go ports rely on a few `ai` features Java may lack. Port the minimum
needed, matching Go:

- the `enum` output format (`OutputFormat.ENUM`, as Go's `WithOutputEnums`),
  used by System One and by the ToolApproval Judge;
- middleware-owned interrupts (Go's `ai.MiddlewareInterrupted` and
  `InterruptedCall.Restart`), used by ToolApproval;
- request media download (Go's `ai.DownloadRequestMedia`) if System One docs
  or tests need it.

### Servers

`SystemOne` is one plugin type with presets `typeSafe()`, `openRouter()`,
`ollama()`, `workersAI(accountId)`, configurable fields matching Go
(`provider`, `baseUrl`, `path`, `modelsPath`, `apiKey`, `headers`,
`httpClient`, `images`/`audio`/`video`, `models`, `route`, `unwrap`), model
listing cached five minutes, and two retries on connect failures, timeouts,
429, and 5xx, honoring `Retry-After`. Environment variables match Go.

### Experimental middlewares

Compression and Budget are experimental in Go. In Java they live in the same
module and are marked experimental in their Javadoc and docs.

## Testing

- Port Go's unit tests for each piece, including a fake System One server
  (Go's `systemonetest`) so no test needs a network or key.
- Live tests (Go's `*_live_test.go`) are ported but skipped unless their
  environment variables are set.
- CI must pass: `mvn -B test`, `mvn -B fmt:check`, `mvn -B checkstyle:check`.

## Docs and samples

- A user docs page `docs/src/content/docs/plugins/systemone.md`, and sections
  in `docs/src/content/docs/middleware.md` for each new middleware. Docs are
  for end users: no internal mechanics or Go citations.
- A `samples/systemone` sample (decisions mirroring Go's
  `samples/basic-decisions`), plus middleware usage in the existing
  middleware sample where it fits.

## Delivery

One PR, built as three parallel workstreams that merge into it:

- **A:** System One plugin plus the core schema extension point and enum
  output format.
- **B:** ToolApproval with Judge, SoftToolErrors, Filesystem, Skills, and
  middleware-owned interrupts.
- **C:** Compression and Budget.
