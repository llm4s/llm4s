package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig

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

  // Initialize schema on creation
  initializeSchema()

  private def initializeSchema(): Unit =
    Using.resource(connection.createStatement()) { stmt =>
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
    }

  override def store(memory: Memory): Result[MemoryStore] = {
    // Generate embedding if not present
    val memoryWithEmbedding = memory.embedding match {
      case Some(_) => Right(memory)
      case None =>
        embeddingService.embed(memory.content).map(embedding => memory.withEmbedding(embedding))
    }

    memoryWithEmbedding.flatMap { m =>
      Try {
        val sql =
          """INSERT OR REPLACE INTO memories
            |(id, content, memory_type, metadata, conversation_id, entity_id, source, timestamp, importance, embedding, embedding_dim)
            |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin

        Using.resource(connection.prepareStatement(sql)) { stmt =>
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
        }

        // Update FTS index
        updateFtsIndex(m)

        this: MemoryStore
      }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to store memory: ${e.getMessage}"))
    }
  }

  private def updateFtsIndex(memory: Memory): Unit = {
    // Delete existing entry if any
    Using.resource(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?")) { stmt =>
      stmt.setString(1, memory.id.value)
      stmt.executeUpdate()
    }

    // Insert new entry
    Using.resource(connection.prepareStatement("INSERT INTO memories_fts (id, content) VALUES (?, ?)")) { stmt =>
      stmt.setString(1, memory.id.value)
      stmt.setString(2, memory.content)
      stmt.executeUpdate()
    }
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
      val (whereClause, params) = filterToSql(filter)
      val sql                   = s"SELECT * FROM memories WHERE $whereClause ORDER BY timestamp DESC LIMIT ?"

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.setInt(params.size + 1, limit)

        Using.resource(stmt.executeQuery()) { rs =>
          val memories = ArrayBuffer.empty[Memory]
          while (rs.next())
            memories += rowToMemory(rs)
          memories.toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to recall memories: ${e.getMessage}"))

  override def search(query: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]] =
    // Generate query embedding
    embeddingService.embed(query).flatMap { queryEmbedding =>
      Try {
        // Get all memories matching filter that have embeddings
        val (whereClause, params) = filterToSql(filter)
        val sql                   = s"SELECT * FROM memories WHERE $whereClause AND embedding IS NOT NULL"

        Using.resource(connection.prepareStatement(sql)) { stmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }

          Using.resource(stmt.executeQuery()) { rs =>
            val candidates = ArrayBuffer.empty[(Memory, Array[Float])]

            while (rs.next()) {
              val memory    = rowToMemory(rs)
              val embedding = memory.embedding.getOrElse(Array.empty[Float])
              if (embedding.nonEmpty) {
                candidates += ((memory, embedding))
              }
            }

            // Calculate similarities and return top-K
            candidates
              .map { case (memory, embedding) =>
                val similarity = VectorOps.cosineSimilarity(queryEmbedding, embedding)
                // Normalize to 0-1 range (cosine similarity is -1 to 1)
                val normalizedScore = (similarity + 1) / 2
                ScoredMemory(memory, normalizedScore)
              }
              .sortBy(-_.score)
              .take(topK)
              .toSeq
          }
        }
      }.toEither.left
        .map(_ => ProcessingError("vector-store", "Vector search failed"))
        .orElse(keywordSearch(query, topK, filter))
    }

  /**
   * Keyword-based fallback search using FTS5.
   */
  private def keywordSearch(query: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]] =
    Try {
      val escapedQuery          = escapeFtsQuery(query)
      val (whereClause, params) = filterToSql(filter)

      val sql =
        s"""SELECT m.*, bm25(memories_fts) as score
           |FROM memories m
           |JOIN memories_fts fts ON m.id = fts.id
           |WHERE fts.content MATCH ? AND $whereClause
           |ORDER BY score
           |LIMIT ?""".stripMargin

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        stmt.setString(1, escapedQuery)
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 2, param)
        }
        stmt.setInt(params.size + 2, topK)

        Using.resource(stmt.executeQuery()) { rs =>
          val results = ArrayBuffer.empty[ScoredMemory]
          while (rs.next()) {
            val memory = rowToMemory(rs)
            val bm25   = rs.getDouble("score")
            // Normalize BM25 score to 0-1 range (BM25 is typically negative, closer to 0 is better)
            val score = math.max(0.0, math.min(1.0, 1.0 / (1.0 + math.abs(bm25))))
            results += ScoredMemory(memory, score)
          }
          results.toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("vector-store", s"Keyword search failed: ${e.getMessage}"))

  private def escapeFtsQuery(query: String): String = {
    // Split into words and use OR for broader matching
    val words = query
      .replace("\"", "")
      .replace("'", "")
      .split("\\s+")
      .filter(_.nonEmpty)
      .map(w => s""""$w"""")

    if (words.isEmpty) "\"\""
    else words.mkString(" OR ")
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
      // First get IDs to delete
      val (whereClause, params) = filterToSql(filter)
      val selectSql             = s"SELECT id FROM memories WHERE $whereClause"

      val ids = Using.resource(connection.prepareStatement(selectSql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        Using.resource(stmt.executeQuery()) { rs =>
          val buffer = ArrayBuffer.empty[String]
          while (rs.next())
            buffer += rs.getString("id")
          buffer.toSeq
        }
      }

      // Delete from FTS
      ids.foreach { id =>
        Using.resource(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?")) { stmt =>
          stmt.setString(1, id)
          stmt.executeUpdate()
        }
      }

      // Delete from main table
      val deleteSql = s"DELETE FROM memories WHERE $whereClause"
      Using.resource(connection.prepareStatement(deleteSql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.executeUpdate()
      }

      this: MemoryStore
    }.toEither.left.map(e => ProcessingError("vector-store", s"Failed to delete matching memories: ${e.getMessage}"))

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
        delete(id).flatMap(_ => store(memoryToStore))
      case None =>
        Left(ProcessingError("vector-store", s"Memory not found: ${id.value}"))
    }

  override def count(filter: MemoryFilter): Result[Long] =
    Try {
      val (whereClause, params) = filterToSql(filter)
      val sql                   = s"SELECT COUNT(*) FROM memories WHERE $whereClause"

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        Using.resource(stmt.executeQuery()) { rs =>
          rs.next()
          rs.getLong(1)
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
      val (whereClause, params) = filterToSql(filter)
      val sql                   = s"SELECT * FROM memories WHERE $whereClause ORDER BY timestamp DESC LIMIT ?"

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.setInt(params.size + 1, limit)

        Using.resource(stmt.executeQuery()) { rs =>
          val memories = ArrayBuffer.empty[Memory]
          while (rs.next())
            memories += rowToMemory(rs)
          memories.toSeq
        }
      }
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

  private def filterToSql(filter: MemoryFilter): (String, Seq[Any]) = filter match {
    case MemoryFilter.All  => ("1=1", Seq.empty)
    case MemoryFilter.None => ("1=0", Seq.empty)

    case MemoryFilter.ByType(memoryType) =>
      ("memory_type = ?", Seq(memoryType.name))

    case MemoryFilter.ByTypes(types) =>
      val placeholders = types.map(_ => "?").mkString(",")
      (s"memory_type IN ($placeholders)", types.map(_.name).toSeq)

    case MemoryFilter.ByMetadata(key, value) =>
      (s"metadata LIKE ?", Seq(s"%\"$key\":\"$value\"%"))

    case MemoryFilter.HasMetadata(key) =>
      (s"metadata LIKE ?", Seq(s"%\"$key\":%"))

    case MemoryFilter.MetadataContains(key, substring) =>
      (s"metadata LIKE ?", Seq(s"%\"$key\":\"%$substring%\"%"))

    case MemoryFilter.ByEntity(entityId) =>
      ("entity_id = ?", Seq(entityId.value))

    case MemoryFilter.ByConversation(conversationId) =>
      ("conversation_id = ?", Seq(conversationId))

    case MemoryFilter.ByTimeRange(afterOpt, beforeOpt) =>
      (afterOpt, beforeOpt) match {
        case (Some(after), Some(before)) =>
          ("timestamp >= ? AND timestamp <= ?", Seq(after.toEpochMilli, before.toEpochMilli))
        case (Some(after), None) =>
          ("timestamp >= ?", Seq(after.toEpochMilli))
        case (None, Some(before)) =>
          ("timestamp <= ?", Seq(before.toEpochMilli))
        case (None, None) =>
          ("1=1", Seq.empty)
      }

    case MemoryFilter.MinImportance(threshold) =>
      ("importance >= ?", Seq(threshold))

    case MemoryFilter.ContentContains(substring, caseSensitive) =>
      if (caseSensitive)
        ("content LIKE ?", Seq(s"%$substring%"))
      else
        ("LOWER(content) LIKE LOWER(?)", Seq(s"%$substring%"))

    case MemoryFilter.And(left, right) =>
      val (leftSql, leftParams)   = filterToSql(left)
      val (rightSql, rightParams) = filterToSql(right)
      (s"($leftSql AND $rightSql)", leftParams ++ rightParams)

    case MemoryFilter.Or(left, right) =>
      val (leftSql, leftParams)   = filterToSql(left)
      val (rightSql, rightParams) = filterToSql(right)
      (s"($leftSql OR $rightSql)", leftParams ++ rightParams)

    case MemoryFilter.Not(inner) =>
      val (innerSql, innerParams) = filterToSql(inner)
      (s"NOT ($innerSql)", innerParams)

    case _: MemoryFilter.Custom =>
      // Custom filters can't be translated to SQL, match all and filter in memory
      ("1=1", Seq.empty)
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

  /**
   * Create a vector memory store with file-based SQLite storage.
   */
  def apply(
    dbPath: String,
    embeddingService: EmbeddingService,
    config: MemoryStoreConfig = MemoryStoreConfig.default
  ): Result[VectorMemoryStore] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
      connection.setAutoCommit(true)
      new VectorMemoryStore(dbPath, embeddingService, config, connection)
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
    else metadata.map { case (k, v) => s""""$k":"$v"""" }.mkString("{", ",", "}")

  private[memory] def deserializeMetadata(json: String): Map[String, String] =
    if (json == null || json == "{}" || json.isEmpty) Map.empty
    else {
      // Simple JSON parsing for metadata
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
