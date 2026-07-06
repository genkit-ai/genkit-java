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

package com.google.genkit.plugins.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genkit.ai.Document;
import com.google.genkit.ai.EmbedResponse;
import com.google.genkit.ai.Embedder;
import com.google.genkit.ai.EmbedderInfo;
import com.google.genkit.ai.IndexerRequest;
import com.google.genkit.ai.RetrieverRequest;
import com.google.genkit.ai.RetrieverResponse;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link MongoVectorStore}, gated on the {@code MONGODB_ATLAS_URI}
 * environment variable (a MongoDB Atlas cluster or the {@code mongodb/mongodb-atlas-local} Docker
 * image). Skipped via {@link org.junit.jupiter.api.Assumptions} when unset. Uses a deterministic
 * stub embedder so a query equal to an indexed document's text retrieves that document first.
 */
class MongoVectorStoreTest {

  private static final String URI = System.getenv("MONGODB_ATLAS_URI");
  private static final int DIM = 16;

  private MongoClient client;
  private String database;
  private String collection;
  private MongoVectorStore store;

  private static boolean configured() {
    return URI != null && !URI.isEmpty();
  }

  private static Embedder stubEmbedder() {
    return new Embedder(
        "test/stub",
        new EmbedderInfo(),
        (ctx, req) -> {
          List<EmbedResponse.Embedding> out = new ArrayList<>();
          for (Document doc : req.getDocuments()) {
            Random random = new Random(doc.text().hashCode());
            float[] values = new float[DIM];
            for (int i = 0; i < DIM; i++) {
              values[i] = random.nextFloat();
            }
            out.add(new EmbedResponse.Embedding(values));
          }
          return new EmbedResponse(out);
        });
  }

  @BeforeEach
  void setUp() {
    if (!configured()) {
      return;
    }
    client = MongoClients.create(URI);
    database = "genkit_test";
    collection = "vec_" + UUID.randomUUID().toString().replace("-", "");
    MongoVectorStoreConfig config =
        MongoVectorStoreConfig.builder()
            .databaseName(database)
            .collectionName(collection)
            .embedderName("test/stub")
            .dimension(DIM)
            .createIndexIfNotExists(true)
            .build();
    store = new MongoVectorStore(client, config, stubEmbedder());
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      if (database != null && collection != null) {
        client.getDatabase(database).getCollection(collection).drop();
      }
      client.close();
    }
  }

  @Test
  void indexThenRetrieveReturnsNearestFirst() throws Exception {
    assumeTrue(configured());
    List<Document> docs =
        List.of(
            Document.fromText("The Matrix is a sci-fi film about simulated reality."),
            Document.fromText("The Godfather is a crime film about a mafia family."),
            Document.fromText("Inception is a sci-fi film about dreams."));
    store.index(null, new IndexerRequest(docs));

    RetrieverRequest request = new RetrieverRequest(Document.fromText(docs.get(1).text()));
    RetrieverRequest.RetrieverOptions options = new RetrieverRequest.RetrieverOptions();
    options.setK(3);
    request.setOptions(options);

    // Newly indexed documents may take a moment to become searchable.
    RetrieverResponse response = null;
    for (int attempt = 0; attempt < 15; attempt++) {
      response = store.retrieve(null, request);
      if (!response.getDocuments().isEmpty()) {
        break;
      }
      Thread.sleep(1000);
    }
    assertFalse(response.getDocuments().isEmpty());
    assertEquals(
        "The Godfather is a crime film about a mafia family.",
        response.getDocuments().get(0).text());
  }
}
