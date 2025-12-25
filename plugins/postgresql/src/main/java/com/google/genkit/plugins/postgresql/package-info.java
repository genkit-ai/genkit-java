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
 * PostgreSQL plugin for Genkit providing vector database integration using
 * pgvector extension.
 *
 * <p>
 * This plugin provides:
 * <ul>
 * <li>PostgreSQL with pgvector extension for vector similarity search</li>
 * <li>Document indexing with automatic embedding generation</li>
 * <li>Support for multiple distance strategies (cosine, L2, inner product)</li>
 * <li>Automatic table and index creation</li>
 * <li>Connection pooling with HikariCP</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * Genkit genkit = Genkit
 * 		.builder().plugin(
 * 				GoogleGenAIPlugin.create(apiKey))
 * 		.plugin(PostgresPlugin.builder().connectionString("jdbc:postgresql://localhost:5432/mydb").username("user")
 * 				.password("pass").addTable(PostgresTableConfig.builder().tableName("documents")
 * 						.embedderName("googleai/text-embedding-004").build())
 * 				.build())
 * 		.build();
 * }</pre>
 */
package com.google.genkit.plugins.postgresql;
