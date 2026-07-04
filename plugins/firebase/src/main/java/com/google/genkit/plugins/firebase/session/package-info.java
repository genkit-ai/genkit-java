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
 * Firestore-backed session store for Genkit agents.
 *
 * <p>{@link com.google.genkit.plugins.firebase.session.FirestoreSessionStore} implements {@link
 * com.google.genkit.ai.agent.SessionStore} and {@link
 * com.google.genkit.ai.agent.SnapshotSubscriber}, persisting session snapshots to Cloud Firestore
 * using a sharded checkpoint + diff + pointer layout (mirroring the upstream Go/JS Firestore
 * session stores). Configure it with {@link
 * com.google.genkit.plugins.firebase.session.FirestoreSessionStoreOptions}.
 */
package com.google.genkit.plugins.firebase.session;
