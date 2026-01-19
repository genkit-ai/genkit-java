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

package com.google.genkit.plugins.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genkit.plugins.firebase.retriever.FirestoreRetrieverConfig;
import java.util.ArrayList;
import java.util.List;

/** Configuration for the Firebase plugin. */
public class FirebasePluginConfig {

  private final String projectId;
  private final GoogleCredentials credentials;
  private final String databaseUrl;
  private final boolean enableTelemetry;
  private final boolean forceDevExport;
  private final long metricExportIntervalMillis;
  private final List<FirestoreRetrieverConfig> retrieverConfigs;

  private FirebasePluginConfig(Builder builder) {
    this.projectId = builder.projectId;
    this.credentials = builder.credentials;
    this.databaseUrl = builder.databaseUrl;
    this.enableTelemetry = builder.enableTelemetry;
    this.forceDevExport = builder.forceDevExport;
    this.metricExportIntervalMillis = builder.metricExportIntervalMillis;
    this.retrieverConfigs = new ArrayList<>(builder.retrieverConfigs);
  }

  /**
   * Creates a builder for FirebasePluginConfig.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  public String getProjectId() {
    return projectId;
  }

  public GoogleCredentials getCredentials() {
    return credentials;
  }

  public String getDatabaseUrl() {
    return databaseUrl;
  }

  public boolean isEnableTelemetry() {
    return enableTelemetry;
  }

  public boolean isForceDevExport() {
    return forceDevExport;
  }

  public long getMetricExportIntervalMillis() {
    return metricExportIntervalMillis;
  }

  public List<FirestoreRetrieverConfig> getRetrieverConfigs() {
    return retrieverConfigs;
  }

  /** Builder for FirebasePluginConfig. */
  public static class Builder {
    private String projectId;
    private GoogleCredentials credentials;
    private String databaseUrl;
    private boolean enableTelemetry = false;
    private boolean forceDevExport = false;
    private long metricExportIntervalMillis = 60000; // 1 minute default
    private final List<FirestoreRetrieverConfig> retrieverConfigs = new ArrayList<>();

    public Builder projectId(String projectId) {
      this.projectId = projectId;
      return this;
    }

    public Builder credentials(GoogleCredentials credentials) {
      this.credentials = credentials;
      return this;
    }

    public Builder databaseUrl(String databaseUrl) {
      this.databaseUrl = databaseUrl;
      return this;
    }

    public Builder enableTelemetry(boolean enableTelemetry) {
      this.enableTelemetry = enableTelemetry;
      return this;
    }

    public Builder forceDevExport(boolean forceDevExport) {
      this.forceDevExport = forceDevExport;
      return this;
    }

    public Builder metricExportIntervalMillis(long metricExportIntervalMillis) {
      this.metricExportIntervalMillis = metricExportIntervalMillis;
      return this;
    }

    public Builder addRetrieverConfig(FirestoreRetrieverConfig config) {
      this.retrieverConfigs.add(config);
      return this;
    }

    public FirebasePluginConfig build() {
      // Project ID can be auto-detected from environment
      if (projectId == null) {
        projectId = System.getenv("GCLOUD_PROJECT");
        if (projectId == null) {
          projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
        }
      }
      return new FirebasePluginConfig(this);
    }
  }
}
