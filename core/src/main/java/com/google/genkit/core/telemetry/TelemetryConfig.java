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

package com.google.genkit.core.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.core.tracing.Tracer;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Central configuration for Genkit telemetry.
 * 
 * <p>
 * This class provides the single entry point for telemetry plugins (like
 * Firebase) to configure both tracing and metrics exporters.
 * 
 * <p>
 * Example usage by a telemetry plugin:
 * 
 * <pre>{@code
 * // Register span processor for traces
 * SpanProcessor spanProcessor = BatchSpanProcessor.builder(traceExporter).build();
 * TelemetryConfig.registerSpanProcessor(spanProcessor);
 * 
 * // Register meter provider for metrics
 * SdkMeterProvider meterProvider = SdkMeterProvider.builder()
 *     .registerMetricReader(...)
 *     .build();
 * TelemetryConfig.setMeterProvider(meterProvider);
 * }</pre>
 */
public final class TelemetryConfig {

  private static final Logger logger = LoggerFactory.getLogger(TelemetryConfig.class);

  private static volatile MeterProvider meterProvider;
  private static final Object lock = new Object();

  private TelemetryConfig() {
    // Utility class
  }

  /**
   * Registers a SpanProcessor for exporting traces.
   * 
   * <p>
   * This allows telemetry plugins to add their own exporters to send traces to
   * external services like Google Cloud Trace. Multiple span processors can be
   * registered.
   *
   * @param processor
   *            the span processor to register
   */
  public static void registerSpanProcessor(SpanProcessor processor) {
    Tracer.registerSpanProcessor(processor);
  }

  /**
   * Sets the MeterProvider to be used by Genkit metrics classes.
   * 
   * <p>
   * This should be called by telemetry plugins (like Firebase) during
   * initialization, before any metrics are recorded.
   *
   * @param provider
   *            the MeterProvider to use for metrics
   */
  public static void setMeterProvider(MeterProvider provider) {
    synchronized (lock) {
      if (meterProvider != null) {
        logger.warn("MeterProvider already set, replacing with new provider");
      }
      meterProvider = provider;
      logger.info("Telemetry MeterProvider configured");
    }
  }

  /**
   * Gets a Meter for recording metrics.
   * 
   * <p>
   * If a MeterProvider has been set via {@link #setMeterProvider(MeterProvider)},
   * it will be used. Otherwise, falls back to GlobalOpenTelemetry.
   *
   * @param instrumentationScopeName
   *            the name of the instrumentation scope
   * @return a Meter for recording metrics
   */
  public static Meter getMeter(String instrumentationScopeName) {
    MeterProvider provider = meterProvider;
    if (provider != null) {
      logger.debug("Using configured MeterProvider for meter: {}", instrumentationScopeName);
      return provider.get(instrumentationScopeName);
    }

    // Fallback to GlobalOpenTelemetry (may be no-op if not configured)
    logger.debug("Using GlobalOpenTelemetry for meter: {}", instrumentationScopeName);
    return GlobalOpenTelemetry.getMeter(instrumentationScopeName);
  }

  /**
   * Checks if a custom MeterProvider has been configured.
   *
   * @return true if a MeterProvider has been set
   */
  public static boolean isMeterProviderConfigured() {
    return meterProvider != null;
  }

  /**
   * Resets the configuration. Primarily for testing.
   */
  public static void reset() {
    synchronized (lock) {
      meterProvider = null;
    }
  }
}
