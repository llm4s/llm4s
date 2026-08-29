package org.llm4s.vectorstore

import org.llm4s.types.Result

/**
 * Result from a keyword search operation.
 *
 * @param id Document ID
 * @param content Document content
 * @param score BM25 relevance score (higher is more relevant)
 * @param metadata Document metadata
 * @param highlights Optional highlighted snippets showing match context
 */
final case class KeywordSearchResult(
  id: String,
  content: String,
  score: Double,
  metadata: Map[String, String] = Map.empty,
  highlights: Seq[String] = Seq.empty
)

/**
 * Document to be indexed for keyword search.
 *
 * @param id Unique document identifier
 * @param content Text content to index
 * @param metadata Additional metadata (not indexed, but returned in results)
 */
final case class KeywordDocument(
  id: String,
  content: String,
  metadata: Map[String, String] = Map.empty
)

/**
 * Abstract interface for keyword-based document indexing and search.
 *
 * Implementations use BM25 (Best Matching 25) scoring for relevance ranking.
 * BM25 considers term frequency, document length, and inverse document frequency.
 *
 * This trait is designed to complement VectorStore for hybrid search scenarios:
 * - VectorStore: Semantic similarity via embeddings
 * - KeywordIndex: Exact/partial term matching via BM25
 *
 * The two can be combined using score fusion (RRF or weighted) for hybrid search.
 */
trait KeywordIndex {

  /**
   * Index a single document.
   *
   * @param doc Document to index
   * @return Unit on success, or error
   */
  def index(doc: KeywordDocument): Result[Unit]

  /**
   * Index multiple documents in batch.
   *
   * @param docs Documents to index
   * @return Unit on success, or error
   */
  def indexBatch(docs: Seq[KeywordDocument]): Result[Unit]

  /**
   * Search for documents matching a query.
   *
   * Uses BM25 scoring for relevance ranking.
   *
   * @param query Search query (supports operators depending on implementation)
   * @param topK Maximum number of results to return
   * @param filter Optional metadata filter
   * @return Ranked search results, or error
   */
  def search(
    query: String,
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  ): Result[Seq[KeywordSearchResult]]

  /**
   * Search with highlighted snippets.
   *
   * @param query Search query
   * @param topK Maximum number of results
   * @param snippetLength Target length for highlight snippets
   * @param filter Optional metadata filter
   * @return Results with highlighted matches
   */
  def searchWithHighlights(
    query: String,
    topK: Int = 10,
    snippetLength: Int = 100,
    filter: Option[MetadataFilter] = None
  ): Result[Seq[KeywordSearchResult]]

  /**
   * Get a document by ID.
   *
   * @param id Document ID
   * @return Document if found, None if not found, or error
   */
  def get(id: String): Result[Option[KeywordDocument]]

  /**
   * Delete a document by ID.
   *
   * @param id Document ID
   * @return Unit on success, or error
   */
  def delete(id: String): Result[Unit]

  /**
   * Delete multiple documents.
   *
   * @param ids Document IDs to delete
   * @return Unit on success, or error
   */
  def deleteBatch(ids: Seq[String]): Result[Unit]

  /**
   * Delete all documents with IDs starting with the given prefix.
   *
   * @param prefix The ID prefix to match
   * @return Number of documents deleted
   */
  def deleteByPrefix(prefix: String): Result[Long]

  /**
   * Update a document (re-index with new content).
   *
   * @param doc Updated document
   * @return Unit on success, or error
   */
  def update(doc: KeywordDocument): Result[Unit] =
    for {
      _ <- delete(doc.id)
      _ <- index(doc)
    } yield ()

  /**
   * Count total indexed documents.
   *
   * @return Document count
   */
  def count(): Result[Long]

  /**
   * Clear all indexed documents.
   *
   * @return Unit on success, or error
   */
  def clear(): Result[Unit]

  /**
   * Close the index and release resources.
   */
  def close(): Unit

  /**
   * Get index statistics.
   *
   * @return Index statistics
   */
  def stats(): Result[KeywordIndexStats]
}

/**
 * Statistics about the keyword index.
 *
 * @param totalDocuments Number of indexed documents
 * @param totalTokens Approximate total token count across all documents
 * @param avgDocumentLength Average document length in tokens
 * @param indexSizeBytes Approximate index size on disk (if applicable)
 */
final case class KeywordIndexStats(
  totalDocuments: Long,
  totalTokens: Option[Long] = None,
  avgDocumentLength: Option[Double] = None,
  indexSizeBytes: Option[Long] = None
)

/**
 * Factory for creating KeywordIndex instances.
 */
object KeywordIndex {

  /**
   * Create an in-memory SQLite-based keyword index.
   *
   * @return New keyword index
   */
  def inMemory(): Result[KeywordIndex] =
    SQLiteKeywordIndex.inMemory()

  /**
   * Create a file-based SQLite keyword index.
   *
   * @param path Path to database file
   * @return New keyword index
   */
  def sqlite(path: String): Result[KeywordIndex] =
    SQLiteKeywordIndex(path)

  /**
   * Create a PostgreSQL-based keyword index.
   *
   * Uses PostgreSQL native full-text search with tsvector/tsquery.
   * Provides BM25-like scoring via ts_rank_cd (cover density ranking).
   *
   * @param config PostgreSQL configuration
   * @return New keyword index
   */
  def postgres(config: PgKeywordIndex.Config): Result[KeywordIndex] =
    PgKeywordIndex(config)

  /**
   * Create a PostgreSQL-based keyword index from connection string.
   *
   * @param connectionString JDBC connection string (jdbc:postgresql://...)
   * @param user Database user
   * @param password Database password
   * @param tableName Base table name (creates {tableName}_keyword table)
   * @return New keyword index
   */
  def postgres(
    connectionString: String,
    user: String = "postgres",
    password: String = "",
    tableName: String = "documents"
  ): Result[KeywordIndex] =
    PgKeywordIndex(connectionString, user, password, tableName)

  /**
   * Create a PostgreSQL-based keyword index with default local settings.
   *
   * Connects to localhost:5432/postgres with user postgres.
   *
   * @param tableName Base table name
   * @return New keyword index
   */
  def postgresLocal(tableName: String = "documents"): Result[KeywordIndex] =
    PgKeywordIndex.local(tableName)
}
