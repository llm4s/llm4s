package org.llm4s.agent.memory

import org.llm4s.error.{ ConfigurationError, NotFoundError, ProcessingError }
import org.llm4s.types.Result

import java.sql.{ Connection, DriverManager, PreparedStatement, ResultSet, SQLException }
import java.time.Instant
import scala.util.{ Try, Using }

/**
 * SQLite-backed implementation of MemoryStore.
 *
 * Provides persistent storage for memories using SQLite database.
 * Supports full-text search via SQLite FTS5 extension.
 *
 * Thread Safety: This implementation is NOT thread-safe. For concurrent
 * access, use connection pooling or synchronization.
 *
 * @param dbPath Path to SQLite database file (use ":memory:" for in-memory)
 * @param config Store configuration
 */
final class SQLiteMemoryStore private (
  val dbPath: String,
  val config: MemoryStoreConfig,
  private val connection: Connection
) extends MemoryStore {

  import SQLiteMemoryStore._

  override def store(memory: Memory): Result[MemoryStore] =
    Try {
      val sql =
        """INSERT INTO memories (id, content, memory_type, timestamp, importance, conversation_id, entity_id, source, metadata_json, embedding_blob)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT(id) DO UPDATE SET
          |  content = excluded.content,
          |  memory_type = excluded.memory_type,
          |  importance = excluded.importance,
          |  conversation_id = excluded.conversation_id,
          |  entity_id = excluded.entity_id,
          |  source = excluded.source,
          |  metadata_json = excluded.metadata_json,
          |  embedding_blob = excluded.embedding_blob""".stripMargin

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        stmt.setString(1, memory.id.value)
        stmt.setString(2, memory.content)
        stmt.setString(3, memoryTypeToString(memory.memoryType))
        stmt.setLong(4, memory.timestamp.toEpochMilli)
        memory.importance match {
          case Some(imp) => stmt.setDouble(5, imp)
          case None      => stmt.setNull(5, java.sql.Types.DOUBLE)
        }
        // Extract from metadata for efficient querying
        memory.conversationId match {
          case Some(cid) => stmt.setString(6, cid)
          case None      => stmt.setNull(6, java.sql.Types.VARCHAR)
        }
        memory.getMetadata("entity_id") match {
          case Some(eid) => stmt.setString(7, eid)
          case None      => stmt.setNull(7, java.sql.Types.VARCHAR)
        }
        memory.source match {
          case Some(src) => stmt.setString(8, src)
          case None      => stmt.setNull(8, java.sql.Types.VARCHAR)
        }
        stmt.setString(9, metadataToJson(memory.metadata))
        memory.embedding match {
          case Some(emb) => stmt.setBytes(10, embeddingToBytes(emb))
          case None      => stmt.setNull(10, java.sql.Types.BLOB)
        }
        stmt.executeUpdate()
      }

      // Update FTS index
      updateFtsIndex(memory)

      this
    }.toEither.left.map(e => ProcessingError("sqlite-store", s"Failed to store memory: ${e.getMessage}"))

  override def get(id: MemoryId): Result[Option[Memory]] =
    Try {
      val sql = "SELECT * FROM memories WHERE id = ?"
      Using.resource(connection.prepareStatement(sql)) { stmt =>
        stmt.setString(1, id.value)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Some(rowToMemory(rs))
          else None
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-get", s"Failed to get memory: ${e.getMessage}"))

  override def recall(
    filter: MemoryFilter = MemoryFilter.All,
    limit: Int = 100
  ): Result[Seq[Memory]] =
    Try {
      val (whereClause, params) = filterToSql(filter)
      val sql                   = s"SELECT * FROM memories $whereClause ORDER BY timestamp DESC LIMIT ?"
      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.setInt(params.length + 1, limit)
        Using.resource(stmt.executeQuery()) { rs =>
          Iterator.continually(rs).takeWhile(_.next()).map(rowToMemory).toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-recall", s"Failed to recall memories: ${e.getMessage}"))

  override def search(
    query: String,
    topK: Int = 10,
    filter: MemoryFilter = MemoryFilter.All
  ): Result[Seq[ScoredMemory]] =
    Try {
      // Use FTS5 for full-text search
      val (whereClause, params) = filterToSql(filter)

      // Build query to join with FTS table
      val sql = if (whereClause.isEmpty) {
        """SELECT m.*, bm25(memories_fts) as score
          |FROM memories m
          |JOIN memories_fts fts ON m.id = fts.id
          |WHERE fts.content MATCH ?
          |ORDER BY score
          |LIMIT ?""".stripMargin
      } else {
        val innerWhere = whereClause.replace("WHERE ", "")
        s"""SELECT m.*, bm25(memories_fts) as score
           |FROM memories m
           |JOIN memories_fts fts ON m.id = fts.id
           |WHERE ($innerWhere) AND fts.content MATCH ?
           |ORDER BY score
           |LIMIT ?""".stripMargin
      }

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.setString(params.length + 1, escapeFtsQuery(query))
        stmt.setInt(params.length + 2, topK)
        Using.resource(stmt.executeQuery()) { rs =>
          Iterator
            .continually(rs)
            .takeWhile(_.next())
            .map { row =>
              val memory = rowToMemory(row)
              val bm25   = row.getDouble("score")
              // Convert BM25 score (negative, lower is better) to 0-1 range
              val normScore = Math.max(0.0, Math.min(1.0, 1.0 / (1.0 + Math.abs(bm25))))
              ScoredMemory(memory, normScore)
            }
            .toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-search", s"Failed to search memories: ${e.getMessage}"))

  override def delete(id: MemoryId): Result[MemoryStore] =
    Try {
      // Delete from FTS first
      Using.resource(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?")) { stmt =>
        stmt.setString(1, id.value)
        stmt.executeUpdate()
      }
      // Delete from main table
      Using.resource(connection.prepareStatement("DELETE FROM memories WHERE id = ?")) { stmt =>
        stmt.setString(1, id.value)
        stmt.executeUpdate()
      }
      this
    }.toEither.left.map(e => ProcessingError("sqlite-delete", s"Failed to delete memory: ${e.getMessage}"))

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] =
    if (containsCustom(filter)) {
      // Custom predicates anywhere in tree cannot be translated to SQL; fallback to row-by-row
      deleteMatchingRowByRow(filter)
    } else {
      val (whereClause, params) = filterToSql(filter)
      if (whereClause.isEmpty) {
        // Empty WHERE would delete all rows; fallback to safe row-by-row
        deleteMatchingRowByRow(filter)
      } else {
        deleteMatchingBulk(whereClause, params)
      }
    }

  /** Check if filter tree contains any Custom predicates (which cannot be translated to SQL). */
  private def containsCustom(filter: MemoryFilter): Boolean = filter match {
    case _: MemoryFilter.Custom  => true
    case MemoryFilter.And(l, r)  => containsCustom(l) || containsCustom(r)
    case MemoryFilter.Or(l, r)   => containsCustom(l) || containsCustom(r)
    case MemoryFilter.Not(inner) => containsCustom(inner)
    case _                       => false
  }

  /** Fallback: recall matching memories via SQL (where possible), apply in-memory filter, delete one-by-one. */
  private def deleteMatchingRowByRow(filter: MemoryFilter): Result[MemoryStore] =
    for {
      memories <- recall(filter, Int.MaxValue)
      toDelete = memories.filter(filter.matches) // Re-apply filter for Custom predicates not handled by SQL
      _ <- toDelete.foldLeft[Result[Unit]](Right(())) { (acc, memory) =>
        acc.flatMap(_ => delete(memory.id).map(_ => ()))
      }
    } yield this

  /** Bulk delete with transaction: stream IDs, delete FTS entries row-by-row, bulk delete main table. */
  private def deleteMatchingBulk(whereClause: String, params: Seq[Any]): Result[MemoryStore] =
    Try {
      val wasAutoCommit = connection.getAutoCommit
      connection.setAutoCommit(false)
      try {
        // 1. Select IDs and delete FTS entries row-by-row (streaming, avoids materializing all IDs)
        Using.resource(connection.prepareStatement(s"SELECT id FROM memories $whereClause")) { selectStmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(selectStmt, idx + 1, param)
          }
          Using.resource(selectStmt.executeQuery()) { rs =>
            Using.resource(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?")) { deleteStmt =>
              while (rs.next()) {
                val id = rs.getString("id")
                deleteStmt.setString(1, id)
                deleteStmt.executeUpdate()
              }
            }
          }
        }

        // 2. Bulk delete from main table
        Using.resource(connection.prepareStatement(s"DELETE FROM memories $whereClause")) { stmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          stmt.executeUpdate()
        }

        connection.commit()
        this
      } catch {
        case e: Throwable =>
          connection.rollback()
          throw e
      } finally connection.setAutoCommit(wasAutoCommit)
    }.toEither.left.map(e =>
      ProcessingError("sqlite-delete-matching", s"Failed to delete matching memories: ${e.getMessage}")
    )

  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    for {
      maybeMemory <- get(id)
      memory <- maybeMemory.toRight(
        NotFoundError(s"Memory not found: ${id.value}", id.value)
      )
      updated = updateFn(memory)
      result <- store(updated)
    } yield result

  override def count(filter: MemoryFilter = MemoryFilter.All): Result[Long] =
    Try {
      val (whereClause, params) = filterToSql(filter)
      val sql                   = s"SELECT COUNT(*) FROM memories $whereClause"
      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) rs.getLong(1)
          else 0L
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-count", s"Failed to count memories: ${e.getMessage}"))

  override def clear(): Result[MemoryStore] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        stmt.executeUpdate("DELETE FROM memories_fts")
        stmt.executeUpdate("DELETE FROM memories")
      }
      this
    }.toEither.left.map(e => ProcessingError("sqlite-clear", s"Failed to clear memories: ${e.getMessage}"))

  override def recent(limit: Int = 10, filter: MemoryFilter = MemoryFilter.All): Result[Seq[Memory]] =
    recall(filter, limit)

  /**
   * Close the database connection.
   * Should be called when the store is no longer needed.
   */
  def close(): Unit =
    if (!connection.isClosed) {
      connection.close()
    }

  private def updateFtsIndex(memory: Memory): Unit = {
    // Delete existing entry
    Using.resource(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?")) { stmt =>
      stmt.setString(1, memory.id.value)
      stmt.executeUpdate()
    }
    // Insert new entry
    Using.resource(
      connection.prepareStatement("INSERT INTO memories_fts (id, content) VALUES (?, ?)")
    ) { stmt =>
      stmt.setString(1, memory.id.value)
      stmt.setString(2, memory.content)
      stmt.executeUpdate()
    }
  }

  private def filterToSql(filter: MemoryFilter): (String, Seq[Any]) = filter match {
    case MemoryFilter.All =>
      ("", Seq.empty)

    case MemoryFilter.None =>
      ("WHERE 1 = 0", Seq.empty)

    case MemoryFilter.ByType(memoryType) =>
      ("WHERE memory_type = ?", Seq(memoryTypeToString(memoryType)))

    case MemoryFilter.ByTypes(memoryTypes) =>
      val placeholders = memoryTypes.map(_ => "?").mkString(",")
      (s"WHERE memory_type IN ($placeholders)", memoryTypes.map(memoryTypeToString).toSeq)

    case MemoryFilter.ByConversation(conversationId) =>
      ("WHERE conversation_id = ?", Seq(conversationId))

    case MemoryFilter.ByEntity(entityId) =>
      ("WHERE entity_id = ?", Seq(entityId.value))

    case MemoryFilter.ByTimeRange(after, before) =>
      (after, before) match {
        case (Some(a), Some(b)) =>
          ("WHERE timestamp >= ? AND timestamp <= ?", Seq(a.toEpochMilli, b.toEpochMilli))
        case (Some(a), scala.None) =>
          ("WHERE timestamp >= ?", Seq(a.toEpochMilli))
        case (scala.None, Some(b)) =>
          ("WHERE timestamp <= ?", Seq(b.toEpochMilli))
        case (scala.None, scala.None) =>
          ("", Seq.empty)
      }

    case MemoryFilter.MinImportance(threshold) =>
      ("WHERE importance >= ?", Seq(threshold))

    case MemoryFilter.ByMetadata(key, value) =>
      ("WHERE json_extract(metadata_json, ?) = ?", Seq(s"$$.$key", value))

    case MemoryFilter.HasMetadata(key) =>
      ("WHERE json_extract(metadata_json, ?) IS NOT NULL", Seq(s"$$.$key"))

    case MemoryFilter.MetadataContains(key, substring) =>
      ("WHERE json_extract(metadata_json, ?) LIKE ?", Seq(s"$$.$key", s"%$substring%"))

    case MemoryFilter.ContentContains(substring, _) =>
      // Note: caseSensitive not easily supported in SQLite, using LIKE
      ("WHERE content LIKE ?", Seq(s"%$substring%"))

    case MemoryFilter.And(left, right) =>
      val (leftSql, leftParams)   = filterToSql(left)
      val (rightSql, rightParams) = filterToSql(right)
      val leftWhere               = leftSql.replace("WHERE ", "")
      val rightWhere              = rightSql.replace("WHERE ", "")
      if (leftWhere.isEmpty && rightWhere.isEmpty) ("", Seq.empty)
      else if (leftWhere.isEmpty) (rightSql, rightParams)
      else if (rightWhere.isEmpty) (leftSql, leftParams)
      else (s"WHERE ($leftWhere) AND ($rightWhere)", leftParams ++ rightParams)

    case MemoryFilter.Or(left, right) =>
      val (leftSql, leftParams)   = filterToSql(left)
      val (rightSql, rightParams) = filterToSql(right)
      val leftWhere               = leftSql.replace("WHERE ", "")
      val rightWhere              = rightSql.replace("WHERE ", "")
      if (leftWhere.isEmpty || rightWhere.isEmpty) ("", Seq.empty)
      else (s"WHERE ($leftWhere) OR ($rightWhere)", leftParams ++ rightParams)

    case MemoryFilter.Not(inner) =>
      val (innerSql, innerParams) = filterToSql(inner)
      val innerWhere              = innerSql.replace("WHERE ", "")
      if (innerWhere.isEmpty) ("", Seq.empty)
      else (s"WHERE NOT ($innerWhere)", innerParams)

    case MemoryFilter.Custom(_) =>
      // Custom predicates can't be translated to SQL - return all and filter in memory
      ("", Seq.empty)
  }

  private def setParameter(stmt: PreparedStatement, idx: Int, value: Any): Unit = value match {
    case s: String => stmt.setString(idx, s)
    case l: Long   => stmt.setLong(idx, l)
    case d: Double => stmt.setDouble(idx, d)
    case i: Int    => stmt.setInt(idx, i)
    case _         => stmt.setObject(idx, value)
  }

  private def rowToMemory(rs: ResultSet): Memory = {
    val importanceValue = rs.getDouble("importance")
    val importance      = if (rs.wasNull()) None else Some(importanceValue)
    val embeddingBytes  = Option(rs.getBytes("embedding_blob"))

    // Reconstruct metadata from stored JSON
    val storedMetadata = jsonToMetadata(rs.getString("metadata_json"))

    Memory(
      id = MemoryId(rs.getString("id")),
      content = rs.getString("content"),
      memoryType = stringToMemoryType(rs.getString("memory_type")),
      metadata = storedMetadata,
      timestamp = Instant.ofEpochMilli(rs.getLong("timestamp")),
      importance = importance,
      embedding = embeddingBytes.map(bytesToEmbedding)
    )
  }
}

object SQLiteMemoryStore {

  /**
   * Create a new SQLite memory store.
   *
   * @param dbPath Path to database file, or ":memory:" for in-memory database
   * @param config Store configuration
   * @return Initialized store or error
   */
  def apply(
    dbPath: String,
    config: MemoryStoreConfig = MemoryStoreConfig.default
  ): Result[SQLiteMemoryStore] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
      connection.setAutoCommit(true)
      initializeSchema(connection)
      new SQLiteMemoryStore(dbPath, config, connection)
    }.toEither.left.map {
      case e: ClassNotFoundException =>
        ConfigurationError(s"SQLite JDBC driver not found: ${e.getMessage}")
      case e: SQLException =>
        ProcessingError("sqlite-open", s"Failed to open SQLite database: ${e.getMessage}")
      case e: Throwable =>
        ProcessingError("sqlite-init", s"Unexpected error creating SQLite store: ${e.getMessage}")
    }

  /**
   * Create an in-memory SQLite store for testing.
   */
  def inMemory(config: MemoryStoreConfig = MemoryStoreConfig.testing): Result[SQLiteMemoryStore] =
    apply(":memory:", config)

  /**
   * Schema version for migrations.
   */
  val SchemaVersion: Int = 1

  private def initializeSchema(connection: Connection): Unit = {
    val statements = Seq(
      // Main memories table
      """CREATE TABLE IF NOT EXISTS memories (
        |  id TEXT PRIMARY KEY,
        |  content TEXT NOT NULL,
        |  memory_type TEXT NOT NULL,
        |  timestamp INTEGER NOT NULL,
        |  importance REAL,
        |  conversation_id TEXT,
        |  entity_id TEXT,
        |  source TEXT,
        |  metadata_json TEXT DEFAULT '{}',
        |  embedding_blob BLOB
        |)""".stripMargin,
      // Indexes for common queries
      "CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(memory_type)",
      "CREATE INDEX IF NOT EXISTS idx_memories_conversation ON memories(conversation_id)",
      "CREATE INDEX IF NOT EXISTS idx_memories_entity ON memories(entity_id)",
      "CREATE INDEX IF NOT EXISTS idx_memories_timestamp ON memories(timestamp DESC)",
      "CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance DESC)",
      // FTS5 virtual table for full-text search (standalone, not external content)
      """CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
        |  id UNINDEXED,
        |  content
        |)""".stripMargin,
      // Schema version tracking
      """CREATE TABLE IF NOT EXISTS schema_version (
        |  version INTEGER PRIMARY KEY
        |)""".stripMargin,
      s"INSERT OR IGNORE INTO schema_version (version) VALUES ($SchemaVersion)"
    )

    Using.resource(connection.createStatement())(stmt => statements.foreach(stmt.executeUpdate))
  }

  private def memoryTypeToString(mt: MemoryType): String = mt match {
    case MemoryType.Conversation => "conversation"
    case MemoryType.Entity       => "entity"
    case MemoryType.Knowledge    => "knowledge"
    case MemoryType.UserFact     => "user_fact"
    case MemoryType.Task         => "task"
    case MemoryType.Custom(name) => s"custom:$name"
  }

  private def stringToMemoryType(s: String): MemoryType = s match {
    case "conversation" => MemoryType.Conversation
    case "entity"       => MemoryType.Entity
    case "knowledge"    => MemoryType.Knowledge
    case "user_fact"    => MemoryType.UserFact
    case "task"         => MemoryType.Task
    case custom if custom.startsWith("custom:") =>
      MemoryType.Custom(custom.stripPrefix("custom:"))
    case other => MemoryType.Custom(other)
  }

  private def metadataToJson(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else {
      val entries = metadata.map { case (k, v) =>
        s""""${escapeJson(k)}":"${escapeJson(v)}""""
      }
      s"{${entries.mkString(",")}}"
    }

  private def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else {
      // Simple JSON parsing for flat string maps
      val content = json.trim.stripPrefix("{").stripSuffix("}")
      if (content.isEmpty) Map.empty
      else {
        content
          .split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
          .flatMap { pair =>
            val parts = pair.split(":", 2)
            if (parts.length == 2) {
              val key   = parts(0).trim.stripPrefix("\"").stripSuffix("\"")
              val value = parts(1).trim.stripPrefix("\"").stripSuffix("\"")
              Some(unescapeJson(key) -> unescapeJson(value))
            } else None
          }
          .toMap
      }
    }

  private def escapeJson(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")

  private def unescapeJson(s: String): String =
    s.replace("\\\"", "\"")
      .replace("\\\\", "\\")
      .replace("\\n", "\n")
      .replace("\\r", "\r")
      .replace("\\t", "\t")

  private def embeddingToBytes(embedding: Array[Float]): Array[Byte] = {
    val buffer = java.nio.ByteBuffer.allocate(embedding.length * 4)
    embedding.foreach(buffer.putFloat)
    buffer.array()
  }

  private def bytesToEmbedding(bytes: Array[Byte]): Array[Float] = {
    val buffer = java.nio.ByteBuffer.wrap(bytes)
    Array.fill(bytes.length / 4)(buffer.getFloat)
  }

  private def escapeFtsQuery(query: String): String = {
    // Split into words and create OR query for FTS5
    // FTS5 uses implicit AND, so we use OR for more flexible matching
    val words = query
      .replace("\"", "")
      .replace("*", "")
      .replace(":", " ")
      .replace("(", " ")
      .replace(")", " ")
      .split("\\s+")
      .filter(_.nonEmpty)
      .map(w => s""""$w"""")

    if (words.isEmpty) "\"\""
    else words.mkString(" OR ")
  }
}
