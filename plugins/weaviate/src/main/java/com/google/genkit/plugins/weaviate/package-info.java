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
 * Weaviate plugin for Genkit providing vector database integration for RAG
 * workflows.
 *
 * <p>
 * This plugin provides:
 * <ul>
 * <li>Weaviate vector similarity search for retrieval</li>
 * <li>Document indexing with automatic embedding generation</li>
 * <li>Support for both local and Weaviate Cloud instances</li>
 * <li>Configurable distance measures (cosine, L2, dot product)</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * Genkit genkit = Genkit.builder().plugin(GoogleGenAIPlugin.create(apiKey))
 * 		.plugin(WeaviatePlugin.builder().host("localhost").port(8080).addCollection(WeaviateCollectionConfig
 * 				.builder().name("documents").embedderName("googleai/text-embedding-004").build()).build())
 * 		.build();
 * }</pre>
 */
package com.google.genkit.plugins.weaviate;
