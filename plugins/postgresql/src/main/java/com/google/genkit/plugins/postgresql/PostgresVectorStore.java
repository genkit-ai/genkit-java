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

package com.google.genkit.plugins.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genkit.ai.*;
import com.google.genkit.core.ActionContext;
import com.pgvector.PGvector;

/**
 * PostgreSQL vector store implementation using pgvector extension.
 *
 * <p>
 * This class provides indexing and retrieval of documents using PostgreSQL with
 * the pgvector extension for vector similarity search.
 */
public class PostgresVectorStore {

  private static final Logger logger = LoggerFactory.getLogger(PostgresVectorStore.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final DataSource dataSource;
  private final PostgresTableConfig config;
  private final Embedder embedder;
  private boolean initialized = false;

  /**
   * Creates a new PostgresVectorStore.
   *
   * @param dataSource
   *            the PostgreSQL data source
   * @param config
   *            the table configuration
   * @param embedder
   *            the embedder for generating vectors
   */
  public PostgresVectorStore(DataSource dataSource, PostgresTableConfig config, Embedder embedder) {
    this.dataSource = dataSource;
    this.config = config;
    this.embedder = embedder;
  }

  /**
   * Initializes the vector store by creating the table and index if needed.
   */
  public synchronized void initialize() throws SQLException {
    if (initialized) {
      return;
    }

    try (Connection conn = dataSource.getConnection()) {
      // Register pgvector type
      PGvector.addVectorType(conn);

      // Ensure pgvector extension is enabled
      ensureExtension(conn);

      // Create table if needed
      if (config.isCreateTableIfNotExists()) {
        ensureTable(conn);
      }

      // Create index if needed
      if (config.isCreateIndexIfNotExists()) {
        ensureIndex(conn);
      }

      initialized = true;
      logger.info("PostgreSQL vector store initialized for table: {}", config.getTableName());
    }
  }

  private void ensureExtension(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
      logger.debug("pgvector extension enabled");
    }
  }

  private void ensureTable(Connection conn) throws SQLException {
    String createTableSql = String.format("""
        CREATE TABLE IF NOT EXISTS %s (
            %s UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            %s TEXT,
            %s vector(%d),
            %s JSONB,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        )
        """, escapeIdentifier(config.getTableName()), escapeIdentifier(config.getIdColumn()),
        escapeIdentifier(config.getContentColumn()), escapeIdentifier(config.getEmbeddingColumn()),
        config.getVectorDimension(), escapeIdentifier(config.getMetadataColumn()));

    try (Statement stmt = conn.createStatement()) {
      stmt.execute(createTableSql);
      logger.debug("Table {} created or already exists", config.getTableName());
    }
  }

  private void ensureIndex(Connection conn) throws SQLException {
    String indexName = config.getTableName() + "_embedding_idx";
    String createIndexSql = String.format("""
        CREATE INDEX IF NOT EXISTS %s ON %s
        USING ivfflat (%s %s)
        WITH (lists = %d)
        """, escapeIdentifier(indexName), escapeIdentifier(config.getTableName()),
        escapeIdentifier(config.getEmbeddingColumn()), config.getDistanceStrategy().getIndexOpsClass(),
        config.getIndexLists());

    try (Statement stmt = conn.createStatement()) {
      stmt.execute(createIndexSql);
      logger.debug("Index {} created or already exists", indexName);
    }
  }

  /**
   * Retrieves documents similar to the query.
   *
   * @param context
   *            the action context
   * @param request
   *            the retriever request
   * @return the retriever response with matching documents
   */
  public RetrieverResponse retrieve(ActionContext context, RetrieverRequest request) {
    try {
      ensureInitialized();

      // Get query document
      Document queryDoc = request.getQuery();
      if (queryDoc == null) {
        throw new RuntimeException("Query document is required");
      }
      String queryText = queryDoc.text();
      if (queryText == null || queryText.trim().isEmpty()) {
        throw new RuntimeException("Query document has no text content");
      }

      // Generate embedding for query
      List<Float> queryEmbedding = generateEmbedding(context, queryText);

      int limit = request.getOptions() != null && request.getOptions().getK() != null
          ? request.getOptions().getK()
          : 10;

      List<Document> documents = performSimilaritySearch(queryEmbedding, limit);

      logger.debug("Retrieved {} documents from table {}", documents.size(), config.getTableName());

      return new RetrieverResponse(documents);

    } catch (Exception e) {
      logger.error("Error retrieving documents: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to retrieve documents from PostgreSQL", e);
    }
  }

  /**
   * Indexes documents into the vector store.
   *
   * @param context
   *            the action context
   * @param request
   *            the indexer request
   * @return the indexer response
   */
  public IndexerResponse index(ActionContext context, IndexerRequest request) {
    try {
      ensureInitialized();

      List<Document> documents = request.getDocuments();
      if (documents == null || documents.isEmpty()) {
        logger.warn("No documents to index");
        return new IndexerResponse();
      }

      int indexed = 0;
      try (Connection conn = dataSource.getConnection()) {
        PGvector.addVectorType(conn);

        String insertSql = String.format("""
            INSERT INTO %s (%s, %s, %s, %s)
            VALUES (?, ?, ?::vector, ?::jsonb)
            ON CONFLICT (%s) DO UPDATE SET
                %s = EXCLUDED.%s,
                %s = EXCLUDED.%s,
                %s = EXCLUDED.%s
            """, escapeIdentifier(config.getTableName()), escapeIdentifier(config.getIdColumn()),
            escapeIdentifier(config.getContentColumn()), escapeIdentifier(config.getEmbeddingColumn()),
            escapeIdentifier(config.getMetadataColumn()), escapeIdentifier(config.getIdColumn()),
            escapeIdentifier(config.getContentColumn()), escapeIdentifier(config.getContentColumn()),
            escapeIdentifier(config.getEmbeddingColumn()), escapeIdentifier(config.getEmbeddingColumn()),
            escapeIdentifier(config.getMetadataColumn()), escapeIdentifier(config.getMetadataColumn()));

        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
          for (Document doc : documents) {
            String content = extractContent(doc);
            List<Float> embedding = generateEmbedding(context, content);
            Map<String, Object> metadata = buildMetadata(doc);
            String docId = getOrGenerateId(doc);

            pstmt.setObject(1, UUID.fromString(docId));
            pstmt.setString(2, content);
            pstmt.setString(3, embeddingToString(embedding));
            pstmt.setString(4, objectMapper.writeValueAsString(metadata));

            pstmt.addBatch();
            indexed++;

            // Execute batch every 100 documents
            if (indexed % 100 == 0) {
              pstmt.executeBatch();
              logger.debug("Indexed {} documents so far", indexed);
            }
          }

          // Execute remaining batch
          pstmt.executeBatch();
        }
      }

      logger.info("Indexed {} documents into table {}", indexed, config.getTableName());
      return new IndexerResponse();

    } catch (Exception e) {
      logger.error("Error indexing documents: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to index documents to PostgreSQL", e);
    }
  }

  private void ensureInitialized() throws SQLException {
    if (!initialized) {
      initialize();
    }
  }

  private List<Float> generateEmbedding(ActionContext ctx, String text) {
    Document doc = new Document(text);
    EmbedRequest embedRequest = new EmbedRequest(List.of(doc));

    EmbedResponse response = embedder.run(ctx, embedRequest);
    if (response.getEmbeddings() == null || response.getEmbeddings().isEmpty()) {
      throw new RuntimeException("Failed to generate embedding for text");
    }

    float[] embedding = response.getEmbeddings().get(0).getValues();
    return floatArrayToList(embedding);
  }

  private List<Float> floatArrayToList(float[] arr) {
    List<Float> list = new ArrayList<>(arr.length);
    for (float f : arr) {
      list.add(f);
    }
    return list;
  }

  private List<Document> performSimilaritySearch(List<Float> queryEmbedding, int limit) throws SQLException {
    List<Document> results = new ArrayList<>();

    String querySql = String.format("""
        SELECT %s, %s, %s, %s %s ?::vector AS distance
        FROM %s
        ORDER BY %s %s ?::vector
        LIMIT ?
        """, escapeIdentifier(config.getIdColumn()), escapeIdentifier(config.getContentColumn()),
        escapeIdentifier(config.getMetadataColumn()), escapeIdentifier(config.getEmbeddingColumn()),
        config.getDistanceStrategy().getOperator(), escapeIdentifier(config.getTableName()),
        escapeIdentifier(config.getEmbeddingColumn()), config.getDistanceStrategy().getOperator());

    try (Connection conn = dataSource.getConnection()) {
      PGvector.addVectorType(conn);

      try (PreparedStatement pstmt = conn.prepareStatement(querySql)) {
        String vectorStr = embeddingToString(queryEmbedding);
        pstmt.setString(1, vectorStr);
        pstmt.setString(2, vectorStr);
        pstmt.setInt(3, limit);

        try (ResultSet rs = pstmt.executeQuery()) {
          while (rs.next()) {
            Document doc = resultSetToDocument(rs);
            results.add(doc);
          }
        }
      }
    }

    return results;
  }

  private Document resultSetToDocument(ResultSet rs) throws SQLException {
    String content = rs.getString(config.getContentColumn());
    String metadataJson = rs.getString(config.getMetadataColumn());
    float distance = rs.getFloat("distance");

    Map<String, Object> metadata = new HashMap<>();
    if (metadataJson != null && !metadataJson.isEmpty()) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(metadataJson, Map.class);
        metadata.putAll(parsed);
      } catch (JsonProcessingException e) {
        logger.warn("Failed to parse metadata JSON: {}", e.getMessage());
      }
    }

    // Add distance score to metadata
    metadata.put("distance", distance);
    metadata.put("similarity", 1.0 - distance); // Convert distance to similarity

    Document doc = new Document(content);
    doc.setMetadata(metadata);
    return doc;
  }

  private String extractContent(Document doc) {
    String text = doc.text();
    return text != null ? text : "";
  }

  private Map<String, Object> buildMetadata(Document doc) {
    Map<String, Object> metadata = new HashMap<>();

    // Add document metadata
    if (doc.getMetadata() != null) {
      metadata.putAll(doc.getMetadata());
    }

    // Add config-level additional metadata
    metadata.putAll(config.getAdditionalMetadata());

    return metadata;
  }

  private String getOrGenerateId(Document doc) {
    if (doc.getMetadata() != null && doc.getMetadata().containsKey("id")) {
      Object id = doc.getMetadata().get("id");
      if (id != null) {
        String idStr = id.toString();
        // Validate or convert to UUID
        try {
          return UUID.fromString(idStr).toString();
        } catch (IllegalArgumentException e) {
          // Generate UUID from the string using name-based UUID
          return UUID.nameUUIDFromBytes(idStr.getBytes()).toString();
        }
      }
    }
    return UUID.randomUUID().toString();
  }

  private String embeddingToString(List<Float> embedding) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < embedding.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(embedding.get(i));
    }
    sb.append("]");
    return sb.toString();
  }

  private String escapeIdentifier(String identifier) {
    // Simple SQL identifier escaping - double any double quotes
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /**
   * Deletes documents by their IDs.
   *
   * @param ids
   *            the document IDs to delete
   * @return the number of documents deleted
   */
  public int deleteByIds(List<String> ids) throws SQLException {
    if (ids == null || ids.isEmpty()) {
      return 0;
    }

    ensureInitialized();

    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    String deleteSql = String.format("DELETE FROM %s WHERE %s IN (%s)", escapeIdentifier(config.getTableName()),
        escapeIdentifier(config.getIdColumn()), placeholders);

    try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {

      for (int i = 0; i < ids.size(); i++) {
        pstmt.setObject(i + 1, UUID.fromString(ids.get(i)));
      }

      int deleted = pstmt.executeUpdate();
      logger.info("Deleted {} documents from table {}", deleted, config.getTableName());
      return deleted;
    }
  }

  /**
   * Clears all documents from the table.
   *
   * @return the number of documents deleted
   */
  public int clearAll() throws SQLException {
    ensureInitialized();

    String deleteSql = String.format("DELETE FROM %s", escapeIdentifier(config.getTableName()));

    try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {

      int deleted = stmt.executeUpdate(deleteSql);
      logger.info("Cleared {} documents from table {}", deleted, config.getTableName());
      return deleted;
    }
  }

  /**
   * Gets the table configuration.
   */
  public PostgresTableConfig getConfig() {
    return config;
  }

  /**
   * Gets the data source.
   */
  public DataSource getDataSource() {
    return dataSource;
  }
}
