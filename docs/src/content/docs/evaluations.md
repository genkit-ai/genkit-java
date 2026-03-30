---
title: Evaluations
description: Assess AI output quality with custom and pre-built evaluators.
---

Genkit provides a framework for evaluating AI output quality. You can define custom evaluators for your specific needs, use pre-built RAGAS-style metrics, manage evaluation datasets, and run evaluations against flows or models.

## Custom evaluators

Define evaluators to assess specific quality dimensions. Each evaluator receives an `EvalDataPoint` containing the input, output, context, and reference, and returns a `Score`.

```java
import com.google.genkit.ai.evaluation.*;

Evaluator<Void> lengthEvaluator = genkit.defineEvaluator(
    "custom/length",
    "Output Length",
    "Evaluates whether the output has an appropriate length",
    (dataPoint, options) -> {
        String output = dataPoint.getOutput() != null
            ? dataPoint.getOutput().toString() : "";
        int length = output.length();
        double score = (length >= 50 && length <= 500)
            ? 1.0 : Math.min(length / 50.0, 1.0);
        EvalStatus status = score >= 1.0
            ? EvalStatus.PASS : EvalStatus.FAIL;

        return EvalResponse.builder()
            .testCaseId(dataPoint.getTestCaseId())
            .evaluation(Score.builder()
                .score(score)
                .status(status)
                .details(ScoreDetails.builder()
                    .reasoning("Output length: " + length + " characters")
                    .build())
                .build())
            .build();
    });
```

### Evaluator with options

You can define evaluators that accept typed options:

```java
Evaluator<ThresholdOptions> thresholdEvaluator = genkit.defineEvaluator(
    "custom/threshold",
    "Threshold Check",
    "Checks output against a configurable threshold",
    false,  // isBilled
    ThresholdOptions.class,
    (dataPoint, options) -> {
        double threshold = options.getThreshold();
        // ... evaluate against threshold
        return EvalResponse.builder()
            .testCaseId(dataPoint.getTestCaseId())
            .evaluation(Score.builder().score(score).build())
            .build();
    });
```

### EvalDataPoint fields

Each data point passed to your evaluator contains:

| Field | Type | Description |
|-------|------|-------------|
| `testCaseId` | `String` | Unique identifier for the test case |
| `input` | `Object` | The input sent to the flow/model |
| `output` | `Object` | The output produced by the flow/model |
| `context` | `List<String>` | Retrieved context documents (for RAG) |
| `reference` | `Object` | Expected/reference output |
| `custom` | `Map<String, Object>` | Custom metadata |
| `traceIds` | `List<String>` | Associated trace IDs |

### Score values

Scores can be numeric, boolean, or string:

```java
Score.builder().score(0.95).status(EvalStatus.PASS).build()    // numeric
Score.builder().score(true).status(EvalStatus.PASS).build()    // boolean
Score.builder().score("good").status(EvalStatus.PASS).build()  // categorical
```

The `EvalStatus` enum has three values: `PASS`, `FAIL`, and `UNKNOWN`.

## Managing datasets

Genkit includes a `DatasetStore` for managing evaluation datasets. The default implementation (`LocalFileDatasetStore`) persists datasets to local files.

### Creating a dataset

```java
DatasetStore datasetStore = genkit.getDatasetStore();

List<DatasetSample> samples = List.of(
    DatasetSample.builder()
        .testCaseId("food_1")
        .input("pizza")
        .reference("A delicious Italian dish with a crispy crust and savory toppings")
        .build(),
    DatasetSample.builder()
        .testCaseId("food_2")
        .input("sushi")
        .reference("Fresh, delicate rolls of rice and fish from Japan")
        .build());

DatasetMetadata metadata = datasetStore.createDataset(
    CreateDatasetRequest.builder()
        .datasetId("food_descriptions")
        .data(samples)
        .datasetType(DatasetType.FLOW)
        .targetAction("/flow/describeFood")
        .metricRefs(List.of("custom/length", "custom/keywords"))
        .build());
```

### Listing and retrieving datasets

```java
List<DatasetMetadata> datasets = datasetStore.listDatasets();

DatasetMetadata dataset = datasetStore.getDataset("food_descriptions");
```

### Updating a dataset

```java
datasetStore.updateDataset(UpdateDatasetRequest.builder()
    .datasetId("food_descriptions")
    .data(updatedSamples)
    .version(dataset.getVersion())  // optimistic concurrency
    .build());
```

### Dataset types

| Type | Description |
|------|-------------|
| `DatasetType.FLOW` | Dataset targets a flow |
| `DatasetType.MODEL` | Dataset targets a model |
| `DatasetType.CUSTOM` | Custom dataset type |

## Running evaluations

Run evaluations against a flow or model using a dataset:

```java
EvalRunKey result = genkit.evaluate(
    RunEvaluationRequest.builder()
        .dataSource(DataSource.builder()
            .datasetId("food_descriptions")
            .build())
        .targetAction("/flow/describeFood")
        .evaluators(List.of("custom/length", "custom/keywords"))
        .build());
```

### Inline data (no dataset)

You can also provide data inline without creating a dataset first:

```java
EvalRunKey result = genkit.evaluate(
    RunEvaluationRequest.builder()
        .dataSource(DataSource.builder()
            .data(List.of(
                DatasetSample.builder()
                    .testCaseId("test_1")
                    .input("What is Genkit?")
                    .reference("Genkit is an AI framework")
                    .build()))
            .build())
        .targetAction("/flow/myFlow")
        .evaluators(List.of("custom/length"))
        .build());
```

## Viewing evaluation results

Evaluation results are stored in an `EvalStore`. The default implementation (`LocalFileEvalStore`) persists to local files.

```java
EvalStore evalStore = genkit.getEvalStore();

// List all evaluation runs
List<EvalRunKey> runs = evalStore.list();

// Load a specific run
EvalRun run = evalStore.load(result);  // result from evaluate()

for (EvalResult evalResult : run.getResults()) {
    System.out.println("Test: " + evalResult.getTestCaseId());
    for (EvalMetric metric : evalResult.getMetrics()) {
        System.out.println("  " + metric.getEvaluator()
            + ": " + metric.getScore()
            + " (" + metric.getStatus() + ")");
    }
}
```

## Pre-built evaluators plugin

Use the evaluators plugin for RAGAS-style metrics without writing custom evaluators:

```java
import com.google.genkit.plugins.evaluators.EvaluatorsPlugin;
import com.google.genkit.plugins.evaluators.EvaluatorsPluginOptions;
import com.google.genkit.plugins.evaluators.GenkitMetric;

Genkit genkit = Genkit.builder()
    .plugin(OpenAIPlugin.create())
    .plugin(EvaluatorsPlugin.create(
        EvaluatorsPluginOptions.builder()
            .judge("openai/gpt-4o-mini")
            .metrics(List.of(
                GenkitMetric.FAITHFULNESS,
                GenkitMetric.ANSWER_RELEVANCY,
                GenkitMetric.ANSWER_ACCURACY,
                GenkitMetric.MALICIOUSNESS,
                GenkitMetric.REGEX,
                GenkitMetric.DEEP_EQUAL,
                GenkitMetric.JSONATA
            ))
            .build()))
    .build();
```

Or enable all metrics at once:

```java
EvaluatorsPluginOptions.builder()
    .useAllMetrics()
    .judge("openai/gpt-4o-mini")
    .embedder("openai/text-embedding-3-small")
    .build()
```

### Available metrics

| Metric | Type | Description |
|--------|------|-------------|
| `FAITHFULNESS` | LLM-based | Factual accuracy against provided context |
| `ANSWER_RELEVANCY` | LLM-based | Answer pertains to the question |
| `ANSWER_ACCURACY` | LLM-based | Matches reference answer |
| `MALICIOUSNESS` | LLM-based | Detects harmful content |
| `REGEX` | Programmatic | Pattern matching against output |
| `DEEP_EQUAL` | Programmatic | JSON deep equality comparison |
| `JSONATA` | Programmatic | JSONata expression evaluation |

LLM-based metrics require a `judge` model. Programmatic metrics run locally without any API calls.

See the [evaluators plugin docs](/genkit-java/plugins/evaluators/) for full details.

## Samples

- [evaluations sample](https://github.com/xavidop/genkit-java/tree/main/samples/evaluations) — Custom evaluators, dataset management, and running evaluations
- [evaluators-plugin sample](https://github.com/xavidop/genkit-java/tree/main/samples/evaluators-plugin) — Pre-built RAGAS metrics
