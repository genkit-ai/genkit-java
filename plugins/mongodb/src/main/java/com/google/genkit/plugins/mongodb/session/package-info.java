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
 * MongoDB-backed agent session persistence.
 *
 * <p>{@link com.google.genkit.plugins.mongodb.session.MongoSessionStore} implements the Genkit
 * {@code SessionStore} contract using the sharded checkpoint + RFC-6902 diff + pointer layout
 * shared with the Firestore, DynamoDB, Cosmos DB, and PostgreSQL backends. Construct it directly
 * from a {@code com.mongodb.client.MongoClient} and pass it to an agent via {@code
 * AgentConfig.store(...)}.
 */
package com.google.genkit.plugins.mongodb.session;
