/*
 * Copyright 2025 Google LLC
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

package com.google.genkit;

import com.google.genkit.core.tracing.GenkitSpanData;
import com.google.genkit.core.tracing.TraceData;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SpanProcessor that stores traces locally for the Dev UI to access. This enables the evaluation
 * workflow to retrieve trace data including input and output values.
 */
public class LocalTelemetryStore implements SpanProcessor {

  private static final Logger logger = LoggerFactory.getLogger(LocalTelemetryStore.class);

  // Buffer spans by trace ID until the root span completes
  private final Map<String, TraceData> traceBuffer = new ConcurrentHashMap<>();

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    // No action needed on start
  }

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    try {
      SpanData spanData = span.toSpanData();
      String traceId = spanData.getTraceId();
      String spanId = spanData.getSpanId();

      // Convert span to GenkitSpanData format
      GenkitSpanData genkitSpan = convertSpan(spanData);

      // Get or create trace data
      TraceData traceData = traceBuffer.computeIfAbsent(traceId, TraceData::new);
      traceData.addSpan(genkitSpan);

      // Check if this is a root span (no parent)
      String parentSpanId = spanData.getParentSpanId();
      boolean isRoot =
          parentSpanId == null || parentSpanId.isEmpty() || "0000000000000000".equals(parentSpanId);

      if (isRoot) {
        // Set trace-level info
        traceData.setDisplayName(spanData.getName());
        traceData.setStartTime(toMillis(spanData.getStartEpochNanos()));
        traceData.setEndTime(toMillis(spanData.getEndEpochNanos()));

        // Store the completed trace
        ReflectionServer.storeTrace(traceData);

        // Remove from buffer
        traceBuffer.remove(traceId);

        logger.debug(
            "Stored completed trace: {} with {} spans", traceId, traceData.getSpans().size());
      }
    } catch (Exception e) {
      logger.error("Failed to store span locally", e);
    }
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  @Override
  public CompletableResultCode shutdown() {
    // Store any remaining buffered traces
    for (TraceData trace : traceBuffer.values()) {
      ReflectionServer.storeTrace(trace);
    }
    traceBuffer.clear();
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode forceFlush() {
    // Store all buffered traces
    for (TraceData trace : traceBuffer.values()) {
      ReflectionServer.storeTrace(trace);
    }
    return CompletableResultCode.ofSuccess();
  }

  private GenkitSpanData convertSpan(SpanData otelSpan) {
    GenkitSpanData span = new GenkitSpanData();

    span.setSpanId(otelSpan.getSpanId());
    span.setTraceId(otelSpan.getTraceId());
    span.setDisplayName(otelSpan.getName() != null ? otelSpan.getName() : "unknown");
    span.setStartTime(toMillis(otelSpan.getStartEpochNanos()));
    span.setEndTime(toMillis(otelSpan.getEndEpochNanos()));
    span.setSpanKind(otelSpan.getKind().name());

    String parentSpanId = otelSpan.getParentSpanId();
    if (parentSpanId != null
        && !parentSpanId.isEmpty()
        && !"0000000000000000".equals(parentSpanId)) {
      span.setParentSpanId(parentSpanId);
    }

    // Convert attributes - this includes genkit:input and genkit:output
    Map<String, Object> attributes = new HashMap<>();
    otelSpan
        .getAttributes()
        .forEach(
            (key, value) -> {
              attributes.put(key.getKey(), value);
            });
    span.setAttributes(attributes);

    // Convert status
    GenkitSpanData.Status status = new GenkitSpanData.Status();
    status.setCode(convertStatusCode(otelSpan.getStatus().getStatusCode()));
    if (otelSpan.getStatus().getDescription() != null) {
      status.setMessage(otelSpan.getStatus().getDescription());
    }
    span.setStatus(status);

    // Set instrumentation scope
    GenkitSpanData.InstrumentationScope scope = new GenkitSpanData.InstrumentationScope();
    String scopeName = otelSpan.getInstrumentationScopeInfo().getName();
    scope.setName(scopeName != null && !scopeName.isEmpty() ? scopeName : "genkit-java");
    String version = otelSpan.getInstrumentationScopeInfo().getVersion();
    scope.setVersion(version != null ? version : "1.0.0");
    span.setInstrumentationScope(scope);

    // Set sameProcessAsParentSpan
    span.setSameProcessAsParentSpan(
        new GenkitSpanData.BoolValue(!otelSpan.getSpanContext().isRemote()));

    return span;
  }

  private int convertStatusCode(StatusCode statusCode) {
    switch (statusCode) {
      case OK:
        return 0;
      case ERROR:
        return 2;
      case UNSET:
      default:
        return 0;
    }
  }

  private long toMillis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }
}
