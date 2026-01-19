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

/**
 * Pinecone plugin for Genkit providing vector database integration.
 *
 * <p>This plugin provides:
 *
 * <ul>
 *   <li>Pinecone serverless and pod-based index support
 *   <li>Document indexing with automatic embedding generation
 *   <li>Namespace support for multi-tenant applications
 *   <li>Metadata filtering for precise retrieval
 *   <li>Automatic index creation and management
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(GoogleGenAIPlugin.create(apiKey))
 *     .plugin(
 *         PineconePlugin.builder()
 *             .apiKey(System.getenv("PINECONE_API_KEY"))
 *             .addIndex(
 *                 PineconeIndexConfig.builder()
 *                     .indexName("my-index")
 *                     .embedderName("googleai/text-embedding-004")
 *                     .build())
 *             .build())
 *     .build();
 * }</pre>
 */
package com.google.genkit.plugins.pinecone;
