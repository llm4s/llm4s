package org.llm4s.vectorstore

import org.llm4s.types.Result

/**
 * Low-level vector storage abstraction for RAG and semantic search.
 *
 * VectorStore provides a backend-agnostic interface for storing and
 * searching vector embeddings. Implementations can be SQLite, pgvector,
 * Qdrant, Milvus, Pinecone, or any other vector database.
 *
 * This is the foundation layer - higher-level abstractions like
 * MemoryStore can build on top of VectorStore for domain-specific
 * functionality.
 *
 * Key design principles:
 * - Backend-agnostic: Same interface for all vector databases
 * - Minimal API: Focus on core vector operations
 * - Composable: Can be wrapped with additional functionality
 * - Type-safe: Uses Result[A] for error handling
 */
trait VectorStore {

  /**
   * Store a vector record.
   *
   * If a record with the same ID exists, it will be replaced.
   *
   * @param record The record to store
   * @return Success or error
   */
  def upsert(record: VectorRecord): Result[Unit]

  /**
   * Store multiple vector records in a batch.
   *
   * More efficient than individual upserts for bulk operations.
   *
   * @param records The records to store
   * @return Success or error
   */
  def upsertBatch(records: Seq[VectorRecord]): Result[Unit]

  /**
   * Search for similar vectors using cosine similarity.
   *
   * @param queryVector The query embedding
   * @param topK Number of results to return
   * @param filter Optional metadata filter
   * @return Matching records with similarity scores
   */
  def search(
    queryVector: Array[Float],
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  ): Result[Seq[ScoredRecord]]

  /**
   * Retrieve a record by its ID.
   *
   * @param id The record identifier
   * @return The record if found
   */
  def get(id: String): Result[Option[VectorRecord]]

  /**
   * Retrieve multiple records by their IDs.
   *
   * @param ids The record identifiers
   * @return The found records (missing IDs are silently skipped)
   */
  def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]]

  /**
   * Delete a record by its ID.
   *
   * @param id The record identifier
   * @return Success or error
   */
  def delete(id: String): Result[Unit]

  /**
   * Delete multiple records by their IDs.
   *
   * @param ids The record identifiers
   * @return Success or error
   */
  def deleteBatch(ids: Seq[String]): Result[Unit]

  /**
   * Delete all records with IDs starting with the given prefix.
   *
   * @param prefix The ID prefix to match
   * @return Number of records deleted
   */
  def deleteByPrefix(prefix: String): Result[Long]

  /**
   * Delete all records matching a metadata filter.
   *
   * @param filter The metadata filter
   * @return Number of records deleted
   */
  def deleteByFilter(filter: MetadataFilter): Result[Long]

  /**
   * Count total records in the store.
   *
   * @param filter Optional metadata filter
   * @return Record count
   */
  def count(filter: Option[MetadataFilter] = None): Result[Long]

  /**
   * List records with pagination.
   *
   * @param limit Maximum records to return
   * @param offset Number of records to skip
   * @param filter Optional metadata filter
   * @return Records in insertion order
   */
  def list(
    limit: Int = 100,
    offset: Int = 0,
    filter: Option[MetadataFilter] = None
  ): Result[Seq[VectorRecord]]

  /**
   * Clear all records from the store.
   *
   * @return Success or error
   */
  def clear(): Result[Unit]

  /**
   * Get statistics about the vector store.
   *
   * @return Store statistics
   */
  def stats(): Result[VectorStoreStats]

  /**
   * Close the store and release resources.
   */
  def close(): Unit
}

/**
 * A record stored in the vector store.
 *
 * @param id Unique identifier for the record
 * @param embedding The vector embedding
 * @param content Optional text content (for display/debugging)
 * @param metadata Key-value metadata for filtering
 */
final case class VectorRecord(
  id: String,
  embedding: Array[Float],
  content: Option[String] = None,
  metadata: Map[String, String] = Map.empty
) {

  /**
   * Get a metadata value.
   */
  def getMetadata(key: String): Option[String] = metadata.get(key)

  /**
   * Add or update metadata.
   */
  def withMetadata(key: String, value: String): VectorRecord =
    copy(metadata = metadata + (key -> value))

  /**
   * Add multiple metadata entries.
   */
  def withMetadata(entries: Map[String, String]): VectorRecord =
    copy(metadata = metadata ++ entries)

  /**
   * Get embedding dimensionality.
   */
  def dimensions: Int = embedding.length

  override def equals(other: Any): Boolean = other match {
    case that: VectorRecord =>
      id == that.id &&
      java.util.Arrays.equals(embedding, that.embedding) &&
      content == that.content &&
      metadata == that.metadata
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(id, java.util.Arrays.hashCode(embedding), content, metadata)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

object VectorRecord {

  /**
   * Create a record with auto-generated ID.
   */
  def create(
    embedding: Array[Float],
    content: Option[String] = None,
    metadata: Map[String, String] = Map.empty
  ): VectorRecord =
    VectorRecord(
      id = java.util.UUID.randomUUID().toString,
      embedding = embedding,
      content = content,
      metadata = metadata
    )
}

/**
 * A record with its similarity score from a search.
 *
 * @param record The vector record
 * @param score Similarity score (0.0 to 1.0, higher is more similar)
 */
final case class ScoredRecord(
  record: VectorRecord,
  score: Double
) {
  require(score >= 0.0 && score <= 1.0, s"Score must be between 0.0 and 1.0, got: $score")
}

object ScoredRecord {

  /**
   * Ordering by score (descending).
   */
  implicit val byScoreDescending: Ordering[ScoredRecord] =
    Ordering.by[ScoredRecord, Double](_.score).reverse
}

/**
 * Filter for metadata-based queries.
 */
sealed trait MetadataFilter

object MetadataFilter {

  /**
   * Match all records.
   */
  case object All extends MetadataFilter

  /**
   * Match records where metadata key equals value.
   */
  final case class Equals(key: String, value: String) extends MetadataFilter

  /**
   * Match records where metadata key contains substring.
   */
  final case class Contains(key: String, substring: String) extends MetadataFilter

  /**
   * Match records that have a metadata key.
   */
  final case class HasKey(key: String) extends MetadataFilter

  /**
   * Match records where metadata key is in a set of values.
   */
  final case class In(key: String, values: Set[String]) extends MetadataFilter

  /**
   * Combine filters with AND logic.
   */
  final case class And(left: MetadataFilter, right: MetadataFilter) extends MetadataFilter

  /**
   * Combine filters with OR logic.
   */
  final case class Or(left: MetadataFilter, right: MetadataFilter) extends MetadataFilter

  /**
   * Negate a filter.
   */
  final case class Not(filter: MetadataFilter) extends MetadataFilter

  /**
   * DSL for building filters.
   */
  implicit class FilterOps(val filter: MetadataFilter) extends AnyVal {
    def and(other: MetadataFilter): MetadataFilter = And(filter, other)
    def or(other: MetadataFilter): MetadataFilter  = Or(filter, other)
    def unary_! : MetadataFilter                   = Not(filter)
  }
}

/**
 * Statistics about a vector store.
 *
 * @param totalRecords Total number of records
 * @param dimensions Set of embedding dimensions in the store
 * @param sizeBytes Approximate size in bytes (if available)
 */
final case class VectorStoreStats(
  totalRecords: Long,
  dimensions: Set[Int] = Set.empty,
  sizeBytes: Option[Long] = None
) {

  /**
   * Check if store is empty.
   */
  def isEmpty: Boolean = totalRecords == 0

  /**
   * Human-readable size.
   */
  def formattedSize: String = sizeBytes match {
    case Some(bytes) if bytes >= 1024 * 1024 * 1024 => f"${bytes / (1024.0 * 1024 * 1024)}%.2f GB"
    case Some(bytes) if bytes >= 1024 * 1024        => f"${bytes / (1024.0 * 1024)}%.2f MB"
    case Some(bytes) if bytes >= 1024               => f"${bytes / 1024.0}%.2f KB"
    case Some(bytes)                                => s"$bytes bytes"
    case None                                       => "unknown"
  }
}
