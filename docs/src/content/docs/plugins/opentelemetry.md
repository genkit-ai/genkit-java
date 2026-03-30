---
title: OpenTelemetry Plugin
description: Export Genkit traces and metrics to any OpenTelemetry-compatible backend.
---

Genkit Java has built-in OpenTelemetry support in its core module. All actions (models, tools, flows) are automatically instrumented with traces and metrics. You can export this telemetry data to any OpenTelemetry-compatible backend by registering custom span processors and meter providers.

## Built-in instrumentation

The core module automatically instruments every Genkit action with:

- **Traces** — Rich span data with inputs, outputs, execution paths, session info, and error details.
- **Metrics** — Counters and histograms for request counts, latencies, token usage, and more.

No additional dependencies are required for local development — the Dev UI displays traces automatically.

## Dependencies

The core Genkit module already includes the OpenTelemetry SDK. If you need to add custom exporters (e.g., OTLP, Jaeger, Zipkin), add the relevant exporter dependency:

```xml
<!-- OTLP exporter (gRPC) -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>

<!-- Zipkin exporter -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-zipkin</artifactId>
</dependency>
```

The OpenTelemetry BOM is managed by the Genkit parent POM, so you don't need to specify versions.

## Registering a custom span processor

Use `TelemetryConfig.registerSpanProcessor()` to add your own trace exporter. This works alongside the built-in Dev UI exporter — your spans are sent to both.

```java
import com.google.genkit.core.telemetry.TelemetryConfig;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

// Create an OTLP exporter pointing to your collector
OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
    .setEndpoint("http://localhost:4317")
    .build();

// Wrap in a batch processor and register
BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter).build();
TelemetryConfig.registerSpanProcessor(processor);
```

You can register multiple span processors. Each one receives all Genkit trace spans.

## Registering a custom meter provider

Use `TelemetryConfig.setMeterProvider()` to route metrics to your backend:

```java
import com.google.genkit.core.telemetry.TelemetryConfig;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;

// Create metric exporter
OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
    .setEndpoint("http://localhost:4317")
    .build();

// Create meter provider with periodic reader
SdkMeterProvider meterProvider = SdkMeterProvider.builder()
    .registerMetricReader(
        PeriodicMetricReader.builder(metricExporter).build()
    )
    .build();

TelemetryConfig.setMeterProvider(meterProvider);
```

Once set, all Genkit metrics (`genkit/ai/generate/requests`, `genkit/ai/generate/latency`, token counters, etc.) are automatically exported.

## Full example: OTLP collector

Send all traces and metrics to an OpenTelemetry Collector running locally:

```java
import com.google.genkit.Genkit;
import com.google.genkit.core.telemetry.TelemetryConfig;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

public class App {
    public static void main(String[] args) {
        // Configure OTLP trace export
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://localhost:4317")
            .build();
        TelemetryConfig.registerSpanProcessor(
            BatchSpanProcessor.builder(spanExporter).build()
        );

        // Configure OTLP metric export
        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint("http://localhost:4317")
            .build();
        TelemetryConfig.setMeterProvider(
            SdkMeterProvider.builder()
                .registerMetricReader(
                    PeriodicMetricReader.builder(metricExporter).build()
                )
                .build()
        );

        // Initialize Genkit — telemetry is already wired
        Genkit genkit = Genkit.builder()
            .addPlugin(new GoogleGenAIPlugin())
            .build();

        // All generate/flow/tool calls now export traces and metrics
        genkit.generate(options -> options.model("googleai/gemini-2.0-flash").prompt("Hi"));
    }
}
```

## Supported backends

Because Genkit uses standard OpenTelemetry APIs, you can export to any compatible backend:

| Backend | Exporter |
|---------|----------|
| [Jaeger](https://www.jaegertracing.io/) | `opentelemetry-exporter-otlp` |
| [Zipkin](https://zipkin.io/) | `opentelemetry-exporter-zipkin` |
| [Grafana / Tempo](https://grafana.com/oss/tempo/) | `opentelemetry-exporter-otlp` |
| [Datadog](https://www.datadoghq.com/) | OTLP via Datadog Agent |
| [New Relic](https://newrelic.com/) | `opentelemetry-exporter-otlp` |
| [Honeycomb](https://www.honeycomb.io/) | `opentelemetry-exporter-otlp` |
| [Google Cloud](https://cloud.google.com/) | Firebase plugin (built-in) |
| [AWS X-Ray](https://aws.amazon.com/xray/) | `opentelemetry-exporter-otlp` |

For Google Cloud specifically, the [Firebase plugin](/genkit-java/plugins/firebase/) provides a turnkey integration.

## Trace attributes reference

See the [Observability overview](/genkit-java/observability/) for the full list of trace attributes and metrics emitted by Genkit.
