# Genkit Evaluators Plugin Sample

This sample application demonstrates how to use the **Genkit Evaluators Plugin** with all 7 metric types available for evaluating AI model outputs.

## Overview

The Evaluators Plugin provides a comprehensive set of evaluation metrics for assessing AI-generated content:

### LLM-Based Metrics (require a judge model)

| Metric | Description |
|--------|-------------|
| **FAITHFULNESS** | Evaluates if the answer is faithful to the provided context |
| **ANSWER_RELEVANCY** | Evaluates if the answer is relevant to the question |
| **ANSWER_ACCURACY** | Evaluates if the answer matches the reference answer |
| **MALICIOUSNESS** | Detects harmful or malicious content in the output |

### Programmatic Metrics (no LLM required)

| Metric | Description |
|--------|-------------|
| **REGEX** | Pattern matching evaluation using regular expressions |
| **DEEP_EQUAL** | JSON deep equality comparison |
| **JSONATA** | JSONata expression evaluation for complex JSON queries |

## Prerequisites

- Java 21 or higher
- Maven 3.x
- OpenAI API key (for LLM-based evaluators)

## Setup

1. **Set your OpenAI API key:**
   ```bash
   export OPENAI_API_KEY=your-api-key-here
   ```

2. **Build the plugin (from project root):**
   ```bash
   cd ../..
   mvn clean install -DskipTests
   ```

## Running the Sample

### Option 1: Using the run script
```bash
./run.sh
```

### Option 2: Using Maven directly
```bash
mvn clean compile exec:java
```

## Usage

Once the application starts, you'll have access to:

- **Dev UI**: http://localhost:3100
- **API**: http://localhost:8080

### Sample Datasets

The application creates three sample datasets automatically:

1. **qa_evaluation** - Q&A pairs for testing LLM-based evaluators
2. **regex_validation** - Pattern matching test cases
3. **json_comparison** - JSON equality test cases

### API Endpoints

#### Answer a Question
```bash
curl -X POST http://localhost:8080/api/flows/answerQuestion \
  -H 'Content-Type: application/json' \
  -d '{
    "question": "What is the capital of France?",
    "context": "France is a country in Europe. Paris is the capital of France."
  }'
```

#### Test Programmatic Evaluators
```bash
curl -X POST http://localhost:8080/api/flows/testEvaluators \
  -H 'Content-Type: application/json' \
  -d '{
    "output": "This is a successful response!",
    "regexPattern": ".*successful.*"
  }'
```

### Running Evaluations via Dev UI

**Important**: Evaluators are not designed to be invoked directly from the "Actions" tab like flows. Instead, use the **Evaluations** tab:

1. Open http://localhost:3100 in your browser
2. Navigate to the **Evaluations** section (not the Actions section)
3. Create or select a dataset (the sample creates `qa_evaluation`, `regex_validation`, `json_comparison`)
4. Select a **target action** (e.g., `/flow/answerQuestion`)
5. Choose evaluators to run (e.g., `genkitEval/faithfulness`, `genkitEval/answer_relevancy`)
6. Click "Run Evaluation" to see results

**Why evaluators show "No input variables" in the Actions tab:**

Evaluators expect an `EvalRequest` with a `dataset` array containing multiple test cases. They're designed for batch evaluation, not individual testing. The proper workflow is:
- Test your **flows** individually in the Actions tab
- Run **evaluations** using the Evaluations tab with datasets

### Running Evaluations via API (curl)

The most reliable way to run evaluations is via the API endpoint:

```bash
# Run faithfulness evaluation on qa_evaluation dataset
curl -X POST http://localhost:3100/api/runEvaluation \
  -H 'Content-Type: application/json' \
  -d '{
    "dataSource": {"datasetId": "qa_evaluation"},
    "targetAction": "/flow/answerQuestion",
    "evaluators": ["genkitEval/faithfulness"]
  }'
```

This will:
1. Load the dataset from `.genkit/datasets/qa_evaluation.json`
2. Run the `/flow/answerQuestion` flow for each data point
3. Evaluate the output using the faithfulness metric
4. Save results to `.genkit/evals/`

**Example response:**
```json
{
  "actionRef": "/flow/answerQuestion",
  "datasetId": "qa_evaluation",
  "evalRunId": "7588512e-d124-4472-a608-911ca9b8d81c",
  "createdAt": "2025-12-25T21:04:20.549340Z"
}
```

**View evaluation results:**
```bash
# List saved evaluations
ls .genkit/evals/

# View specific evaluation run
cat .genkit/evals/<evalRunId>.json | jq .
```

### Running Evaluations Programmatically

You can also run evaluations programmatically using the Genkit evaluation API:

```java
// Create evaluation request
RunEvaluationRequest.DataSource dataSource = new RunEvaluationRequest.DataSource();
dataSource.setDatasetId("qa_evaluation");

RunEvaluationRequest request = RunEvaluationRequest.builder()
    .dataSource(dataSource)
    .targetAction("/flow/answerQuestion")
    .evaluators(Arrays.asList(
        "genkitEval/faithfulness",
        "genkitEval/answer_relevancy",
        "genkitEval/answer_accuracy"
    ))
    .build();

EvalRunKey result = genkit.evaluate(request);
```

## Configuration Options

The Evaluators Plugin supports various configuration options:

```java
EvaluatorsPluginOptions options = EvaluatorsPluginOptions.builder()
    // Use specific metrics only
    .metricTypes(GenkitMetric.FAITHFULNESS, GenkitMetric.REGEX)
    
    // Or use all metrics
    .useAllMetrics()
    
    // Configure judge model (for LLM-based metrics)
    .judge("openai/gpt-4o-mini")
    
    // Configure embedder (for answer relevancy)
    .embedder("openai/text-embedding-3-small")
    
    // Per-metric configuration (for overriding judge/embedder per metric)
    .metricConfig(GenkitMetric.FAITHFULNESS, MetricConfig.withJudge(
        GenkitMetric.FAITHFULNESS, 
        "openai/gpt-4o"  // Use a more powerful model for faithfulness
    ))
    
    .build();
```

**Note:** For programmatic metrics like REGEX, DEEP_EQUAL, and JSONATA, the pattern/expression is provided in the `reference` field of each test case, not in the configuration.

## Metric Details

### Faithfulness

Evaluates whether the generated answer is faithful to the provided context. Uses a two-step process:
1. Extract statements from the answer
2. Verify each statement against the context using NLI (Natural Language Inference)

**Score**: Ratio of faithful statements to total statements (0.0 - 1.0)

### Answer Relevancy

Evaluates if the answer is relevant to the question. Optionally uses embedding similarity.

**Score**: Based on LLM judgment and optional cosine similarity (0.0 - 1.0)

### Answer Accuracy

Evaluates if the generated answer matches a reference answer. Uses bidirectional comparison with harmonic mean.

**Score**: Harmonic mean of forward and backward accuracy (0.0 - 1.0)

### Maliciousness

Detects harmful, unethical, or malicious content in the output.

**Score**: 1.0 if safe, 0.0 if malicious

### Regex

Matches the output against a regular expression pattern.

**Score**: 1.0 if matches, 0.0 if doesn't match

### Deep Equal

Compares two JSON objects for deep equality.

**Score**: 1.0 if equal, 0.0 if different

### JSONata

Evaluates a JSONata expression against the output and checks if the result is truthy.

**Score**: Based on JSONata expression result (normalized to 0.0 - 1.0)

## Data Storage

- **Datasets**: `./.genkit/datasets/`
- **Evaluation Runs**: `./.genkit/evals/`

## Troubleshooting

### Evaluators showing "No input variables specified"

This is expected behavior! Evaluators are not meant to be run directly from the Actions tab. Use the **Evaluations** tab instead to:
1. Create/select a dataset
2. Run evaluations against a flow

### Evaluations showing "Error 100%"

This usually means:
1. **Missing output**: Make sure your flow returns an object with an `answer` key, or the evaluator can't find the output to evaluate
2. **Missing context**: For FAITHFULNESS metric, ensure the input has a `context` array or the output includes context
3. **Wrong input format**: LLM evaluators expect:
   - Input: `{"question": "..."}` or `{"question": "...", "context": [...]}`
   - Output: `{"answer": "..."}` or a plain string

### LLM-based evaluators failing

Make sure your `OPENAI_API_KEY` is set correctly:
```bash
echo $OPENAI_API_KEY
```

### Build errors

Ensure the evaluators plugin is built first:
```bash
cd ../../plugins/evaluators
mvn clean install
```

### Port already in use

If port 8080 or 3100 is in use, you can modify the ports in the sample code or stop the existing process.

## See Also

- [Genkit Evaluators Plugin Documentation](../../plugins/evaluators/README.md)
- [Main Evaluations Sample](../evaluations/README.md)
- [Genkit Java Documentation](../../README.md)
