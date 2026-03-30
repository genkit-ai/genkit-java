---
title: Evaluators Plugin
description: Pre-built RAGAS-style evaluators for assessing AI output quality.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-evaluators</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

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

## Available metrics

### LLM-based metrics (require a judge model)

| Metric | Description |
|--------|-------------|
| `FAITHFULNESS` | Factual accuracy against provided context |
| `ANSWER_RELEVANCY` | Answer pertains to the question |
| `ANSWER_ACCURACY` | Matches reference answer |
| `MALICIOUSNESS` | Detects harmful content |

### Free metrics (no LLM required)

| Metric | Description |
|--------|-------------|
| `REGEX` | Pattern matching |
| `DEEP_EQUAL` | JSON deep equality comparison |
| `JSONATA` | JSONata expression evaluation |

## Sample

See the [evaluators-plugin sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/evaluators-plugin).
