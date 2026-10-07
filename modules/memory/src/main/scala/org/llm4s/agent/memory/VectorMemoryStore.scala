package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.llm4s.error.{ ConfigurationError, ProcessingError }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.slf4j.LoggerFactory

import java.sql.{ Connection, DriverManager, PreparedStatement, ResultSet }
import java.time.Instant
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

/**
 * Memory store with vector embedding support for semantic search.
 *
 * This store extends SQLite-based storage with embedding vectors,
 * enabling semantic similarity search in addition to keyword search.
 *
 * Embeddings are generated on-demand using the provided EmbeddingService
 * and stored alongside memories for efficient retrieval.
 *
 * Writes: `store` and `storeAll` are each one transaction - a batch is stored whole or not at all - and so are
 * `deleteMatching` and opening the store. A write takes the database's write lock when it begins, waiting up to 30
 * seconds (the connection's busy timeout) for a writer in another instance or process on the same file.
 *
 * Thread safety: one instance holds one JDBC connection and is not safe for concurrent use; share it between
 * threads only under your own synchronization. Separate instances, in one process or several, may write the same
 * file concurrently: SQLite serialises their transactions.
 *
 * @param dbPath Path to SQLite database file
 * @param embeddingService Service for generating embeddings
 * @param config Store configuration
 */
final class VectorMemoryStore private (
  val dbPath: String,
  val embeddingService: EmbeddingService,
  val config: MemoryStoreConfig,
  private val connection: Connection
) extends MemoryStore {

  import VectorMemoryStore._
  import FilterSupport.Sql

  // Initialize schema on creation
  initializeSchema()

  // One transaction, so opening a store commits (and syncs the file) once rather than once per statement.
  private def initializeSchema(): Unit =
    inTransaction(Using.resource(connection.createStatement()) { stmt =>
      // Main memories table with embedding blob
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS memories (
          |  id TEXT PRIMARY KEY,
          |  content TEXT NOT NULL,
          |  memory_type TEXT NOT NULL,
          |  metadata TEXT,
          |  conversation_id TEXT,
          |  entity_id TEXT,
          |  source TEXT,
          |  timestamp INTEGER NOT NULL,
          |  importance REAL,
          |  embedding BLOB,
          |  embedding_dim INTEGER
          |)""".stripMargin
      )

      // Indexes for common queries
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(memory_type)")
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_conversation ON memories(conversation_id)")
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_entity ON memories(entity_id)")
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_timestamp ON memories(timestamp)")
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance)")
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_memories_has_embedding ON memories(embedding_dim)")

      // FTS5 for keyword search fallback
      stmt.execute(
        """CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
          |  id UNINDEXED,
          |  content
          |)""".stripMargin
      )

      // Schema version tracking
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS schema_version (
          |  version INTEGER PRIMARY KEY,
          |  applied_at INTEGER NOT NULL
          |)""".stripMargin
      )

      // Insert initial version if not exists
      Using.resource(stmt.executeQuery("SELECT COUNT(*) FROM schema_version")) { rs =>
        rs.next()
        if (rs.getInt(1) == 0) {
          stmt.execute(s"INSERT INTO schema_version (version, applied_at) VALUES (1, ${System.currentTimeMillis()})")
        }
      }
    })

  override def store(memory: Memory): Result[MemoryStore] = storeAll(Seq(memory))

  /**
   * Store `memories` in one transaction: every missing embedding is computed first, in `embedBatch` calls of at most
   * [[VectorMemoryStore.EmbeddingBatchSize]] texts, then all of them are written or, if any embedding call or any
   * write fails, none is. The commit - and the file sync behind it - is paid once for the batch, not once per row.
   */
  override def storeAll(memories: Seq[Memory]): Result[MemoryStore] = {
    val withEmbeddings = embedMissing(memories)

    withEmbeddings.flatMap { ms =>
      Try {
        if (ms.nonEmpty) inTransaction(writeAll(ms))
        this: MemoryStore
      }.toEither.left.map(e =>
        ProcessingError(
          "vector-store",
          s"Failed to store ${if (ms.size == 1) "memory" else "memories"}: ${e.getMessage}"
        )
      )
    }
  }

  /**
   * `memories` with every missing embedding filled in, asking the service for at most `EmbeddingBatchSize` texts at a
   * time - one request for a whole large batch could exceed a provider's input limit. Fails if any call fails,
   * throws, or answers with the wrong number of embeddings.
   */
  private def embedMissing(memories: Seq[Memory]): Result[Seq[Memory]] = {
    val texts = memories.filter(_.embedding.isEmpty).map(_.content)
    val embedded =
      texts.grouped(EmbeddingBatchSize).foldLeft[Result[Vector[Array[Float]]]](Right(Vector.empty)) { (acc, chunk) =>
        acc.flatMap { done =>
          Try(embeddingService.embedBatch(chunk))
            .fold(e => Left(ProcessingError("vector-store", s"Embedding failed: ${e.getMessage}")), identity)
            .flatMap { embeddings =>
              if (embeddings.size == chunk.size) Right(done ++ embeddings)
              else
                Left(
                  ProcessingError(
                    "vector-store",
                    s"Embedding service returned ${embeddings.size} embeddings for ${chunk.size} texts"
                  )
                )
            }
        }
      }
    embedded.map { embeddings =>
      val next = embeddings.iterator
      memories.map(m => if (m.embedding.isEmpty) m.withEmbedding(next.next()) else m)
    }
  }

  /**
   * Write each memory and replace its full-text entry, in order, reusing three prepared statements. The caller
   * supplies the transaction.
   */
  private def writeAll(memories: Seq[Memory]): Unit = {
    val sql =
      """INSERT OR REPLACE INTO memories
        |(id, content, memory_type, metadata, conversation_id, entity_id, source, timestamp, importance, embedding, embedding_dim)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin

    Using.Manager { use =>
      val stmt      = use(connection.prepareStatement(sql))
      val ftsDelete = use(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?"))
      val ftsInsert = use(connection.prepareStatement("INSERT INTO memories_fts (id, content) VALUES (?, ?)"))
      memories.foreach { m =>
        stmt.setString(1, m.id.value)
        stmt.setString(2, m.content)
        stmt.setString(3, m.memoryType.name)
        stmt.setString(4, serializeMetadata(m.metadata))
        stmt.setString(5, m.getMetadata("conversation_id").orNull)
        stmt.setString(6, m.getMetadata("entity_id").orNull)
        stmt.setString(7, m.getMetadata("source").orNull)
        stmt.setLong(8, m.timestamp.toEpochMilli)
        m.importance match {
          case Some(imp) => stmt.setDouble(9, imp)
          case None      => stmt.setNull(9, java.sql.Types.REAL)
        }
        m.embedding match {
          case Some(emb) =>
            stmt.setBytes(10, serializeEmbedding(emb))
            stmt.setInt(11, emb.length)
          case None =>
            stmt.setNull(10, java.sql.Types.BLOB)
            stmt.setNull(11, java.sql.Types.INTEGER)
        }
        stmt.executeUpdate()

        // Replace the full-text entry
        ftsDelete.setString(1, m.id.value)
        ftsDelete.executeUpdate()
        ftsInsert.setString(1, m.id.value)
        ftsInsert.setString(2, m.content)
        ftsInsert.executeUpdate()
      }
    }.get
  }

  override def get(id: MemoryId): Result[Option[Memory]] =
    Try {
      Using.resource(connection.prepareStatement("SELECT * FROM memories WHERE id = ?")) { stmt =>
        stmt.setString(1, id.value)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Some(rowToMemory(rs))
          else None
        }
      }
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to get memory: ${e.getMessage}"))

  override def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] =
    Try {
      queryMemories(filter, Some(limit))
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to recall memories: ${e.getMessage}"))

  /** Memories matching `filter`, newest first; `limit` applies to the matches, after any in-memory filtering. */
  private def queryMemories(filter: MemoryFilter, limit: Option[Int]): Seq[Memory] = {
    val plan = narrowing(filter)
    val sql =
      if (plan.needsMatches || limit.isEmpty) s"SELECT * FROM memories ${plan.where} ORDER BY timestamp DESC"
      else s"SELECT * FROM memories ${plan.where} ORDER BY timestamp DESC LIMIT ?"

    Using.resource(connection.prepareStatement(sql)) { stmt =>
      plan.params.zipWithIndex.foreach { case (param, idx) =>
        setParameter(stmt, idx + 1, param)
      }
      if (!plan.needsMatches) limit.foreach(l => stmt.setInt(plan.params.size + 1, l))

      Using.resource(stmt.executeQuery()) { rs =>
        val memories = Iterator.continually(rs).takeWhile(_.next()).map(rowToMemory)
        val matching = if (plan.needsMatches) memories.filter(filter.matches) else memories
        (if (plan.needsMatches) limit.fold(matching)(matching.take) else matching).toSeq
      }
    }
  }

  override def search(query: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]] =
    // Generate query embedding: as a query, which some models embed differently from a document
    embeddingService.embedQuery(query).flatMap { queryEmbedding =>
      Try {
        // Memories matching the filter that have embeddings; topK applies to those the filter accepts
        val candidates = queryMemories(filter, None).flatMap(m => m.embedding.filter(_.nonEmpty).map(e => (m, e)))
        val (comparable, mismatched) = candidates.partition(_._2.length == queryEmbedding.length)

        mismatched.headOption match {
          case Some((memory, stored)) if comparable.isEmpty =>
            // Nothing stored can be compared with the query: the store was written with a different embedding
            // model, and hiding that behind a keyword fallback or an empty result would hide the misconfiguration.
            Left(
              ConfigurationError(
                s"Embedding dimension mismatch: the query embedding has ${queryEmbedding.length} dimensions but " +
                  s"memory ${memory.id.value} was stored with ${stored.length}. The store was written with a " +
                  "different embedding model; use that model, or re-embed the store."
              )
            )
          case _ =>
            // A store that holds vectors of more than one dimension (the embedding model was changed part way)
            // stays searchable: the memories that cannot be compared are left out, and counted in the log.
            if (mismatched.nonEmpty)
              logger.warn(
                s"Vector search skipped ${mismatched.size} memories whose embedding has a different dimension than " +
                  s"the query's ${queryEmbedding.length}; re-embed the store to include them"
              )
            Right(
              comparable
                .map { case (memory, embedding) =>
                  val similarity = VectorOps.cosineSimilarity(queryEmbedding, embedding)
                  // Normalize to 0-1 range (cosine similarity is -1 to 1)
                  ScoredMemory(memory, (similarity + 1) / 2)
                }
                .sortBy(-_.score)
                .take(topK)
            )
        }
      }.toEither.left
        .map(e => ProcessingError("vector-store", s"Vector search failed: ${e.getMessage}"))
        .flatMap(identity)
    }

  override def delete(id: MemoryId): Result[MemoryStore] =
    Try {
      // Delete from FTS
      Using.resource(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?")) { stmt =>
        stmt.setString(1, id.value)
        stmt.executeUpdate()
      }

      // Delete from main table
      Using.resource(connection.prepareStatement("DELETE FROM memories WHERE id = ?")) { stmt =>
        stmt.setString(1, id.value)
        stmt.executeUpdate()
      }

      this: MemoryStore
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to delete memory: ${e.getMessage}"))

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] =
    Try {
      val plan = narrowing(filter)
      // One transaction: the whole delete happens or none of it, and the commit is paid once, not once per row.
      inTransaction {
        if (plan.exact) {
          // SQL says exactly which rows go: delete them in SQL, from the full-text index and the table, without
          // reading a single row (or decoding a single embedding) into memory.
          bindAndUpdate(s"DELETE FROM memories_fts WHERE id IN (SELECT id FROM memories ${plan.where})", plan.params)
          bindAndUpdate(s"DELETE FROM memories ${plan.where}", plan.params)
        } else {
          // `matches` decides: stream the narrowed rows (without the embedding unless the filter can read it),
          // keep only the ids it accepts, then delete those.
          val ids = Vector.newBuilder[String]
          foreachMatching(filter, plan)(m => ids += m.id.value)
          deleteIds(ids.result())
        }
      }

      this: MemoryStore
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to delete matching memories: ${e.getMessage}"))

  private def bindAndUpdate(sql: String, params: Seq[Any]): Int =
    Using.resource(connection.prepareStatement(sql)) { stmt =>
      params.zipWithIndex.foreach { case (param, idx) =>
        setParameter(stmt, idx + 1, param)
      }
      stmt.executeUpdate()
    }

  /** Delete `ids` from the full-text index and the table, as two batches. */
  private def deleteIds(ids: Seq[String]): Unit =
    if (ids.nonEmpty)
      Seq("DELETE FROM memories_fts WHERE id = ?", "DELETE FROM memories WHERE id = ?").foreach { sql =>
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          ids.foreach { id =>
            stmt.setString(1, id)
            stmt.addBatch()
          }
          stmt.executeBatch()
        }
      }

  /**
   * Call `f` on each memory `filter` accepts among the rows `plan` narrows to, one row at a time: nothing is
   * materialised. The embedding is read and decoded only if the filter can look at it (a `Custom` predicate).
   */
  private def foreachMatching(filter: MemoryFilter, plan: FilterSupport.Narrowing)(f: Memory => Unit): Unit = {
    val embedding = if (FilterSupport.readsEmbedding(filter)) "embedding" else "NULL AS embedding"
    val sql =
      s"SELECT id, content, memory_type, metadata, timestamp, importance, $embedding FROM memories ${plan.where}"
    Using.resource(connection.prepareStatement(sql)) { stmt =>
      plan.params.zipWithIndex.foreach { case (param, idx) =>
        setParameter(stmt, idx + 1, param)
      }
      Using.resource(stmt.executeQuery()) { rs =>
        while (rs.next()) {
          val memory = rowToMemory(rs)
          if (filter.matches(memory)) f(memory)
        }
      }
    }
  }

  /** Run `body` as one write transaction: committed if it returns, rolled back otherwise. */
  private def inTransaction[A](body: => A): A = SqliteTransaction.immediate(connection)(body)

  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    get(id).flatMap {
      case Some(memory) =>
        val updated = updateFn(memory)
        // Re-embed if content changed
        val memoryToStore =
          if (updated.content != memory.content)
            updated.copy(embedding = None) // Will be re-embedded on store
          else
            updated
        // The same id is replaced in one transaction, after any re-embedding: a failure leaves the memory as it was.
        if (memoryToStore.id == id) store(memoryToStore)
        else delete(id).flatMap(_ => store(memoryToStore))
      case None =>
        Left(ProcessingError("vector-store", s"Memory not found: ${id.value}"))
    }

  override def count(filter: MemoryFilter): Result[Long] =
    Try {
      val plan = narrowing(filter)
      if (plan.needsMatches) {
        var n = 0L
        foreachMatching(filter, plan)(_ => n += 1)
        n
      } else {
        val sql = s"SELECT COUNT(*) FROM memories ${plan.where}"
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          plan.params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          Using.resource(stmt.executeQuery()) { rs =>
            rs.next()
            rs.getLong(1)
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to count memories: ${e.getMessage}"))

  override def clear(): Result[MemoryStore] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        stmt.execute("DELETE FROM memories")
        stmt.execute("DELETE FROM memories_fts")
      }
      this: MemoryStore
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to clear memories: ${e.getMessage}"))

  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] =
    Try {
      queryMemories(filter, Some(limit))
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to get recent memories: ${e.getMessage}"))

  /**
   * Embed all memories that don't have embeddings.
   *
   * This is useful for adding embeddings to memories imported without them.
   *
   * @param batchSize Number of memories to embed per batch
   * @return Number of memories embedded
   */
  def embedAll(batchSize: Int = 100): Result[Int] = {
    def fetchBatch(): Result[Seq[Memory]] =
      Try {
        val sql = "SELECT * FROM memories WHERE embedding IS NULL LIMIT ?"
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          stmt.setInt(1, batchSize)
          Using.resource(stmt.executeQuery()) { rs =>
            val memories = ArrayBuffer.empty[Memory]
            while (rs.next())
              memories += rowToMemory(rs)
            memories.toSeq
          }
        }
      }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to fetch memories: ${e.getMessage}"))

    def updateEmbedding(memory: Memory, embedding: Array[Float]): Unit = {
      val updateSql = "UPDATE memories SET embedding = ?, embedding_dim = ? WHERE id = ?"
      Using.resource(connection.prepareStatement(updateSql)) { stmt =>
        stmt.setBytes(1, serializeEmbedding(embedding))
        stmt.setInt(2, embedding.length)
        stmt.setString(3, memory.id.value)
        stmt.executeUpdate()
      }
    }

    def processBatch(): Result[Int] =
      fetchBatch().flatMap { memories =>
        if (memories.isEmpty) {
          Right(0)
        } else {
          val texts = memories.map(_.content)
          embeddingService.embedBatch(texts).flatMap { embs =>
            Try {
              memories.zip(embs).foreach { case (memory, embedding) =>
                updateEmbedding(memory, embedding)
              }
              memories.size
            }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to update embeddings: ${e.getMessage}"))
          }
        }
      }

    // Process batches until no more memories without embeddings
    @scala.annotation.tailrec
    def loop(totalEmbedded: Int): Result[Int] =
      processBatch() match {
        case Right(0)     => Right(totalEmbedded)
        case Right(count) => loop(totalEmbedded + count)
        case Left(error)  => Left(error)
      }

    loop(0)
  }

  /**
   * Get statistics about the store including embedding coverage.
   */
  def vectorStats: Result[VectorStoreStats] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        val total = Using.resource(stmt.executeQuery("SELECT COUNT(*) FROM memories")) { rs =>
          rs.next()
          rs.getLong(1)
        }

        val embedded = Using.resource(stmt.executeQuery("SELECT COUNT(*) FROM memories WHERE embedding IS NOT NULL")) {
          rs =>
            rs.next()
            rs.getLong(1)
        }

        val dims = Using.resource(
          stmt.executeQuery("SELECT DISTINCT embedding_dim FROM memories WHERE embedding_dim IS NOT NULL")
        ) { rs =>
          val buffer = ArrayBuffer.empty[Int]
          while (rs.next())
            buffer += rs.getInt(1)
          buffer.toSet
        }

        VectorStoreStats(
          totalMemories = total,
          embeddedMemories = embedded,
          embeddingDimensions = dims
        )
      }
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to get stats: ${e.getMessage}"))

  /**
   * Close the database connection.
   */
  def close(): Unit =
    if (!connection.isClosed) {
      connection.close()
    }

  // Helper methods

  private def rowToMemory(rs: ResultSet): Memory = {
    val embeddingBytes = rs.getBytes("embedding")
    val embedding =
      if (embeddingBytes != null) Some(deserializeEmbedding(embeddingBytes))
      else None

    Memory(
      id = MemoryId(rs.getString("id")),
      content = rs.getString("content"),
      memoryType = MemoryType.fromString(rs.getString("memory_type")),
      metadata = deserializeMetadata(rs.getString("metadata")),
      timestamp = Instant.ofEpochMilli(rs.getLong("timestamp")),
      importance = Option(rs.getDouble("importance")).filterNot(_ => rs.wasNull()),
      embedding = embedding
    )
  }

  private def narrowing(filter: MemoryFilter): FilterSupport.Narrowing =
    FilterSupport.narrow(filter)(leafSql)

  private def jsonString(text: String): String = ujson.write(ujson.Str(text))

  /**
   * The SQL of one filter, when it means exactly what `matches` means: a row satisfies it if and only if `matches`
   * accepts the row, and it is never NULL (a NULL under `NOT` would drop a row `Not` accepts), hence the `COALESCE`
   * around every comparison with a nullable column. A `MetadataContains` has none: metadata is stored as JSON text,
   * where a substring test can match across keys, so `matches` decides it.
   */
  private def leafSql(filter: MemoryFilter): Option[Sql] = filter match {
    case MemoryFilter.ByType(memoryType) =>
      Some(Sql("memory_type = ?", Seq(memoryType.name)))

    case MemoryFilter.ByTypes(types) =>
      // A Seq, not the Set: mapping a Set to "?" would collapse the placeholders into one
      val names = types.toSeq.map(_.name)
      if (names.isEmpty) Some(Sql("1 = 0", Seq.empty))
      else Some(Sql(s"memory_type IN (${names.map(_ => "?").mkString(",")})", names))

    case MemoryFilter.ByMetadata(key, value) =>
      // Metadata is stored as JSON text, so look for the JSON form of the pair. instr is a literal, case-sensitive
      // search: LIKE would read % and _ as wildcards and ignore the case of ASCII letters.
      Some(Sql("COALESCE(instr(metadata, ?) > 0, 0)", Seq(s"${jsonString(key)}:${jsonString(value)}")))

    case MemoryFilter.HasMetadata(key) =>
      Some(Sql("COALESCE(instr(metadata, ?) > 0, 0)", Seq(s"${jsonString(key)}:")))

    case MemoryFilter.ByEntity(entityId) =>
      Some(Sql("COALESCE(entity_id = ?, 0)", Seq(entityId.value)))

    case MemoryFilter.ByConversation(conversationId) =>
      Some(Sql("COALESCE(conversation_id = ?, 0)", Seq(conversationId)))

    case MemoryFilter.ByTimeRange(afterOpt, beforeOpt) =>
      (afterOpt, beforeOpt) match {
        case (Some(after), Some(before)) =>
          Some(Sql("timestamp >= ? AND timestamp <= ?", Seq(after.toEpochMilli, before.toEpochMilli)))
        case (Some(after), None)  => Some(Sql("timestamp >= ?", Seq(after.toEpochMilli)))
        case (None, Some(before)) => Some(Sql("timestamp <= ?", Seq(before.toEpochMilli)))
        case (None, None)         => Some(FilterSupport.unrestricted)
      }

    case MemoryFilter.MinImportance(threshold) =>
      Some(Sql("COALESCE(importance >= ?, 0)", Seq(threshold)))

    case MemoryFilter.ContentContains(substring, caseSensitive) =>
      // instr is a literal substring test; LIKE would read % and _ as wildcards and is never case sensitive
      if (caseSensitive) Some(Sql("instr(content, ?) > 0", Seq(substring)))
      else Some(Sql(s"instr(${FilterSupport.JavaLower}(content), ${FilterSupport.JavaLower}(?)) > 0", Seq(substring)))

    case _ => None // MetadataContains here; And, Or, Not, All, None and Custom are narrowed by FilterSupport
  }

  private def setParameter(stmt: PreparedStatement, index: Int, value: Any): Unit = value match {
    case s: String  => stmt.setString(index, s)
    case i: Int     => stmt.setInt(index, i)
    case l: Long    => stmt.setLong(index, l)
    case d: Double  => stmt.setDouble(index, d)
    case b: Boolean => stmt.setBoolean(index, b)
    case null       => stmt.setNull(index, java.sql.Types.NULL)
    case other      => stmt.setString(index, other.toString)
  }
}

object VectorMemoryStore {

  private val logger = LoggerFactory.getLogger(getClass)

  private val BusyTimeoutMillis = 30000

  /** The most texts `storeAll` sends to the embedding service in one `embedBatch` call. */
  val EmbeddingBatchSize: Int = 64

  /**
   * Create a vector memory store with file-based SQLite storage.
   */
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
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = connect(dbPath)
      // If schema setup fails (e.g. the file is not a database) the connection must not leak:
      // an open handle keeps the file locked, which blocks deletion on Windows.
      Try {
        connection.setAutoCommit(true)
        FilterSupport.registerJavaLower(connection)
        // Wait for a competing writer on the same file instead of failing immediately with SQLITE_BUSY
        Using.resource(connection.createStatement())(_.execute(s"PRAGMA busy_timeout = $BusyTimeoutMillis"))
        new VectorMemoryStore(dbPath, embeddingService, config, connection)
      }.recoverWith { case e =>
        Try(connection.close())
        scala.util.Failure(e)
      }.get
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to create vector store: ${e.getMessage}"))

  /**
   * Create an in-memory vector store (for testing).
   */
  def inMemory(
    embeddingService: EmbeddingService = MockEmbeddingService.default,
    config: MemoryStoreConfig = MemoryStoreConfig.testing
  ): Result[VectorMemoryStore] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
      connection.setAutoCommit(true)
      FilterSupport.registerJavaLower(connection)
      new VectorMemoryStore(":memory:", embeddingService, config, connection)
    }.toEither.left.map(e =>
      ProcessingError("vector-store", s"Failed to create in-memory vector store: ${e.getMessage}")
    )

  /**
   * Create a vector store from environment configuration.
   * Uses the configured embedding provider.
   */
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
