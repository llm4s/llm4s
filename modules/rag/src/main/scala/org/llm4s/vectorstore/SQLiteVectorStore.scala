package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError

import org.sqlite.Function

import java.sql.{ Connection, DriverManager, PreparedStatement, ResultSet }
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

/**
 * SQLite-based vector store implementation.
 *
 * Uses SQLite for storage with in-memory cosine similarity computation.
 * Suitable for development, testing, and small-to-medium datasets
 * (up to ~100K vectors depending on dimensions).
 *
 * Features:
 * - File-based or in-memory storage
 * - FTS5 full-text search fallback
 * - ACID transactions
 * - No external dependencies beyond SQLite
 *
 * Limitations:
 * - Vector similarity computed in Scala (not accelerated)
 * - All embeddings loaded into memory for search
 * - No built-in sharding or replication
 *
 * For production with larger datasets, consider pgvector or Qdrant.
 *
 * @param dbPath Path to SQLite database file
 * @param connection The database connection
 */
final class SQLiteVectorStore private (
  val dbPath: String,
  private val connection: Connection
) extends VectorStore {

  // Initialize schema on creation
  initializeSchema()

  private def initializeSchema(): Unit =
    Using.resource(connection.createStatement()) { stmt =>
      // Main vectors table
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS vectors (
          |  id TEXT PRIMARY KEY,
          |  embedding BLOB NOT NULL,
          |  embedding_dim INTEGER NOT NULL,
          |  content TEXT,
          |  metadata TEXT,
          |  created_at INTEGER NOT NULL
          |)""".stripMargin
      )

      // Indexes for efficient queries
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_vectors_dim ON vectors(embedding_dim)")
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_vectors_created ON vectors(created_at)")

      // FTS5 for text search (optional content column)
      stmt.execute(
        """CREATE VIRTUAL TABLE IF NOT EXISTS vectors_fts USING fts5(
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

  override def upsert(record: VectorRecord): Result[Unit] =
    Try {
      val sql =
        """INSERT OR REPLACE INTO vectors
          |(id, embedding, embedding_dim, content, metadata, created_at)
          |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        stmt.setString(1, record.id)
        stmt.setBytes(2, SQLiteVectorStore.serializeEmbedding(record.embedding))
        stmt.setInt(3, record.dimensions)
        record.content match {
          case Some(c) => stmt.setString(4, c)
          case None    => stmt.setNull(4, java.sql.Types.VARCHAR)
        }
        stmt.setString(5, SQLiteVectorStore.serializeMetadata(record.metadata))
        stmt.setLong(6, System.currentTimeMillis())
        stmt.executeUpdate()
        ()
      }

      // Update FTS index if content present
      record.content.foreach(content => updateFtsIndex(record.id, content))
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to upsert: ${e.getMessage}"))

  override def upsertBatch(records: Seq[VectorRecord]): Result[Unit] =
    if (records.isEmpty) Right(())
    else {
      val result = withTransaction {
        val sql =
          """INSERT OR REPLACE INTO vectors
            |(id, embedding, embedding_dim, content, metadata, created_at)
            |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin

        Using.resource(connection.prepareStatement(sql)) { stmt =>
          val now = System.currentTimeMillis()
          records.foreach { record =>
            stmt.setString(1, record.id)
            stmt.setBytes(2, SQLiteVectorStore.serializeEmbedding(record.embedding))
            stmt.setInt(3, record.dimensions)
            record.content match {
              case Some(c) => stmt.setString(4, c)
              case None    => stmt.setNull(4, java.sql.Types.VARCHAR)
            }
            stmt.setString(5, SQLiteVectorStore.serializeMetadata(record.metadata))
            stmt.setLong(6, now)
            stmt.addBatch()
          }
          stmt.executeBatch()
        }

        // Update FTS index for records with content
        records.foreach(record => record.content.foreach(content => updateFtsIndex(record.id, content)))
      }
      result.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to upsert batch: ${e.getMessage}"))
    }

  override def search(
    queryVector: Array[Float],
    topK: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[ScoredRecord]] = {
    // Fail-fast: Check dimension compatibility before loading vectors
    // O(1) query using indexed column (idx_vectors_dim)
    val storedDimOpt = Try {
      Using.resource(connection.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery("SELECT embedding_dim FROM vectors LIMIT 1")) { rs =>
          if (rs.next()) Some(rs.getInt(1))
          else None
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to check dimensions: ${e.getMessage}"))

    storedDimOpt match {
      case Left(error) => Left(error)
      case Right(Some(storedDim)) if storedDim != queryVector.length =>
        Left(
          ProcessingError(
            "sqlite-vector-store",
            s"Dimension mismatch: query vector has ${queryVector.length} dimensions, but stored vectors have $storedDim dimensions"
          )
        )
      case Right(_) =>
        // Empty store or matching dimensions - proceed with search
        Try {
          val (whereClause, params) = filter.map(filterToSql).getOrElse(("1=1", Seq.empty))
          val sql                   = s"SELECT * FROM vectors WHERE $whereClause"

          Using.resource(connection.prepareStatement(sql)) { stmt =>
            params.zipWithIndex.foreach { case (param, idx) =>
              setParameter(stmt, idx + 1, param)
            }

            Using.resource(stmt.executeQuery()) { rs =>
              val candidates = ArrayBuffer.empty[(VectorRecord, Array[Float])]

              while (rs.next()) {
                val record    = rowToRecord(rs)
                val embedding = record.embedding
                if (embedding.nonEmpty) {
                  candidates += ((record, embedding))
                }
              }

              // Calculate cosine similarities and return top-K
              candidates
                .map { case (record, embedding) =>
                  val similarity = cosineSimilarity(queryVector, embedding)
                  // Normalize to 0-1 range (cosine similarity is -1 to 1)
                  val normalizedScore = (similarity + 1) / 2
                  ScoredRecord(record, normalizedScore)
                }
                .sortBy(-_.score)
                .take(topK)
                .toSeq
            }
          }
        }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Search failed: ${e.getMessage}"))
    }
  }

  override def get(id: String): Result[Option[VectorRecord]] =
    Try {
      Using.resource(connection.prepareStatement("SELECT * FROM vectors WHERE id = ?")) { stmt =>
        stmt.setString(1, id)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Some(rowToRecord(rs))
          else None
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to get: ${e.getMessage}"))

  override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] =
    if (ids.isEmpty) Right(Seq.empty)
    else {
      Try {
        val placeholders = ids.map(_ => "?").mkString(",")
        val sql          = s"SELECT * FROM vectors WHERE id IN ($placeholders)"

        Using.resource(connection.prepareStatement(sql)) { stmt =>
          ids.zipWithIndex.foreach { case (id, idx) =>
            stmt.setString(idx + 1, id)
          }
          Using.resource(stmt.executeQuery()) { rs =>
            val records = ArrayBuffer.empty[VectorRecord]
            while (rs.next())
              records += rowToRecord(rs)
            records.toSeq
          }
        }
      }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to get batch: ${e.getMessage}"))
    }

  override def delete(id: String): Result[Unit] =
    Try {
      // Delete from FTS
      Using.resource(connection.prepareStatement("DELETE FROM vectors_fts WHERE id = ?")) { stmt =>
        stmt.setString(1, id)
        stmt.executeUpdate()
      }

      // Delete from main table
      Using.resource(connection.prepareStatement("DELETE FROM vectors WHERE id = ?")) { stmt =>
        stmt.setString(1, id)
        stmt.executeUpdate()
        ()
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to delete: ${e.getMessage}"))

  override def deleteBatch(ids: Seq[String]): Result[Unit] =
    if (ids.isEmpty) Right(())
    else {
      Try {
        val placeholders = ids.map(_ => "?").mkString(",")

        // Delete from FTS
        Using.resource(connection.prepareStatement(s"DELETE FROM vectors_fts WHERE id IN ($placeholders)")) { stmt =>
          ids.zipWithIndex.foreach { case (id, idx) =>
            stmt.setString(idx + 1, id)
          }
          stmt.executeUpdate()
        }

        // Delete from main table
        Using.resource(connection.prepareStatement(s"DELETE FROM vectors WHERE id IN ($placeholders)")) { stmt =>
          ids.zipWithIndex.foreach { case (id, idx) =>
            stmt.setString(idx + 1, id)
          }
          stmt.executeUpdate()
          ()
        }
      }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to delete batch: ${e.getMessage}"))
    }

  override def deleteByPrefix(prefix: String): Result[Long] =
    Try {
      // First get IDs to delete
      val ids = Using.resource(
        connection.prepareStatement("SELECT id FROM vectors WHERE id GLOB ?")
      ) { stmt =>
        stmt.setString(1, SqlLikePattern.globPrefix(prefix))
        Using.resource(stmt.executeQuery()) { rs =>
          val buffer = ArrayBuffer.empty[String]
          while (rs.next())
            buffer += rs.getString("id")
          buffer.toSeq
        }
      }

      if (ids.isEmpty) {
        0L
      } else {
        val placeholders = ids.map(_ => "?").mkString(",")

        // Delete from FTS
        Using.resource(connection.prepareStatement(s"DELETE FROM vectors_fts WHERE id IN ($placeholders)")) { stmt =>
          ids.zipWithIndex.foreach { case (id, idx) =>
            stmt.setString(idx + 1, id)
          }
          stmt.executeUpdate()
        }

        // Delete from main table
        Using.resource(connection.prepareStatement(s"DELETE FROM vectors WHERE id IN ($placeholders)")) { stmt =>
          ids.zipWithIndex.foreach { case (id, idx) =>
            stmt.setString(idx + 1, id)
          }
          stmt.executeUpdate().toLong
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to delete by prefix: ${e.getMessage}"))

  override def deleteByFilter(filter: MetadataFilter): Result[Long] =
    Try {
      val (whereClause, params) = filterToSql(filter)

      // First get IDs to delete from FTS
      val ids = Using.resource(connection.prepareStatement(s"SELECT id FROM vectors WHERE $whereClause")) { stmt =>
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
        Using.resource(connection.prepareStatement("DELETE FROM vectors_fts WHERE id = ?")) { stmt =>
          stmt.setString(1, id)
          stmt.executeUpdate()
        }
      }

      // Delete from main table
      val deleteSql = s"DELETE FROM vectors WHERE $whereClause"
      Using.resource(connection.prepareStatement(deleteSql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.executeUpdate().toLong
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to delete by filter: ${e.getMessage}"))

  override def count(filter: Option[MetadataFilter]): Result[Long] =
    Try {
      val (whereClause, params) = filter.map(filterToSql).getOrElse(("1=1", Seq.empty))
      val sql                   = s"SELECT COUNT(*) FROM vectors WHERE $whereClause"

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        Using.resource(stmt.executeQuery()) { rs =>
          rs.next()
          rs.getLong(1)
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to count: ${e.getMessage}"))

  override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
    Try {
      val (whereClause, params) = filter.map(filterToSql).getOrElse(("1=1", Seq.empty))
      val sql                   = s"SELECT * FROM vectors WHERE $whereClause ORDER BY created_at DESC LIMIT ? OFFSET ?"

      Using.resource(connection.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          setParameter(stmt, idx + 1, param)
        }
        stmt.setInt(params.size + 1, limit)
        stmt.setInt(params.size + 2, offset)

        Using.resource(stmt.executeQuery()) { rs =>
          val records = ArrayBuffer.empty[VectorRecord]
          while (rs.next())
            records += rowToRecord(rs)
          records.toSeq
        }
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to list: ${e.getMessage}"))

  override def clear(): Result[Unit] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        stmt.execute("DELETE FROM vectors")
        stmt.execute("DELETE FROM vectors_fts")
        () // Return Unit explicitly
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to clear: ${e.getMessage}"))

  override def stats(): Result[VectorStoreStats] =
    Try {
      Using.resource(connection.createStatement()) { stmt =>
        val total = Using.resource(stmt.executeQuery("SELECT COUNT(*) FROM vectors")) { rs =>
          rs.next()
          rs.getLong(1)
        }

        val dims = Using.resource(stmt.executeQuery("SELECT DISTINCT embedding_dim FROM vectors")) { rs =>
          val buffer = ArrayBuffer.empty[Int]
          while (rs.next())
            buffer += rs.getInt(1)
          buffer.toSet
        }

        // Approximate size (SQLite page_count * page_size)
        val size = Try {
          val pageCount = Using.resource(stmt.executeQuery("PRAGMA page_count")) { rs =>
            rs.next()
            rs.getLong(1)
          }
          val pageSize = Using.resource(stmt.executeQuery("PRAGMA page_size")) { rs =>
            rs.next()
            rs.getLong(1)
          }
          Some(pageCount * pageSize)
        }.getOrElse(None)

        VectorStoreStats(
          totalRecords = total,
          dimensions = dims,
          sizeBytes = size
        )
      }
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to get stats: ${e.getMessage}"))

  override def close(): Unit =
    if (!connection.isClosed) {
      connection.close()
    }

  // ============================================================
  // Helper Methods
  // ============================================================

  /**
   * Execute a block within a transaction, committing on success or rolling back on failure.
   * Uses Try for clean error handling.
   */
  private def withTransaction[A](block: => A): Either[Throwable, A] = {
    connection.setAutoCommit(false)
    val result = Try(block).toEither
    result match {
      case Right(_) =>
        connection.commit()
        connection.setAutoCommit(true)
      case Left(_) =>
        connection.rollback()
        connection.setAutoCommit(true)
    }
    result
  }

  private def updateFtsIndex(id: String, content: String): Unit = {
    // Delete existing entry if any
    Using.resource(connection.prepareStatement("DELETE FROM vectors_fts WHERE id = ?")) { stmt =>
      stmt.setString(1, id)
      stmt.executeUpdate()
    }

    // Insert new entry
    Using.resource(connection.prepareStatement("INSERT INTO vectors_fts (id, content) VALUES (?, ?)")) { stmt =>
      stmt.setString(1, id)
      stmt.setString(2, content)
      stmt.executeUpdate()
    }
  }

  private def rowToRecord(rs: ResultSet): VectorRecord = {
    val embeddingBytes = rs.getBytes("embedding")
    val embedding      = SQLiteVectorStore.deserializeEmbedding(embeddingBytes)
    val content        = Option(rs.getString("content")).filter(_.nonEmpty)
    val metadata       = SQLiteVectorStore.deserializeMetadata(rs.getString("metadata"))

    VectorRecord(
      id = rs.getString("id"),
      embedding = embedding,
      content = content,
      metadata = metadata
    )
  }

  /**
   * Whether the metadata has an entry with the bound key that meets `condition`. The key is compared as a value, not
   * as a JSON path, which cannot name every key. Rows an earlier version wrote are read by the same Scala code as
   * `rowToRecord` uses, so a filter sees what a read returns.
   */
  private def metadataEntry(condition: String): String =
    s"EXISTS (SELECT 1 FROM json_each(${SQLiteVectorStore.metadataJson}) WHERE key = ? AND type = 'text' AND $condition)"

  private def filterToSql(filter: MetadataFilter): (String, Seq[Any]) = filter match {
    case MetadataFilter.All => ("1=1", Seq.empty)

    case MetadataFilter.Equals(key, value) =>
      (metadataEntry("value = ?"), Seq(key, value))

    case MetadataFilter.Contains(key, substring) =>
      // instr is a literal, case-sensitive substring test: LIKE would read % and _ as wildcards
      (metadataEntry("instr(value, ?) > 0"), Seq(key, substring))

    case MetadataFilter.HasKey(key) =>
      (metadataEntry("1=1"), Seq(key))

    case MetadataFilter.In(_, values) if values.isEmpty =>
      ("1=0", Seq.empty)

    case MetadataFilter.In(key, values) =>
      (metadataEntry(s"value IN (${Seq.fill(values.size)("?").mkString(",")})"), Seq(key) ++ values.toSeq)

    case MetadataFilter.And(left, right) =>
      val (leftSql, leftParams)   = filterToSql(left)
      val (rightSql, rightParams) = filterToSql(right)
      (s"($leftSql AND $rightSql)", leftParams ++ rightParams)

    case MetadataFilter.Or(left, right) =>
      val (leftSql, leftParams)   = filterToSql(left)
      val (rightSql, rightParams) = filterToSql(right)
      (s"($leftSql OR $rightSql)", leftParams ++ rightParams)

    case MetadataFilter.Not(inner) =>
      val (innerSql, innerParams) = filterToSql(inner)
      (s"NOT ($innerSql)", innerParams)
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

  private def cosineSimilarity(a: Array[Float], b: Array[Float]): Double = {
    if (a.length != b.length || a.isEmpty) return 0.0

    var dotProduct = 0.0
    var normA      = 0.0
    var normB      = 0.0

    var i = 0
    while (i < a.length) {
      dotProduct += a(i) * b(i)
      normA += a(i) * a(i)
      normB += b(i) * b(i)
      i += 1
    }

    val denominator = math.sqrt(normA) * math.sqrt(normB)
    if (denominator == 0.0) 0.0 else dotProduct / denominator
  }
}

object SQLiteVectorStore {

  /**
   * Create a file-based SQLite vector store.
   *
   * @param dbPath Path to SQLite database file
   * @return The vector store or error
   */
  def apply(dbPath: String): Result[SQLiteVectorStore] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
      openOn(dbPath, connection)
    }.toEither.left.map(e => ProcessingError("sqlite-vector-store", s"Failed to create store: ${e.getMessage}"))

  /**
   * Create an in-memory SQLite vector store.
   *
   * Useful for testing or temporary storage.
   *
   * @return The vector store or error
   */
  def inMemory(): Result[SQLiteVectorStore] =
    Try {
      Class.forName("org.sqlite.JDBC")
      val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
      openOn(":memory:", connection)
    }.toEither.left.map(e =>
      ProcessingError("sqlite-vector-store", s"Failed to create in-memory store: ${e.getMessage}")
    )

  private def openOn(dbPath: String, connection: Connection): SQLiteVectorStore = {
    connection.setAutoCommit(true)
    Function.create(connection, OldMetadataJson, oldMetadataJson, 1, Function.FLAG_DETERMINISTIC)
    new SQLiteVectorStore(dbPath, connection)
  }

  // ============================================================
  // Serialization Helpers
  // ============================================================

  /**
   * Starts the metadata text of every row written since 0.6.0. Up to then the text began with `{` and nothing in it was
   * escaped (#2759): parsing it as JSON would turn the `\n` of `{"path":"C:\new"}` into a line break.
   */
  private val JsonMarker = " "

  /** The SQL function giving the JSON of metadata an earlier version wrote. */
  private val OldMetadataJson = "llm4s_old_metadata_json"

  private def oldMetadataJson: Function = new Function {
    override protected def xFunc(): Unit =
      result(ujson.write(toJson(deserializeOldMetadata(value_text(0)))))
  }

  /** SQL for a row's metadata as JSON, whichever version wrote it. */
  private val metadataJson =
    s"CASE WHEN substr(metadata, 1, 1) = '$JsonMarker' AND json_valid(metadata) THEN metadata ELSE $OldMetadataJson(metadata) END"

  private def toJson(metadata: Map[String, String]): ujson.Obj =
    ujson.Obj.from(metadata.map { case (k, v) => k -> ujson.Str(String.valueOf(v)) })

  private[vectorstore] def serializeMetadata(metadata: Map[String, String]): String =
    JsonMarker + ujson.write(toJson(metadata))

  private[vectorstore] def deserializeMetadata(text: String): Map[String, String] =
    if (text != null && text.startsWith(JsonMarker)) parseJson(text).getOrElse(Map.empty)
    else deserializeOldMetadata(text)

  private def parseJson(text: String): Option[Map[String, String]] =
    Try(ujson.read(text).obj.collect { case (k, ujson.Str(v)) => k -> v }.toMap).toOption

  /** Metadata an earlier version wrote. JSON with no backslash and no repeated key means the same without escaping. */
  private def deserializeOldMetadata(text: String): Map[String, String] =
    if (text == null) Map.empty
    else if (text.contains('\\') || repeatsKey(text)) parseUnescaped(text)
    else parseJson(text).getOrElse(parseUnescaped(text))

  /**
   * Whether JSON holding only strings, with no escapes, has a key twice: it has four quotes an entry. Earlier versions
   * wrote a `Map`, so there the second is part of a value.
   */
  private def repeatsKey(text: String): Boolean =
    Try(ujson.read(text).obj).toOption.exists { entries =>
      entries.values.forall(_.strOpt.isDefined) && text.count(_ == '"') != entries.size * 4
    }

  /**
   * Reads `{"key":"value","key2":"value2"}` with nothing escaped. A key ends at the first `":"`, and a value at the
   * first `","` that a key not seen before and `":"` follow: quotes that are part of the text in that arrangement
   * cannot be told from separators.
   */
  private def parseUnescaped(text: String): Map[String, String] = {
    val entrySeparator = "\",\""
    val keySeparator   = "\":\""

    // nextKeyEnd is the key separator found for an earlier candidate: searching again for each would be quadratic
    @tailrec
    def valueEnd(body: String, from: Int, nextKeyEnd: Int, seen: Set[String]): Int = {
      val at     = body.indexOf(entrySeparator, from)
      val next   = at + entrySeparator.length
      val keyEnd = if (at < 0 || nextKeyEnd >= next) nextKeyEnd else body.indexOf(keySeparator, next)
      if (at < 0 || keyEnd < 0) body.length
      else {
        val entryEnd = body.indexOf(entrySeparator, next)
        val isKey    = (entryEnd < 0 || keyEnd < entryEnd) && !seen.contains(body.substring(next, keyEnd))
        if (isKey) at else valueEnd(body, at + 1, keyEnd, seen)
      }
    }

    @tailrec
    def entries(body: String, from: Int, read: Map[String, String]): Map[String, String] =
      body.indexOf(keySeparator, from) match {
        case -1 => read
        case keyEnd =>
          val key     = body.substring(from, keyEnd)
          val start   = keyEnd + keySeparator.length
          val end     = valueEnd(body, start, nextKeyEnd = 0, read.keySet + key)
          val updated = read.updated(key, body.substring(start, end))
          if (end == body.length) updated else entries(body, end + entrySeparator.length, updated)
      }

    if (text.length >= 7 && text.startsWith("{\"") && text.endsWith("\"}"))
      entries(text.substring(2, text.length - 2), 0, Map.empty)
    else Map.empty
  }

  private[vectorstore] def serializeEmbedding(embedding: Array[Float]): Array[Byte] = {
    val buffer = java.nio.ByteBuffer.allocate(embedding.length * 4)
    buffer.asFloatBuffer().put(embedding)
    buffer.array()
  }

  private[vectorstore] def deserializeEmbedding(bytes: Array[Byte]): Array[Float] = {
    val buffer     = java.nio.ByteBuffer.wrap(bytes)
    val floatCount = bytes.length / 4
    val embedding  = new Array[Float](floatCount)
    buffer.asFloatBuffer().get(embedding)
    embedding
  }
}
