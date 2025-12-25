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

package com.google.genkit.samples.weaviate;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.genkit.Genkit;
import com.google.genkit.GenkitOptions;
import com.google.genkit.ai.*;
import com.google.genkit.core.Flow;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;
import com.google.genkit.plugins.jetty.JettyPlugin;
import com.google.genkit.plugins.jetty.JettyPluginOptions;
import com.google.genkit.plugins.weaviate.WeaviateCollectionConfig;
import com.google.genkit.plugins.weaviate.WeaviatePlugin;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Sample application demonstrating Weaviate vector search with Genkit.
 *
 * <p>
 * This sample shows how to:
 * <ul>
 * <li>Configure Weaviate plugin with collection settings</li>
 * <li>Index documents with embeddings</li>
 * <li>Retrieve relevant documents using vector similarity search</li>
 * <li>Build a RAG (Retrieval Augmented Generation) workflow</li>
 * </ul>
 *
 * <p>
 * Prerequisites:
 * <ul>
 * <li>Set GEMINI_API_KEY environment variable</li>
 * <li>Running Weaviate instance (local or cloud)</li>
 * <li>Optional: WEAVIATE_HOST, WEAVIATE_PORT, WEAVIATE_API_KEY</li>
 * </ul>
 */
public class WeaviateRAGSample {

  private static final Logger logger = LoggerFactory.getLogger(WeaviateRAGSample.class);

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
    // Load .env file (falls back to system environment variables)
    Dotenv dotenv = Dotenv.configure().ignoreIfMissing().systemProperties().load();

    String apiKey = getEnv(dotenv, "GEMINI_API_KEY");
    if (apiKey == null) {
      logger.error("Please set GEMINI_API_KEY in .env file or environment variable");
      System.exit(1);
    }

    // Weaviate configuration
    String weaviateHost = getEnvOrDefault(dotenv, "WEAVIATE_HOST", "localhost");
    int weaviatePort = Integer.parseInt(getEnvOrDefault(dotenv, "WEAVIATE_PORT", "8080"));
    int weaviateGrpcPort = Integer.parseInt(getEnvOrDefault(dotenv, "WEAVIATE_GRPC_PORT", "50051"));
    String weaviateApiKey = getEnv(dotenv, "WEAVIATE_API_KEY");

    logger.info("Starting Weaviate RAG Sample");
    logger.info("Weaviate Host: {}:{}", weaviateHost, weaviatePort);

    // Create Jetty plugin
    JettyPlugin jetty = new JettyPlugin(JettyPluginOptions.builder().port(8081).build());

    // Build Weaviate plugin
    WeaviatePlugin.Builder weaviateBuilder;
    if (weaviateApiKey != null && !weaviateApiKey.isBlank()) {
      // Cloud configuration with API key
      weaviateBuilder = WeaviatePlugin.builder().host(weaviateHost).apiKey(weaviateApiKey);
    } else {
      // Local configuration
      weaviateBuilder = WeaviatePlugin.local(weaviateHost).port(weaviatePort).grpcPort(weaviateGrpcPort);
    }

    // Add collection configuration
    weaviateBuilder.addCollection(
        WeaviateCollectionConfig.builder().name("Films").embedderName("googleai/text-embedding-004")
            .vectorDimension(768).distanceMeasure(WeaviateCollectionConfig.DistanceMeasure.COSINE)
            .createCollectionIfMissing(true).build());

    // Build Genkit with Weaviate plugin
    Genkit genkit = Genkit.builder().options(GenkitOptions.builder().devMode(true).reflectionPort(3100).build())
        .plugin(GoogleGenAIPlugin.create(apiKey)).plugin(weaviateBuilder.build()).plugin(jetty).build();

    // Define indexing flow
    Flow<Void, String, Void> indexDocumentsFlow = genkit.defineFlow("indexDocuments", Void.class, String.class,
        (ctx, input) -> {
          logger.info("Indexing {} film documents...", SAMPLE_DOCUMENTS.size());

          List<Document> documents = SAMPLE_DOCUMENTS.stream().map(Document::fromText)
              .collect(Collectors.toList());

          genkit.index("weaviate/Films", documents);

          return "Successfully indexed " + documents.size() + " documents";
        });

    // Define retrieval flow
    @SuppressWarnings("unchecked")
    Flow<String, List<String>, Void> retrieveDocumentsFlow = genkit.defineFlow("retrieveDocuments", String.class,
        (Class<List<String>>) (Class<?>) List.class, (ctx, query) -> {
          if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Please provide a search query. Example: 'sci-fi movies' or 'Christopher Nolan'");
          }

          logger.info("Retrieving documents for query: {}", query);

          List<Document> docs = genkit.retrieve("weaviate/Films", query);

          return docs.stream().map(Document::text).collect(Collectors.toList());
        });

    // Define RAG query flow
    Flow<String, String, Void> ragQueryFlow = genkit.defineFlow("ragQuery", String.class, String.class,
        (ctx, question) -> {
          if (question == null || question.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Please provide a question. Example: 'What Christopher Nolan films are mentioned?'");
          }

          logger.info("Processing RAG query: {}", question);

          // Retrieve relevant documents
          List<Document> docs = genkit.retrieve("weaviate/Films", question);

          logger.info("Retrieved {} documents for context", docs.size());

          // Generate answer using context
          ModelResponse response = genkit.generate(GenerateOptions.builder()
              .model("googleai/gemini-2.0-flash").system(RAG_SYSTEM_PROMPT).prompt(question).docs(docs)
              .config(GenerationConfig.builder().temperature(0.3).build()).build());

          return response.getText();
        });

    // Start the server
    logger.info("=".repeat(60));
    logger.info("Genkit Weaviate RAG Sample Started");
    logger.info("=".repeat(60));
    logger.info("");
    logger.info("Available flows:");
    logger.info("  - indexDocuments: Index sample film documents");
    logger.info("  - retrieveDocuments: Retrieve films matching a query");
    logger.info("  - ragQuery: Answer questions about films using RAG");
    logger.info("");
    logger.info("Example usage:");
    logger.info(
        "  curl -X POST http://localhost:4000/api/flows/indexDocuments -H 'Content-Type: application/json' -d '{}'");
    logger.info(
        "  curl -X POST http://localhost:4000/api/flows/retrieveDocuments -H 'Content-Type: application/json' -d '{\"data\": \"sci-fi movie\"}'");
    logger.info(
        "  curl -X POST http://localhost:4000/api/flows/ragQuery -H 'Content-Type: application/json' -d '{\"data\": \"What Christopher Nolan films are mentioned?\"}'");
    logger.info("=".repeat(60));

    // Keep the application running
    try {
      jetty.start();
    } catch (Exception e) {
      logger.error("Failed to start Jetty server", e);
      System.exit(1);
    }
  }

  /**
   * Get environment variable from dotenv or system environment.
   */
  private static String getEnv(Dotenv dotenv, String name) {
    String value = dotenv.get(name);
    return (value != null && !value.isBlank()) ? value : System.getenv(name);
  }

  /**
   * Get environment variable with default fallback.
   */
  private static String getEnvOrDefault(Dotenv dotenv, String name, String defaultValue) {
    String value = getEnv(dotenv, name);
    return (value != null && !value.isBlank()) ? value : defaultValue;
  }
}
