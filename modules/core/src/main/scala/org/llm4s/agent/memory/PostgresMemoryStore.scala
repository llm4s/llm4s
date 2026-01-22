package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }

import java.sql.{ Connection, PreparedStatement, ResultSet, Timestamp }
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

/**
 * PostgreSQL implementation of MemoryStore.
 * Persists agent memories to a Postgres table using JDBC.
 * DESIGN NOTES:
 * - This is an MVP implementation focused on persistence only.
 * - Full MemoryFilter support (And/Or/Not/TimeRange) and Semantic search via embeddings will be added later.
 */
final class PostgresMemoryStore private[memory] (
  private val dataSource: HikariDataSource,
  val tableName: String
) extends MemoryStore
    with AutoCloseable {

  initializeSchema()

  private def initializeSchema(): Unit =
    withConnection { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        // 1. Enable pgvector extension
        stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")

        // 2. Create table with proper VECTOR type
        stmt.execute(s"""
          CREATE TABLE IF NOT EXISTS $tableName (
            id TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            memory_type TEXT NOT NULL,
            metadata JSONB DEFAULT '{}',
            created_at TIMESTAMPTZ NOT NULL,
            importance DOUBLE PRECISION,
            embedding vector
          )
        """)

        // 3. Indexes for common access patterns
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_type ON $tableName(memory_type)")
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_created ON $tableName(created_at)")
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_metadata ON $tableName USING GIN(metadata)")
        stmt.execute(
          s"CREATE INDEX IF NOT EXISTS idx_${tableName}_conversation ON $tableName ((metadata->>'conversation_id'))"
        )
      }
      ()
    }

  override def store(memory: Memory): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        val sql = s"""
          INSERT INTO $tableName
            (id, content, memory_type, metadata, created_at, importance, embedding)
          VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::vector)
          ON CONFLICT (id) DO UPDATE SET
            content = EXCLUDED.content,
            memory_type = EXCLUDED.memory_type,
            metadata = EXCLUDED.metadata,
            created_at = EXCLUDED.created_at,
            importance = EXCLUDED.importance,
            embedding = EXCLUDED.embedding
        """

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          stmt.setString(1, memory.id.value)
          stmt.setString(2, memory.content)
          stmt.setString(3, memory.memoryType.name)
          stmt.setString(4, PostgresMemoryStore.metadataToJson(memory.metadata))
          stmt.setTimestamp(5, Timestamp.from(memory.timestamp))

          memory.importance match {
            case Some(v) => stmt.setDouble(6, v)
            case None    => stmt.setNull(6, java.sql.Types.DOUBLE)
          }

          memory.embedding match {
            // CALLING COMPANION OBJECT METHOD
            case Some(vec) => stmt.setString(7, PostgresMemoryStore.embeddingToString(vec))
            case None      => stmt.setNull(7, java.sql.Types.OTHER, "vector")
          }

          stmt.executeUpdate()
        }
      }
      this
    }.toEither.left.map(e => ProcessingError("postgres-memory-store", s"Failed to store memory: ${e.getMessage}"))

  override def get(id: MemoryId): Result[Option[Memory]] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"SELECT * FROM $tableName WHERE id = ?")) { stmt =>
          stmt.setString(1, id.value)
          Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(rowToMemory(rs)) else None)
        }
      }
    }.toEither.left.map(e => ProcessingError("postgres-memory-store", s"Failed to get memory: ${e.getMessage}"))

  override def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] =
    Try {
      withConnection { conn =>
        val (whereClause, params) = PostgresMemoryStore.filterToSql(filter)
        val sql =
          s"SELECT * FROM $tableName WHERE $whereClause ORDER BY created_at DESC LIMIT ?"

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          stmt.setInt(params.size + 1, limit)

          Using.resource(stmt.executeQuery()) { rs =>
            val memories = ArrayBuffer.empty[Memory]
            while (rs.next()) memories += rowToMemory(rs)
            memories.toSeq
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("postgres-memory-store", s"Failed to recall memories: ${e.getMessage}"))

  override def search(
    query: String,
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] =
    Left(
      ProcessingError(
        "postgres-memory-store",
        "Semantic search is not yet implemented for PostgresMemoryStore. Requires EmbeddingService integration."
      )
    )

  override def delete(id: MemoryId): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id = ?")) { stmt =>
          stmt.setString(1, id.value)
          stmt.executeUpdate()
        }
      }
      this
    }.toEither.left.map(e => ProcessingError("postgres-memory-store", s"Failed to delete memory: ${e.getMessage}"))

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        val (whereClause, params) = PostgresMemoryStore.filterToSql(filter)
        val sql                   = s"DELETE FROM $tableName WHERE $whereClause"

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          stmt.executeUpdate()
        }
      }
      this
    }.toEither.left.map(e =>
      ProcessingError("postgres-memory-store", s"Failed to delete matching memories: ${e.getMessage}")
    )

  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    // TODO: This update is not atomic under concurrency.
    // Future improvement: SELECT FOR UPDATE or optimistic locking.
    get(id).flatMap {
      case Some(existing) => store(updateFn(existing))
      case None           => Right(this)
    }

  override def count(filter: MemoryFilter): Result[Long] =
    Try {
      withConnection { conn =>
        val (whereClause, params) = PostgresMemoryStore.filterToSql(filter)
        val sql                   = s"SELECT COUNT(*) FROM $tableName WHERE $whereClause"

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          Using.resource(stmt.executeQuery()) { rs =>
            rs.next()
            rs.getLong(1)
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("postgres-memory-store", s"Failed to count memories: ${e.getMessage}"))

  override def clear(): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement())(stmt => stmt.execute(s"TRUNCATE TABLE $tableName"))
      }
      this
    }.toEither.left.map(e => ProcessingError("postgres-memory-store", s"Failed to clear memories: ${e.getMessage}"))

  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] =
    recall(filter, limit)

  override def close(): Unit =
    if (!dataSource.isClosed) dataSource.close()

  private def withConnection[A](f: Connection => A): A = {
    val conn = dataSource.getConnection
    Try(f(conn)) match {
      case scala.util.Success(result) =>
        conn.close()
        result
      case scala.util.Failure(e) =>
        conn.close()
        throw e
    }
  }

  private def rowToMemory(rs: ResultSet): Memory = {
    val embeddingStr = rs.getString("embedding")

    Memory(
      id = MemoryId(rs.getString("id")),
      content = rs.getString("content"),
      memoryType = MemoryType.fromString(rs.getString("memory_type")),
      metadata = PostgresMemoryStore.jsonToMetadata(rs.getString("metadata")),
      timestamp = rs.getTimestamp("created_at").toInstant,
      importance = Option(rs.getDouble("importance")).filterNot(_ => rs.wasNull()),
      embedding = Option(embeddingStr).map(PostgresMemoryStore.stringToEmbedding)
    )
  }

  private def setParameter(stmt: PreparedStatement, index: Int, value: Any): Unit = value match {
    case s: String  => stmt.setString(index, s)
    case i: Int     => stmt.setInt(index, i)
    case l: Long    => stmt.setLong(index, l)
    case d: Double  => stmt.setDouble(index, d)
    case b: Boolean => stmt.setBoolean(index, b)
    case _          => stmt.setString(index, value.toString)
  }
}

object PostgresMemoryStore {

  final case class Config(
    host: String = "localhost",
    port: Int = 5432,
    database: String = "postgres",
    user: String = "postgres",
    password: String = "",
    tableName: String = "agent_memories",
    maxPoolSize: Int = 10
  ) {
    def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
  }

  def apply(config: Config): Result[PostgresMemoryStore] =
    Try {
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(config.jdbcUrl)
      hikariConfig.setUsername(config.user)
      hikariConfig.setPassword(config.password)
      hikariConfig.setMaximumPoolSize(config.maxPoolSize)
      hikariConfig.setMinimumIdle(1)

      val dataSource = new HikariDataSource(hikariConfig)
      new PostgresMemoryStore(dataSource, config.tableName)
    }.toEither.left.map(e => ProcessingError("postgres-memory-store", s"Failed to initialize: ${e.getMessage}"))

  private[memory] def filterToSql(filter: MemoryFilter): (String, Seq[Any]) = filter match {
    case MemoryFilter.All =>
      ("TRUE", Seq.empty)

    case MemoryFilter.ByEntity(entityId) =>
      ("metadata->>'entity_id' = ?", Seq(entityId.value))

    case MemoryFilter.ByConversation(convId) =>
      ("metadata->>'conversation_id' = ?", Seq(convId))

    case MemoryFilter.ByType(memType) =>
      ("memory_type = ?", Seq(memType.name))

    case MemoryFilter.MinImportance(threshold) =>
      ("importance >= ?", Seq(threshold))

    case _ =>
      // Safe fallback for now until full ADT support is added
      ("FALSE", Seq.empty)
  }

  private[memory] def metadataToJson(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else metadata.map { case (k, v) => s""""$k":"${v.replace("\"", "\\\"")}"""" }.mkString("{", ",", "}")

  private[memory] def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json == "{}" || json.isEmpty) Map.empty
    else {
      val pattern = """"([^"]+)":\s*"([^"]*)"""".r
      pattern.findAllMatchIn(json).map(m => m.group(1) -> m.group(2)).toMap
    }

  private[memory] def embeddingToString(embedding: Array[Float]): String =
    embedding.mkString("[", ",", "]")

  private[memory] def stringToEmbedding(s: String): Array[Float] =
    if (s == null || s.isEmpty) Array.emptyFloatArray
    else {
      val cleaned = s.stripPrefix("[").stripSuffix("]")
      if (cleaned.isEmpty) Array.emptyFloatArray
      else cleaned.split(",").map(_.trim.toFloat)
    }
}
