package org.llm4s.rag.permissions

import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord }

/**
 * Top-level search index with permission-based filtering.
 *
 * SearchIndex is the main entry point for permission-aware RAG operations.
 * It combines:
 * - Collection hierarchy management
 * - User/group principal mapping
 * - Permission-filtered vector search
 * - Document ingestion with access control
 *
 * Query flow:
 * 1. Resolve accessible collection IDs based on pattern + user authorization
 * 2. Perform vector search within those collections
 * 3. Apply document-level permission filtering (readable_by)
 * 4. Return permission-filtered results
 */
trait SearchIndex {

  /** Access to principal (user/group) management */
  def principals: PrincipalStore

  /** Access to collection management */
  def collections: CollectionStore

  /**
   * Main query method with permission filtering.
   *
   * Performs a two-stage permission filter:
   * 1. Collection-level: Only search collections the user can access
   * 2. Document-level: Only return documents the user can read
   *
   * @param auth User authorization context
   * @param collectionPattern Pattern to filter collections (e.g., confluence/STAR)
   * @param queryVector Embedding vector for semantic search
   * @param topK Maximum number of results to return
   * @param additionalFilter Optional additional metadata filter
   * @return Permission-filtered search results
   */
  def query(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    queryVector: Array[Float],
    topK: Int = 10,
    additionalFilter: Option[MetadataFilter] = None
  ): Result[Seq[ScoredRecord]]

  /**
   * Query with a text query (for hybrid search).
   *
   * @param auth User authorization context
   * @param collectionPattern Pattern to filter collections
   * @param queryVector Embedding vector for semantic search
   * @param queryText Text for keyword search (optional)
   * @param topK Maximum number of results
   * @param additionalFilter Optional additional metadata filter
   * @return Permission-filtered search results
   */
  def queryHybrid(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    queryVector: Array[Float],
    queryText: Option[String],
    topK: Int = 10,
    additionalFilter: Option[MetadataFilter] = None
  ): Result[Seq[ScoredRecord]] = {
    val _ = queryText // Future: use for hybrid search
    query(auth, collectionPattern, queryVector, topK, additionalFilter)
  }

  /**
   * Ingest a document into a specific collection.
   *
   * The collection must:
   * - Exist (call ensureExists first if needed)
   * - Be a leaf collection (can contain documents)
   *
   * @param collectionPath Target collection path
   * @param documentId Unique document identifier
   * @param chunks Pre-chunked content with embeddings
   * @param metadata Document metadata
   * @param readableBy Document-level permission override (empty = inherit from collection)
   * @return Number of chunks indexed
   */
  def ingest(
    collectionPath: CollectionPath,
    documentId: String,
    chunks: Seq[ChunkWithEmbedding],
    metadata: Map[String, String] = Map.empty,
    readableBy: Set[PrincipalId] = Set.empty
  ): Result[Int]

  /**
   * Delete a document from a collection.
   *
   * Removes all chunks associated with the document.
   *
   * @param collectionPath The collection containing the document
   * @param documentId The document identifier
   * @return Number of chunks deleted
   */
  def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long]

  /**
   * Delete all documents in a collection.
   *
   * Does not delete the collection itself.
   *
   * @param collectionPath The collection to clear
   * @return Number of chunks deleted
   */
  def clearCollection(collectionPath: CollectionPath): Result[Long]

  /**
   * Get statistics for a collection.
   *
   * @param collectionPath The collection path
   * @return Collection statistics
   */
  def stats(collectionPath: CollectionPath): Result[CollectionStats] =
    collections.stats(collectionPath)

  /**
   * Initialize the permission schema.
   *
   * Creates necessary tables and indexes if they don't exist.
   * Safe to call multiple times (idempotent).
   *
   * @return Success or error
   */
  def initializeSchema(): Result[Unit]

  /**
   * Drop the permission schema.
   *
   * WARNING: This is destructive and will delete all permission data.
   * Only use for testing or complete reset.
   *
   * @return Success or error
   */
  def dropSchema(): Result[Unit]

  /**
   * Close the search index and release resources.
   */
  def close(): Unit

  /**
   * Get the PostgreSQL configuration if this is a PostgreSQL-backed index.
   *
   * This is used by RAGConfig to automatically configure the underlying
   * vector store when a SearchIndex is provided.
   *
   * @return Some(config) if PostgreSQL-backed, None otherwise
   */
  def pgConfig: Option[SearchIndex.PgConfig] = None
}

/**
 * Factory for creating SearchIndex instances.
 */
object SearchIndex {

  /**
   * Configuration for creating a PostgreSQL-backed SearchIndex.
   *
   * @param host Database host
   * @param port Database port
   * @param database Database name
   * @param user Database user
   * @param password Database password
   * @param vectorTableName Name of the vectors table
   * @param keywordTableName Name of the keyword index table
   * @param maxPoolSize Maximum connection pool size
   */
  final case class PgConfig(
    host: String = "localhost",
    port: Int = 5432,
    database: String = "postgres",
    user: String = "postgres",
    password: String = "",
    vectorTableName: String = "vectors",
    keywordTableName: String = "documents",
    maxPoolSize: Int = 10
  ) {
    def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
  }
}
