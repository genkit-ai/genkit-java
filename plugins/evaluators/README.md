# Genkit Evaluators Plugin

The Genkit Evaluators Plugin provides a set of pre-defined evaluators for assessing the quality of your LLM outputs. These evaluators are thin-wrappers around [RAGAS](https://ragas.io/) evaluation metrics, adapted for the Genkit framework.

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-evaluators</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

## Available Evaluators

### LLM-Based Evaluators (Billed)

These evaluators use a judge LLM to assess outputs and may incur API costs:

| Metric | Description | Required Fields |
|--------|-------------|-----------------|
| `FAITHFULNESS` | Measures factual accuracy against provided context | `output`, `context` |
| `ANSWER_RELEVANCY` | Assesses how well the answer pertains to the question | `input`, `output` |
| `ANSWER_ACCURACY` | Compares output against a reference answer | `input`, `output`, `reference` |
| `MALICIOUSNESS` | Detects harmful, misleading, or deceptive content | `input`, `output`, `context` |

### Non-LLM Evaluators (Free)

These evaluators don't use LLM calls:

| Metric | Description | Required Fields |
|--------|-------------|-----------------|
| `REGEX` | Validates output against a regex pattern | `output`, `reference` (pattern) |
| `DEEP_EQUAL` | Checks deep equality between output and reference | `output`, `reference` |
| `JSONATA` | Evaluates output using JSONata expressions | `output`, `reference` (expression) |

## Usage

### Basic Configuration

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.evaluators.EvaluatorsPlugin;
import com.google.genkit.plugins.evaluators.EvaluatorsPluginOptions;
import com.google.genkit.plugins.evaluators.GenkitMetric;

// Create plugin with specific metrics
Genkit genkit = Genkit.builder()
    .addPlugin(EvaluatorsPlugin.create(
        EvaluatorsPluginOptions.builder()
            .judge("googleai/gemini-2.0-flash")
            .metricTypes(List.of(
                GenkitMetric.FAITHFULNESS,
                GenkitMetric.ANSWER_RELEVANCY,
                GenkitMetric.MALICIOUSNESS
            ))
            .build()))
    .build();
```

### Using All Default Evaluators

```java
Genkit genkit = Genkit.builder()
    .addPlugin(EvaluatorsPlugin.create(
        EvaluatorsPluginOptions.builder()
            .judge("googleai/gemini-2.0-flash")
            .useAllMetrics()
            .build()))
    .build();
```

### Custom Configuration per Metric

```java
Genkit genkit = Genkit.builder()
    .addPlugin(EvaluatorsPlugin.create(
        EvaluatorsPluginOptions.builder()
            .judge("googleai/gemini-2.0-flash") // Default judge
            .embedder("googleai/text-embedding-004") // Default embedder
            .metrics(List.of(
                // Use defaults
                MetricConfig.of(GenkitMetric.FAITHFULNESS),
                
                // Custom judge for this metric
                MetricConfig.builder()
                    .metricType(GenkitMetric.ANSWER_RELEVANCY)
                    .judge("openai/gpt-4o")
                    .embedder("openai/text-embedding-3-small")
                    .build(),
                
                // Non-LLM metrics don't need judge configuration
                MetricConfig.of(GenkitMetric.REGEX),
                MetricConfig.of(GenkitMetric.JSONATA)
            ))
            .build()))
    .build();
```

## Evaluator Details

### FAITHFULNESS

Measures the factual accuracy of the generated answer against the given context. Uses a two-step process:

1. **Statement Extraction**: Extracts individual factual statements from the answer
2. **NLI Verification**: Checks each statement against the context using Natural Language Inference

**Score**: Proportion of statements that are supported by the context (0.0 - 1.0)
**Pass Threshold**: > 0.5

### ANSWER_RELEVANCY

Assesses how well the generated answer pertains to the question. Optionally uses embeddings for cosine similarity.

**Score**: Based on:
- Whether the question was answered
- Whether the answer is noncommittal
- Cosine similarity between original question and generated questions (if embedder configured)

### ANSWER_ACCURACY

Compares the output against a reference answer using bidirectional semantic comparison. Uses harmonic mean of:
- Original comparison (output vs reference)
- Inverted comparison (reference vs output)

**Score**: Harmonic mean of both directions (0.0 - 1.0)
**Pass Threshold**: > 0.5

### MALICIOUSNESS

Detects whether the output contains harmful, misleading, or deceptive content.

**Score**: 0.0 (not malicious) or 1.0 (malicious)
**Status**: PASS if not malicious, FAIL if malicious

### REGEX

Validates whether the output matches a regular expression pattern provided in the reference field.

**Reference Format**: Valid Java regex pattern
**Score**: 1.0 if matches, 0.0 if not

### DEEP_EQUAL

Checks if the output is deeply equal to the reference using JSON comparison.

**Score**: 1.0 if equal, 0.0 if not

### JSONATA

Evaluates the output using a [JSONata](https://jsonata.org/) expression provided in the reference.

**Reference Format**: Valid JSONata expression
**Score**: 1.0 if expression returns truthy, 0.0 if falsy

## Running Evaluations

```java
// Create evaluation dataset
List<EvalDataPoint> dataset = List.of(
    EvalDataPoint.builder()
        .testCaseId("test-1")
        .input("What is the capital of France?")
        .output("The capital of France is Paris.")
        .context(List.of("France is a country in Western Europe. Its capital is Paris."))
        .build(),
    EvalDataPoint.builder()
        .testCaseId("test-2")
        .input("What is 2+2?")
        .output("4")
        .reference("4")
        .build()
);

// Run evaluation
EvalRequest request = EvalRequest.builder()
    .dataset(dataset)
    .build();

List<EvalResponse> results = genkit.evaluate("genkitEval/faithfulness", request);

// Process results
for (EvalResponse response : results) {
    System.out.println("Test: " + response.getTestCaseId());
    System.out.println("Score: " + response.getEvaluation().getScore());
    System.out.println("Status: " + response.getEvaluation().getStatus());
}
```

## Requirements

- Java 17 or higher
- A judge model (for LLM-based evaluators)
- Optional: An embedder model (for enhanced ANSWER_RELEVANCY scoring)

## Supported Judge Models

Any model registered with Genkit can be used as a judge:

- `googleai/gemini-2.0-flash`
- `googleai/gemini-1.5-pro`
- `openai/gpt-4o`
- `anthropic/claude-3-sonnet`
- And more...

## License

Apache License 2.0
