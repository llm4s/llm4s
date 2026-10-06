// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch, DisableSyntax.NoKeywordFinally
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
  import FilterSupport.Sql

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
      // SQL narrows, `matches` decides: what SQL cannot express exactly (a Custom predicate is code) is decided on
      // the rows read, newest first, so the limit applies to the memories that match and not to the newest overall.
      val plan = narrowing(filter)
      val sql =
        if (plan.needsMatches) s"SELECT * FROM memories ${plan.where} ORDER BY timestamp DESC"
        else s"SELECT * FROM memories ${plan.where} ORDER BY timestamp DESC LIMIT ?"
      Using.resource(connection.prepareStatement(sql)) { stmt =>
        plan.params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        if (!plan.needsMatches) stmt.setInt(plan.params.length + 1, limit)
        Using.resource(stmt.executeQuery()) { rs =>
          val memories = Iterator.continually(rs).takeWhile(_.next()).map(rowToMemory)
          (if (plan.needsMatches) memories.filter(filter.matches).take(limit) else memories).toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-recall", s"Failed to recall memories: ${e.getMessage}"))

  override def search(
    query: String,
    topK: Int = 10,
    filter: MemoryFilter = MemoryFilter.All
  ): Result[Seq[ScoredMemory]] =
    Try {
      // Use FTS5 for full-text search. What SQL cannot narrow exactly is decided by `matches` after the full-text
      // match and before topK, so the limit is applied to the memories the filter accepts.
      val plan = narrowing(filter)

      // Build query to join with FTS table
      val sql = if (plan.where.isEmpty) {
        """SELECT m.*, bm25(memories_fts) as score
          |FROM memories m
          |JOIN memories_fts fts ON m.id = fts.id
          |WHERE fts.content MATCH ?
          |ORDER BY score
          |LIMIT ?""".stripMargin
      } else {
        // Filter in a subquery: a bare `content` in the filter would be ambiguous with the FTS table's column
        s"""SELECT m.*, bm25(memories_fts) as score
           |FROM (SELECT * FROM memories ${plan.where}) m
           |JOIN memories_fts fts ON m.id = fts.id
           |WHERE fts.content MATCH ?
           |ORDER BY score
           |LIMIT ?""".stripMargin
      }

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        plan.params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.setString(plan.params.length + 1, escapeFtsQuery(query))
        // SQLite: a negative LIMIT means no limit
        stmt.setInt(plan.params.length + 2, if (plan.needsMatches) -1 else topK)
        Using.resource(stmt.executeQuery()) { rs =>
          val scored = Iterator
            .continually(rs)
            .takeWhile(_.next())
            .map { row =>
              val memory = rowToMemory(row)
              val bm25   = row.getDouble("score")
              // Convert BM25 score (negative, lower is better) to 0-1 range
              val normScore = Math.max(0.0, Math.min(1.0, 1.0 / (1.0 + Math.abs(bm25))))
              ScoredMemory(memory, normScore)
            }
          (if (plan.needsMatches) scored.filter(sm => filter.matches(sm.memory)).take(topK) else scored).toSeq
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

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] = {
    val plan = narrowing(filter)
    if (plan.needsMatches || plan.where.isEmpty) {
      // SQL cannot say which rows match (or no row is excluded): read the rows `matches` accepts, then delete them
      deleteMatchingRowByRow(filter)
    } else {
      deleteMatchingBulk(plan.where, plan.params)
    }
  }

  /** Delete the memories `recall` returns, all in one transaction: the whole delete happens or none of it. */
  private def deleteMatchingRowByRow(filter: MemoryFilter): Result[MemoryStore] =
    recall(filter, Int.MaxValue).flatMap { memories =>
      Try {
        inTransaction {
          Using.resource(connection.prepareStatement("DELETE FROM memories_fts WHERE id = ?")) { fts =>
            Using.resource(connection.prepareStatement("DELETE FROM memories WHERE id = ?")) { main =>
              memories.foreach { memory =>
                fts.setString(1, memory.id.value)
                fts.executeUpdate()
                main.setString(1, memory.id.value)
                main.executeUpdate()
              }
            }
          }
        }
        this: MemoryStore
      }.toEither.left.map(e =>
        ProcessingError("sqlite-delete-matching", s"Failed to delete matching memories: ${e.getMessage}")
      )
    }

  /** Bulk delete with transaction: stream IDs, delete FTS entries row-by-row, bulk delete main table. */
  private def deleteMatchingBulk(whereClause: String, params: Seq[Any]): Result[MemoryStore] =
    Try {
      inTransaction {
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
      }
      this: MemoryStore
    }.toEither.left.map(e =>
      ProcessingError("sqlite-delete-matching", s"Failed to delete matching memories: ${e.getMessage}")
    )

  /**
   * Run `body` as one transaction: commit if it returns, roll back if it throws, and always restore autocommit,
   * even if the commit or the rollback fails.
   */
  private def inTransaction[A](body: => A): A = {
    val wasAutoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    val outcome = Try {
      val result = body
      connection.commit()
      result
    }
    if (outcome.isFailure) Try(connection.rollback())
    Try(connection.setAutoCommit(wasAutoCommit))
    outcome.get
  }

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
      val plan = narrowing(filter)
      if (plan.needsMatches) {
        // SQL cannot say which rows match exactly: count the rows `matches` accepts among those read.
        Using.resource(connection.prepareStatement(s"SELECT * FROM memories ${plan.where}")) { stmt =>
          plan.params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          Using.resource(stmt.executeQuery()) { rs =>
            Iterator.continually(rs).takeWhile(_.next()).map(rowToMemory).count(filter.matches).toLong
          }
        }
      } else {
        val sql = s"SELECT COUNT(*) FROM memories ${plan.where}"
        Using.resource(connection.prepareStatement(sql)) { stmt =>
          plan.params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) rs.getLong(1)
            else 0L
          }
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

  private def narrowing(filter: MemoryFilter): FilterSupport.Narrowing =
    FilterSupport.narrow(filter)(leafSql)

  /**
   * The SQL of one filter, when it means exactly what `matches` means: a row satisfies it if and only if `matches`
   * accepts the row, and it is never NULL (a NULL under `NOT` would drop a row `Not` accepts), hence the `COALESCE`
   * around every comparison with a nullable column.
   */
  private def leafSql(filter: MemoryFilter): Option[FilterSupport.Sql] = filter match {
    case MemoryFilter.ByType(memoryType) =>
      Some(Sql("memory_type = ?", Seq(memoryTypeToString(memoryType))))

    case MemoryFilter.ByTypes(memoryTypes) =>
      // A Seq, not the Set: mapping a Set to "?" would collapse the placeholders into one
      val names = memoryTypes.toSeq.map(memoryTypeToString)
      if (names.isEmpty) Some(Sql("1 = 0", Seq.empty))
      else Some(Sql(s"memory_type IN (${names.map(_ => "?").mkString(",")})", names))

    case MemoryFilter.ByConversation(conversationId) =>
      Some(Sql("COALESCE(conversation_id = ?, 0)", Seq(conversationId)))

    case MemoryFilter.ByEntity(entityId) =>
      Some(Sql("COALESCE(entity_id = ?, 0)", Seq(entityId.value)))

    case MemoryFilter.ByTimeRange(after, before) =>
      (after, before) match {
        case (Some(a), Some(b)) => Some(Sql("timestamp >= ? AND timestamp <= ?", Seq(a.toEpochMilli, b.toEpochMilli)))
        case (Some(a), scala.None)    => Some(Sql("timestamp >= ?", Seq(a.toEpochMilli)))
        case (scala.None, Some(b))    => Some(Sql("timestamp <= ?", Seq(b.toEpochMilli)))
        case (scala.None, scala.None) => Some(FilterSupport.unrestricted)
      }

    case MemoryFilter.MinImportance(threshold) =>
      Some(Sql("COALESCE(importance >= ?, 0)", Seq(threshold)))

    case MemoryFilter.ByMetadata(key, value) =>
      jsonPath(key).map(path => Sql("COALESCE(json_extract(metadata_json, ?) = ?, 0)", Seq(path, value)))

    case MemoryFilter.HasMetadata(key) =>
      jsonPath(key).map(path => Sql("json_extract(metadata_json, ?) IS NOT NULL", Seq(path)))

    case MemoryFilter.MetadataContains(key, substring) =>
      // instr is a literal, case-sensitive substring test, like String.contains: LIKE would read % and _ as wildcards
      jsonPath(key).map(path => Sql("COALESCE(instr(json_extract(metadata_json, ?), ?) > 0, 0)", Seq(path, substring)))

    case MemoryFilter.ContentContains(substring, caseSensitive) =>
      // instr is a literal substring test: LIKE would read % and _ as wildcards and is never case sensitive
      if (caseSensitive) Some(Sql("instr(content, ?) > 0", Seq(substring)))
      else Some(Sql(s"instr(${FilterSupport.JavaLower}(content), ${FilterSupport.JavaLower}(?)) > 0", Seq(substring)))

    case _ => scala.None // And, Or, Not, All, None and Custom are narrowed by FilterSupport, not here
  }

  /**
   * The JSON path of a metadata key, quoted so that a `.` or `[` in the key is part of the name and not a path
   * step. A key that cannot be quoted this way (empty, or holding a quote, a backslash or a control character)
   * has no exact path: `None`, and `matches` decides.
   */
  private def jsonPath(key: String): Option[String] =
    if (key.nonEmpty && key.forall(c => c != '"' && c != '\\' && c >= ' ')) Some("$.\"" + key + "\"")
    else scala.None

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
    open(dbPath, config, path => DriverManager.getConnection(s"jdbc:sqlite:$path"))

  /**
   * Open the store on a connection from `connect`. The seam exists so a test can prove that a failed open
   * closes the connection it made.
   */
  private[memory] def open(
    dbPath: String,
    config: MemoryStoreConfig,
    connect: String => Connection
  ): Result[SQLiteMemoryStore] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = connect(dbPath)
      // If setting up the schema fails (e.g. the file is not a database) the connection must not leak: an open
      // handle keeps the file locked, which blocks deleting it on Windows.
      Try {
        connection.setAutoCommit(true)
        FilterSupport.registerJavaLower(connection)
        initializeSchema(connection)
        new SQLiteMemoryStore(dbPath, config, connection)
      }.recoverWith { case e =>
        Try(connection.close())
        scala.util.Failure(e)
      }.get
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
    else ujson.write(ujson.Obj.from(metadata.map { case (k, v) => k -> ujson.Str(v) }))

  /**
   * Metadata from its stored JSON. A real JSON parser: the hand-written decoder this replaced undid its escapes one
   * after another, so a stored `c:\temp` came back as `c:`, a tab and `emp`. Rows written by that encoder are valid
   * JSON, so they read correctly now. Text that is not JSON at all (a control character other than tab, newline
   * or carriage return was written raw) still goes through the old lenient reading.
   */
  private def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else
      Try(ujson.read(json).obj.collect { case (k, ujson.Str(v)) => k -> v }.toMap)
        .getOrElse(lenientJsonToMetadata(json))

  private def lenientJsonToMetadata(json: String): Map[String, String] = {
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
