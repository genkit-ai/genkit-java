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

package com.google.genkit.plugins.firebase.telemetry;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.cloud.opentelemetry.metric.GoogleCloudMetricExporter;
import com.google.cloud.opentelemetry.metric.MetricConfiguration;
import com.google.cloud.opentelemetry.trace.TraceConfiguration;
import com.google.cloud.opentelemetry.trace.TraceExporter;
import com.google.genkit.core.telemetry.TelemetryConfig;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;

/**
 * Firebase telemetry integration for Genkit Monitoring.
 * 
 * <p>
 * Configures OpenTelemetry to export traces and metrics to Google Cloud,
 * enabling Firebase Genkit Monitoring features.
 * 
 * <p>
 * This class does NOT define any new metrics - it simply configures exporters
 * to send the existing Genkit core metrics (from GenerateTelemetry,
 * ToolTelemetry, etc.) to Google Cloud Monitoring.
 * 
 * <p>
 * Key capabilities:
 * <ul>
 * <li>View quantitative metrics like latency, errors, and token usage in Cloud
 * Monitoring</li>
 * <li>Inspect traces in Cloud Trace to see flow steps, inputs, and outputs</li>
 * <li>Export production traces to run evaluations</li>
 * </ul>
 * 
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * // Enable telemetry via Firebase plugin builder
 * FirebasePlugin.builder().projectId("my-project").enableTelemetry(true).forceDevExport(true) // Enable in dev mode
 * 		.build();
 * }</pre>
 * 
 * <p>
 * Prerequisites:
 * <ul>
 * <li>Set GCLOUD_PROJECT or GOOGLE_CLOUD_PROJECT environment variable</li>
 * <li>Configure Google Cloud credentials (GOOGLE_APPLICATION_CREDENTIALS or
 * default credentials)</li>
 * <li>Enable Cloud Trace and Cloud Monitoring APIs in your GCP project</li>
 * </ul>
 */
public class FirebaseTelemetry {

  private static final Logger logger = LoggerFactory.getLogger(FirebaseTelemetry.class);
  private static final String SERVICE_NAME = "genkit";
  private static final String SERVICE_VERSION = "1.0.0";

  private final String projectId;
  private final boolean forceDevExport;
  private final long metricExportIntervalMillis;

  private SdkMeterProvider meterProvider;
  private boolean enabled = false;

  private FirebaseTelemetry(Builder builder) {
    this.projectId = builder.projectId;
    this.forceDevExport = builder.forceDevExport;
    this.metricExportIntervalMillis = builder.metricExportIntervalMillis;
  }

  /**
   * Creates a builder for FirebaseTelemetry.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Enables Firebase telemetry with default configuration based on environment.
   * 
   * <p>
   * Checks for ENABLE_FIREBASE_MONITORING environment variable.
   */
  public static void enableFromEnvironment() {
    String envValue = System.getenv("ENABLE_FIREBASE_MONITORING");
    if ("true".equalsIgnoreCase(envValue)) {
      builder().build().enable();
    }
  }

  /**
   * Enables telemetry collection and export to Google Cloud.
   * 
   * <p>
   * This configures:
   * <ul>
   * <li>Trace export to Google Cloud Trace via Genkit's core Tracer</li>
   * <li>Metric export to Google Cloud Monitoring via GlobalOpenTelemetry</li>
   * </ul>
   * 
   * <p>
   * The existing Genkit metrics (GenerateTelemetry, ToolTelemetry) use
   * GlobalOpenTelemetry.getMeter(), so once we configure the global meter
   * provider with a Google Cloud exporter, those metrics will be automatically
   * exported.
   */
  public void enable() {
    if (enabled) {
      logger.debug("Firebase telemetry already enabled");
      return;
    }

    // Check if we should export (production or forceDevExport)
    boolean shouldExport = forceDevExport || isProductionEnvironment();
    if (!shouldExport) {
      logger.info("Firebase telemetry disabled in development mode. Set forceDevExport=true to enable.");
      return;
    }

    if (projectId == null) {
      logger.warn("Firebase telemetry cannot be enabled: no project ID configured");
      return;
    }

    try {
      initializeGoogleCloudTelemetry();
      enabled = true;
      logger.info("Firebase telemetry enabled for project: {}", projectId);
    } catch (Exception e) {
      logger.error("Failed to initialize Firebase telemetry: {}", e.getMessage(), e);
    }
  }

  /**
   * Initializes OpenTelemetry with Google Cloud exporters.
   * 
   * <p>
   * Sets up:
   * <ul>
   * <li>Trace exporter registered with Genkit's core Tracer</li>
   * <li>Metric exporter registered globally for all OpenTelemetry meters</li>
   * </ul>
   */
  private void initializeGoogleCloudTelemetry() throws IOException {
    // Create resource with service info
    Resource resource = Resource.getDefault()
        .merge(Resource.create(Attributes.builder().put(ServiceAttributes.SERVICE_NAME, SERVICE_NAME)
            .put(ServiceAttributes.SERVICE_VERSION, SERVICE_VERSION).put("cloud.project_id", projectId)
            .build()));

    // === TRACES ===
    // Create Google Cloud Trace exporter
    var traceExporter = TraceExporter
        .createWithConfiguration(TraceConfiguration.builder().setProjectId(projectId).build());

    // Create batch span processor for Google Cloud Trace
    SpanProcessor gcpSpanProcessor = BatchSpanProcessor.builder(traceExporter)
        .setScheduleDelay(Duration.ofSeconds(5)).build();

    // Register the span processor with TelemetryConfig
    // This ensures all Genkit traces are exported to Google Cloud Trace
    TelemetryConfig.registerSpanProcessor(gcpSpanProcessor);

    // === METRICS ===
    // Create Google Cloud Metric exporter
    var metricExporter = GoogleCloudMetricExporter
        .createWithConfiguration(MetricConfiguration.builder().setProjectId(projectId).build());

    // Create meter provider with periodic reader for metrics
    meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
            .setInterval(Duration.ofMillis(metricExportIntervalMillis)).build())
        .setResource(resource).build();

    // Register with TelemetryConfig so GenerateTelemetry and other metrics classes
    // will use this meter provider instead of the default no-op one
    TelemetryConfig.setMeterProvider(meterProvider);

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      logger.debug("Shutting down Firebase telemetry...");
      if (meterProvider != null) {
        meterProvider.close();
      }
    }));

    logger.info("Google Cloud telemetry initialized - traces to Cloud Trace, metrics to Cloud Monitoring");
  }

  /**
   * Checks if running in a production environment.
   */
  private boolean isProductionEnvironment() {
    // Check for Cloud Run / Cloud Functions environment
    String kService = System.getenv("K_SERVICE");
    String functionName = System.getenv("FUNCTION_NAME");
    String gaeApplication = System.getenv("GAE_APPLICATION");

    return kService != null || functionName != null || gaeApplication != null;
  }

  /**
   * Checks if telemetry is enabled.
   *
   * @return true if enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Shuts down the telemetry exporters.
   */
  public void shutdown() {
    logger.debug("Shutting down Firebase telemetry");
    if (meterProvider != null) {
      meterProvider.close();
    }
    enabled = false;
  }

  /**
   * Builder for FirebaseTelemetry.
   */
  public static class Builder {
    private String projectId;
    private boolean forceDevExport = false;
    private long metricExportIntervalMillis = 10000; // 10 seconds default

    /**
     * Sets the Firebase/GCP project ID.
     *
     * @param projectId
     *            the project ID
     * @return this builder
     */
    public Builder projectId(String projectId) {
      this.projectId = projectId;
      return this;
    }

    /**
     * Forces telemetry export in development mode.
     * 
     * <p>
     * By default, telemetry is only exported in production environments (Cloud Run,
     * Cloud Functions, App Engine). Set this to true to enable export during local
     * development.
     *
     * @param forceDevExport
     *            true to force export in dev mode
     * @return this builder
     */
    public Builder forceDevExport(boolean forceDevExport) {
      this.forceDevExport = forceDevExport;
      return this;
    }

    /**
     * Sets the metric export interval.
     *
     * @param metricExportIntervalMillis
     *            the interval in milliseconds
     * @return this builder
     */
    public Builder metricExportIntervalMillis(long metricExportIntervalMillis) {
      this.metricExportIntervalMillis = metricExportIntervalMillis;
      return this;
    }

    /**
     * Builds the FirebaseTelemetry instance.
     *
     * @return the configured telemetry instance
     */
    public FirebaseTelemetry build() {
      // Auto-detect project ID from environment
      if (projectId == null) {
        projectId = System.getenv("GCLOUD_PROJECT");
        if (projectId == null) {
          projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
        }
      }
      return new FirebaseTelemetry(this);
    }
  }
}
