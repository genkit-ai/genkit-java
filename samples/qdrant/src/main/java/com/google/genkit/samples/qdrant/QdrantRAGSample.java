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

package com.google.genkit.samples.qdrant;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.*;
import com.google.genkit.core.Flow;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import com.google.genkit.plugins.qdrant.QdrantCollectionConfig;
import com.google.genkit.plugins.qdrant.QdrantPlugin;
import io.github.cdimascio.dotenv.Dotenv;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sample application demonstrating Qdrant vector search with Genkit.
 *
 * <p>Indexes a handful of film descriptions into a Qdrant collection, retrieves the nearest matches
 * for a query, and answers questions with a RAG flow. Requires a running Qdrant server (see the
 * README for a one-line Docker command) and a {@code GEMINI_API_KEY}.
 */
public class QdrantRAGSample {

  private static final Logger logger = LoggerFactory.getLogger(QdrantRAGSample.class);

  private static final List<String> SAMPLE_DOCUMENTS =
      List.of(
          "The Godfather is a 1972 crime film directed by Francis Ford Coppola about the Corleone crime family.",
          "The Dark Knight is a 2008 superhero film directed by Christopher Nolan featuring Batman against the Joker.",
          "Pulp Fiction is a 1994 crime film directed by Quentin Tarantino known for its nonlinear narrative.",
          "Inception is a 2010 sci-fi film directed by Christopher Nolan about dream infiltration.",
          "The Matrix is a 1999 sci-fi film directed by the Wachowskis exploring simulated reality.",
          "Forrest Gump is a 1994 drama directed by Robert Zemeckis about a man's extraordinary life.",
          "Star Wars is a 1977 sci-fi film directed by George Lucas set in a galaxy far, far away.",
          "The Shawshank Redemption is a 1994 drama about hope and friendship in a prison.");

  private static final String RAG_SYSTEM_PROMPT =
      """
      You are a helpful assistant that answers questions based on the provided context documents.
      Answer only from the context. If the context is insufficient, say so.
      """;

  public static void main(String[] args) {
    Dotenv dotenv = Dotenv.configure().ignoreIfMissing().systemProperties().load();

    String geminiApiKey = getEnv(dotenv, "GEMINI_API_KEY");
    if (geminiApiKey == null) {
      logger.error("Please set GEMINI_API_KEY in .env file or environment variable");
      System.exit(1);
    }

    String qdrantUrl = getEnvOrDefault(dotenv, "QDRANT_URL", "http://localhost:6333");
    String qdrantApiKey = getEnv(dotenv, "QDRANT_API_KEY"); // optional
    String collection = getEnvOrDefault(dotenv, "QDRANT_COLLECTION", "genkit_films");

    logger.info("Starting Qdrant RAG Sample (url={}, collection={})", qdrantUrl, collection);

    QdrantPlugin qdrantPlugin =
        QdrantPlugin.builder()
            .url(qdrantUrl)
            .apiKey(qdrantApiKey)
            .addCollection(
                QdrantCollectionConfig.builder()
                    .collectionName(collection)
                    .embedderName("googleai/gemini-embedding-001")
                    .dimension(768)
                    .distance(QdrantCollectionConfig.Distance.COSINE)
                    .build())
            .build();

    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());
    Genkit genkit =
        Genkit.builder()
            .options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
            .plugin(GoogleGenAIPlugin.create(geminiApiKey))
            .plugin(qdrantPlugin)
            .plugin(jetty)
            .build();

    String action = "qdrant/" + collection;

    Flow<Void, String, Void> indexDocumentsFlow =
        genkit.defineFlow(
            "indexDocuments",
            Void.class,
            String.class,
            (ctx, input) -> {
              List<Document> documents =
                  SAMPLE_DOCUMENTS.stream().map(Document::fromText).collect(Collectors.toList());
              genkit.index(action, documents);
              return "Successfully indexed " + documents.size() + " documents";
            });

    @SuppressWarnings("unchecked")
    Flow<String, List<String>, Void> retrieveDocumentsFlow =
        genkit.defineFlow(
            "retrieveDocuments",
            String.class,
            (Class<List<String>>) (Class<?>) List.class,
            (ctx, query) -> {
              List<Document> docs = genkit.retrieve(action, query);
              return docs.stream().map(Document::text).collect(Collectors.toList());
            });

    Flow<String, String, Void> ragQueryFlow =
        genkit.defineFlow(
            "ragQuery",
            String.class,
            String.class,
            (ctx, question) -> {
              List<Document> docs = genkit.retrieve(action, question);
              ModelResponse response =
                  genkit.generate(
                      GenerateOptions.builder()
                          .model("googleai/gemini-2.5-flash")
                          .system(RAG_SYSTEM_PROMPT)
                          .prompt(question)
                          .docs(docs)
                          .config(GenerationConfig.builder().temperature(0.3).build())
                          .build());
              return response.getText();
            });

    logger.info(
        "Genkit Qdrant RAG Sample started. Flows: indexDocuments, retrieveDocuments, ragQuery");
    logger.info("Dev UI: http://localhost:4000  |  Reflection: http://localhost:3100");

    try {
      jetty.start();
    } catch (Exception e) {
      logger.error("Failed to start Jetty server", e);
      System.exit(1);
    }
  }

  private static String getEnv(Dotenv dotenv, String name) {
    String value = dotenv.get(name);
    return (value != null && !value.isBlank()) ? value : System.getenv(name);
  }

  private static String getEnvOrDefault(Dotenv dotenv, String name, String defaultValue) {
    String value = getEnv(dotenv, name);
    return (value != null && !value.isBlank()) ? value : defaultValue;
  }
}
