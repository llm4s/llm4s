package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.util.Redaction

import java.sql.{ Connection, DriverManager }
import scala.util.Try

/**
 * A memory store in a SQLite file that embeds what it stores and searches by similarity: a [[SQLiteMemoryStore]] with
 * an [[EmbeddingService]] (#3039), kept under this name for the code that builds one.
 *
 * Every memory written without an embedding is embedded first, in `embedBatch` calls of at most
 * [[VectorMemoryStore.EmbeddingBatchSize]] texts; `search` ranks by similarity under the shared score rule
 * (`org.llm4s.vectorstore.SearchScores`) and falls back to the full-text index when nothing the filter admits has an
 * embedding or the query cannot be embedded. It is a [[TransactionalMemoryStore]], so `SemanticMemoryMiddleware` can
 * record, replay and consolidate on it.
 *
 * The file has `SQLiteMemoryStore`'s schema, so either store opens it. A file this store wrote before 1.1, with its own
 * `memories` columns, is migrated when it is opened; a 1.0 release cannot open it afterwards.
 *
 * Writes take the database's write lock when they begin, waiting up to 30 seconds (the connection's busy timeout) for a
 * writer in another instance or process on the same file. One instance holds one JDBC connection and is not safe for
 * concurrent use; share it between threads only under your own synchronization.
 *
 * @param dbPath Path to SQLite database file
 * @param embeddingService Service for generating embeddings
 * @param config Store configuration
 */
final class VectorMemoryStore private (
  val dbPath: String,
  val embeddingService: EmbeddingService,
  val config: MemoryStoreConfig,
  private val inner: SQLiteMemoryStore
) extends TransactionalMemoryStore
    with AutoCloseable {

  /** The SQLite file this store reads and writes, by its real path, as `SQLiteMemoryStore` names it (#3238). */
  override def storageIdentity: Result[Option[String]] = inner.storageIdentity

  override def durable: Boolean = inner.durable

  override def store(memory: Memory): Result[MemoryStore]           = inner.store(memory).map(_ => this)
  override def storeAll(memories: Seq[Memory]): Result[MemoryStore] = inner.storeAll(memories).map(_ => this)
  override def replace(add: Seq[Memory], remove: Seq[MemoryId]): Result[MemoryStore] =
    inner.replace(add, remove).map(_ => this)
  override def get(id: MemoryId): Result[Option[Memory]]                     = inner.get(id)
  override def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] = inner.recall(filter, limit)
  override def search(query: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]] =
    inner.search(query, topK, filter)
  override def delete(id: MemoryId): Result[MemoryStore]                 = inner.delete(id).map(_ => this)
  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] = inner.deleteMatching(filter).map(_ => this)
  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    inner.update(id, updateFn).map(_ => this)
  override def count(filter: MemoryFilter): Result[Long]                     = inner.count(filter)
  override def clear(): Result[MemoryStore]                                  = inner.clear().map(_ => this)
  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] = inner.recent(limit, filter)

  override def applyAtomically(
    key: AppliedWriteKey,
    token: Long,
    add: Seq[Memory],
    remove: Seq[MemoryId],
    bounds: MemoryBounds
  ): Result[(TransactionalMemoryStore, AppliedWrite)] =
    inner.applyAtomically(key, token, add, remove, bounds).map { case (_, out) => (this, out) }

  override def appliedWrite(
    key: AppliedWriteKey,
    token: Long
  ): Result[(TransactionalMemoryStore, Option[AppliedWrite])] =
    inner.appliedWrite(key, token).map { case (_, out) => (this, out) }

  override def forgetApplied(key: AppliedWriteKey, token: Long): Result[TransactionalMemoryStore] =
    inner.forgetApplied(key, token).map(_ => this)

  override def appliedWriteCount: Result[Int] = inner.appliedWriteCount

  /** Embed every memory that has no embedding, `batchSize` at a time, and return how many were embedded. */
  def embedAll(batchSize: Int = 100): Result[Int] = inner.embedAll(batchSize)

  /**
   * Embed every memory that has no embedding or one whose dimension is not `dimension`, `batchSize` at a time: after a
   * change of embedding model, the memories written with the old one are otherwise never compared with a query.
   */
  def reembedAll(dimension: Int, batchSize: Int = 100): Result[Int] = inner.reembedAll(dimension, batchSize)

  override def stats: Result[MemoryStats] = inner.stats

  /** Statistics about the store, including embedding coverage. */
  def vectorStats: Result[VectorStoreStats] = inner.vectorStats

  /** The `SQLiteMemoryStore` this store is. */
  def underlying: SQLiteMemoryStore = inner

  /** Close the database connection. */
  override def close(): Unit = inner.close()
}

object VectorMemoryStore {

  private val BusyTimeoutMillis = 30000

  /** The most texts `storeAll` sends to the embedding service in one `embedBatch` call. */
  val EmbeddingBatchSize: Int = 64

  /** Create a vector memory store with file-based SQLite storage. */
  def apply(
    dbPath: String,
    embeddingService: EmbeddingService,
    config: MemoryStoreConfig = MemoryStoreConfig.default
  ): Result[VectorMemoryStore] =
    open(dbPath, embeddingService, config, path => DriverManager.getConnection(s"jdbc:sqlite:$path"))

  /** Open the store on a connection from `connect`; the seam lets a test record the SQL the store sends. */
  private[memory] def open(
    dbPath: String,
    embeddingService: EmbeddingService,
    config: MemoryStoreConfig,
    connect: String => Connection
  ): Result[VectorMemoryStore] =
    SQLiteMemoryStore
      .open(dbPath, config, connect, Some(embeddingService), Some(BusyTimeoutMillis))
      .map(inner => new VectorMemoryStore(dbPath, embeddingService, config, inner))
      .left
      .map(e => ProcessingError("vector-store", s"Failed to create vector store: ${Redaction.scrubKnown(e.message)}"))

  /** Create an in-memory vector store (for testing). */
  def inMemory(
    embeddingService: EmbeddingService = MockEmbeddingService.default,
    config: MemoryStoreConfig = MemoryStoreConfig.testing
  ): Result[VectorMemoryStore] =
    Try(Class.forName("org.sqlite.JDBC")).toEither.left
      .map(e => ProcessingError("vector-store", s"SQLite JDBC driver not found: ${e.getMessage}"))
      .flatMap(_ =>
        open(":memory:", embeddingService, config, path => DriverManager.getConnection(s"jdbc:sqlite:$path"))
      )

  /** Create a vector store over an embedding client and model. */
  def fromEnv(
    client: EmbeddingClient,
    embeddingModel: EmbeddingModelConfig,
    dbPath: String,
    config: MemoryStoreConfig = MemoryStoreConfig.default
  ): Result[VectorMemoryStore] =
    apply(dbPath, LLMEmbeddingService(client, embeddingModel), config)

  // Serialization helpers

  private[memory] def serializeMetadata(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else ujson.write(ujson.Obj.from(metadata.map { case (k, v) => k -> ujson.Str(v) }))

  private[memory] def deserializeMetadata(json: String): Map[String, String] =
    if (json == null || json == "{}" || json.isEmpty) Map.empty
    else
      Try(ujson.read(json).obj.collect { case (k, ujson.Str(v)) => k -> v }.toMap).getOrElse {
        // Rows written before metadata was JSON-escaped: fall back to the lenient pattern
        val pattern = """"([^"]+)":"([^"]*)"""".r
        pattern.findAllMatchIn(json).map(m => m.group(1) -> m.group(2)).toMap
      }

  private[memory] def serializeEmbedding(embedding: Array[Float]): Array[Byte] = {
    val buffer = java.nio.ByteBuffer.allocate(embedding.length * 4)
    buffer.asFloatBuffer().put(embedding)
    buffer.array()
  }

  private[memory] def deserializeEmbedding(bytes: Array[Byte]): Array[Float] = {
    val buffer     = java.nio.ByteBuffer.wrap(bytes)
    val floatCount = bytes.length / 4
    val embedding  = new Array[Float](floatCount)
    buffer.asFloatBuffer().get(embedding)
    embedding
  }
}

/**
 * Statistics for a vector memory store.
 */
final case class VectorStoreStats(
  totalMemories: Long,
  embeddedMemories: Long,
  embeddingDimensions: Set[Int]
) {

  /**
   * Percentage of memories that have embeddings.
   */
  def embeddingCoverage: Double =
    if (totalMemories == 0) 0.0
    else embeddedMemories.toDouble / totalMemories * 100
}
