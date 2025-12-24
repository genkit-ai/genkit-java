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

package com.google.genkit.samples.firebase;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.*;
import com.google.genkit.core.Flow;
import com.google.genkit.plugins.firebase.FirebasePlugin;
import com.google.genkit.plugins.firebase.retriever.FirestoreRetrieverConfig;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;

/**
 * Sample application demonstrating Firestore vector search with Genkit.
 * 
 * <p>
 * This sample shows how to:
 * <ul>
 * <li>Configure Firebase plugin with Firestore retriever</li>
 * <li>Index documents with embeddings</li>
 * <li>Retrieve relevant documents using vector similarity search</li>
 * <li>Build a RAG (Retrieval Augmented Generation) workflow</li>
 * </ul>
 * 
 * <p>
 * Prerequisites:
 * <ul>
 * <li>Set GEMINI_API_KEY environment variable</li>
 * <li>Set GCLOUD_PROJECT environment variable</li>
 * <li>Configure Google Cloud credentials</li>
 * <li>Create a Firestore vector index on the 'embedding' field</li>
 * </ul>
 */
public class FirestoreRAGSample {

  private static final Logger logger = LoggerFactory.getLogger(FirestoreRAGSample.class);

  // Sample documents about famous films
  private static final List<String> SAMPLE_DOCUMENTS = List.of(
      "The Godfather is a 1972 crime film directed by Francis Ford Coppola. It follows the powerful Corleone crime family.",
      "The Dark Knight is a 2008 superhero film directed by Christopher Nolan featuring Batman against the Joker.",
      "Pulp Fiction is a 1994 crime film directed by Quentin Tarantino known for its nonlinear narrative.",
      "Schindler's List is a 1993 historical drama directed by Steven Spielberg about the Holocaust.",
      "Inception is a 2010 sci-fi film directed by Christopher Nolan about dream infiltration.",
      "The Matrix is a 1999 sci-fi film directed by the Wachowskis exploring simulated reality.",
      "Fight Club is a 1999 film directed by David Fincher about an underground fight club.",
      "Forrest Gump is a 1994 drama directed by Robert Zemeckis about a man's extraordinary life.",
      "Star Wars is a 1977 sci-fi film directed by George Lucas set in a galaxy far, far away.",
      "The Shawshank Redemption is a 1994 drama about hope and friendship in a prison.");

  /**
   * System prompt for RAG queries.
   */
  private static final String RAG_SYSTEM_PROMPT = """
      You are a helpful assistant that answers questions based on the provided context documents.

      Please provide a helpful answer based only on the context provided. If the context doesn't contain
      enough information to answer the question, say so.
      """;

  public static void main(String[] args) {
    String apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null) {
      logger.error("Please set GEMINI_API_KEY environment variable");
      System.exit(1);
    }

    String projectId = System.getenv("GCLOUD_PROJECT");
    if (projectId == null) {
      projectId = System.getenv("GOOGLE_CLOUD_PROJECT");
    }
    if (projectId == null) {
      logger.error("Please set GCLOUD_PROJECT or GOOGLE_CLOUD_PROJECT environment variable");
      System.exit(1);
    }

    logger.info("Starting Firestore RAG Sample");
    logger.info("Project ID: {}", projectId);

    // Create Jetty plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8080).build());

    // Build Genkit with Firebase plugin
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(GoogleGenAIPlugin.create(apiKey))
        .plugin(FirebasePlugin.builder().projectId(projectId).enableTelemetry(true).forceDevExport(true) // Enable
            // telemetry
            // in
            // dev
            // mode
            .addRetriever(FirestoreRetrieverConfig.builder().name("films").collection("films")
                .embedderName("googleai/text-embedding-004").vectorField("embedding")
                .contentField("content")
                .distanceMeasure(FirestoreRetrieverConfig.DistanceMeasure.COSINE).defaultLimit(5)
                .createDatabaseIfNotExists(true) // Auto-create database if it doesn't exist
                .createVectorIndexIfNotExists(true) // Auto-create vector index if it doesn't exist
                .embedderDimension(768) // text-embedding-004 outputs 768 dimensions
                .build())
            .build())
        .plugin(jetty).build();

    // Define indexing flow
    Flow<Void, String, Void> indexFilmsFlow = genkit.defineFlow("indexFilms", Void.class, String.class,
        (ctx, input) -> {
          logger.info("Indexing {} film documents...", SAMPLE_DOCUMENTS.size());

          List<Document> documents = SAMPLE_DOCUMENTS.stream().map(Document::fromText)
              .collect(Collectors.toList());

          genkit.index("firebase/films", documents);

          return "Successfully indexed " + documents.size() + " documents";
        });

    // Define retrieval flow
    @SuppressWarnings("unchecked")
    Flow<String, List<String>, Void> retrieveFilmsFlow = genkit.defineFlow("retrieveFilms", String.class,
        (Class<List<String>>) (Class<?>) List.class, (ctx, query) -> {
          // Validate input
          if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Please provide a search query. Example: 'sci-fi movies' or 'Christopher Nolan'");
          }

          logger.info("Retrieving documents for query: {}", query);

          List<Document> docs = genkit.retrieve("firebase/films", query);

          return docs.stream().map(Document::text).collect(Collectors.toList());
        });

    // Define RAG query flow
    Flow<String, String, Void> ragQueryFlow = genkit.defineFlow("ragQuery", String.class, String.class,
        (ctx, question) -> {
          // Validate input
          if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Please provide a question. Example: 'What Christopher Nolan films are mentioned?'");
          }

          logger.info("Processing RAG query: {}", question);

          // Retrieve relevant documents
          List<Document> docs = genkit.retrieve("firebase/films", question);

          logger.info("Retrieved {} documents for context", docs.size());

          // Generate answer using context - the .docs() method passes retrieved documents
          // as context to the model, which will be included in the prompt automatically
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("googleai/gemini-2.0-flash").system(RAG_SYSTEM_PROMPT).prompt(question).docs(docs)
              .config(GenerationConfig.builder().temperature(0.3).build()).build());

          return response.getText();
        });

    // Start the server
    logger.info("=".repeat(60));
    logger.info("Genkit Firebase RAG Sample Started");
    logger.info("=".repeat(60));
    logger.info("");
    logger.info("Available flows:");
    logger.info("  - indexFilms: Index sample film documents");
    logger.info("  - retrieveFilms: Retrieve films matching a query");
    logger.info("  - ragQuery: Answer questions about films using RAG");
    logger.info("");
    logger.info("Example usage:");
    logger.info(
        "  curl -X POST http://localhost:4000/api/flows/indexFilms -H 'Content-Type: application/json' -d '{}'");
    logger.info(
        "  curl -X POST http://localhost:4000/api/flows/retrieveFilms -H 'Content-Type: application/json' -d '{\"data\": \"sci-fi movie\"}'");
    logger.info(
        "  curl -X POST http://localhost:4000/api/flows/ragQuery -H 'Content-Type: application/json' -d '{\"data\": \"What Christopher Nolan films are mentioned?\"}'");
    logger.info("=".repeat(60));

    // Start the server and block - keeps the application running
    try {
      jetty.start();
    } catch (Exception e) {
      logger.error("Failed to start Jetty server", e);
      System.exit(1);
    }
  }
}
