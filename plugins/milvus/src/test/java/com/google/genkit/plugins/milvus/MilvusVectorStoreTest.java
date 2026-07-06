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

package com.google.genkit.plugins.milvus;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link MilvusVectorStore}, gated on the {@code MILVUS_URL} environment
 * variable (e.g. a Milvus server started with the standalone Docker script). Skipped via {@link
 * org.junit.jupiter.api.Assumptions} when unset. Uses a deterministic stub embedder so a query
 * equal to an indexed document's text retrieves that document first.
 */
class MilvusVectorStoreTest {

  private static final String URL = System.getenv("MILVUS_URL");
  private static final String TOKEN = System.getenv("MILVUS_TOKEN");
  private static final int DIM = 16;

  private MilvusVectorStore store;

  private static boolean configured() {
    return URL != null && !URL.isEmpty();
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
    MilvusCollectionConfig config =
        MilvusCollectionConfig.builder()
            .collectionName("genkit_test_" + UUID.randomUUID().toString().replace("-", ""))
            .embedderName("test/stub")
            .dimension(DIM)
            .build();
    store = new MilvusVectorStore(URL, TOKEN, config, stubEmbedder());
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

    // Newly inserted data may take a moment to become searchable.
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
