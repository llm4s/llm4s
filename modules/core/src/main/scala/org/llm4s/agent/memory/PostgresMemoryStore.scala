package org.llm4s.agent.memory

import org.llm4s.types.Result
import org.llm4s.error.{ LLMError, NotFoundError, OptimisticLockFailure, ProcessingError }
import org.llm4s.vectorstore.PostgresVectorHelpers
import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import ujson.{ Obj, Str, read, write }

import java.sql.{ Connection, PreparedStatement, ResultSet, Timestamp }
import scala.util.{ Try, Using }

/**
 * PostgreSQL implementation of MemoryStore.
 * Persists agent memories to a Postgres table using JDBC.
 */
final class PostgresMemoryStore private[memory] (
  private val dataSource: HikariDataSource,
  val tableName: String,
  private val embeddingService: Option[EmbeddingService]
) extends MemoryStore
    with AutoCloseable {

  import PostgresMemoryStore.SqlParam

  private[memory] def initializeSchema(): Unit =
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
            embedding vector,
            version BIGINT NOT NULL DEFAULT 0
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
            case Some(vec) => stmt.setString(7, PostgresVectorHelpers.embeddingToString(vec))
            case None      => stmt.setNull(7, java.sql.Types.OTHER, "vector")
          }

          stmt.executeUpdate()
        }
      }
      this
    }.toEither.left.map(e =>
      ProcessingError("postgres-memory-store", s"Failed to store memory: ${e.getMessage}", cause = Some(e))
    )

  override def get(id: MemoryId): Result[Option[Memory]] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"SELECT * FROM $tableName WHERE id = ?")) { stmt =>
          stmt.setString(1, id.value)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) {
              rowToMemory(rs).map(Some(_))
            } else {
              Right(None)
            }
          }
        }
      }
    }.toEither.left
      .map[LLMError](e =>
        ProcessingError("postgres-memory-store", s"Failed to get memory: ${e.getMessage}", cause = Some(e))
      )
      .flatMap(identity)

  override def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] =
    PostgresMemoryStore.filterToSql(filter).flatMap { case (whereClause, params) =>
      Try {
        withConnection { conn =>
          val sql = s"SELECT * FROM $tableName WHERE $whereClause ORDER BY created_at DESC LIMIT ?"

          Using.resource(conn.prepareStatement(sql)) { stmt =>
            params.zipWithIndex.foreach { case (param, idx) =>
              setParameter(stmt, idx + 1, param)
            }
            setParameter(stmt, params.size + 1, SqlParam.PInt(limit))

            Using.resource(stmt.executeQuery()) { rs =>
              val buffer                  = scala.collection.mutable.ArrayBuffer.empty[Memory]
              var error: Option[LLMError] = None

              while (rs.next() && error.isEmpty)
                rowToMemory(rs) match {
                  case Right(m) => buffer += m
                  case Left(e)  => error = Some(e)
                }

              error match {
                case Some(e) => Left(e)
                case None    => Right(buffer.toSeq)
              }
            }
          }
        }
      }.toEither.left
        .map[LLMError](e =>
          ProcessingError("postgres-memory-store", s"Failed to recall memories: ${e.getMessage}", cause = Some(e))
        )
        .flatMap(identity)
    }

  override def search(
    query: String,
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] =
    embeddingService match {
      case Some(service) =>
        service.embed(query).flatMap { queryVector =>
          if (queryVector.isEmpty) {
            Left(ProcessingError("postgres-memory-store", "Generated embedding vector is empty"))
          } else {
            performVectorSearch(queryVector, topK, filter)
          }
        }
      case None =>
        recall(filter, topK).map(memories => memories.map(m => ScoredMemory(m, 0.0)))
    }

  private def performVectorSearch(
    queryVector: Array[Float],
    topK: Int,
    filter: MemoryFilter
  ): Result[Seq[ScoredMemory]] =
    PostgresMemoryStore.filterToSql(filter).flatMap { case (whereClause, params) =>
      Try {
        withConnection { conn =>
          val sql =
            s"""
               |SELECT *,
               |       1 - (embedding <=> ?::vector) AS similarity
               |FROM $tableName
               |WHERE $whereClause AND embedding IS NOT NULL
               |ORDER BY embedding <=> ?::vector
               |LIMIT ?
             """.stripMargin

          Using.resource(conn.prepareStatement(sql)) { stmt =>
            val vectorStr = PostgresVectorHelpers.embeddingToString(queryVector)

            stmt.setString(1, vectorStr)

            params.zipWithIndex.foreach { case (param, idx) =>
              setParameter(stmt, idx + 2, param)
            }

            stmt.setString(params.size + 2, vectorStr)
            stmt.setInt(params.size + 3, topK)

            Using.resource(stmt.executeQuery()) { rs =>
              val results                 = scala.collection.mutable.ArrayBuffer.empty[ScoredMemory]
              var error: Option[LLMError] = None

              while (rs.next() && error.isEmpty)
                rowToMemory(rs) match {
                  case Right(memory) =>
                    val score = rs.getDouble("similarity")
                    results += ScoredMemory(
                      memory,
                      math.max(0.0, math.min(1.0, score))
                    )
                  case Left(e) =>
                    error = Some(e)
                }

              error match {
                case Some(e) => Left(e)
                case None    => Right(results.toSeq)
              }
            }
          }
        }
      }.toEither.left
        .map[LLMError](e =>
          ProcessingError("postgres-memory-store", s"Vector search failed: ${e.getMessage}", cause = Some(e))
        )
        .flatMap(identity)
    }

  override def delete(id: MemoryId): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id = ?")) { stmt =>
          stmt.setString(1, id.value)
          stmt.executeUpdate()
        }
      }
      this
    }.toEither.left.map(e =>
      ProcessingError("postgres-memory-store", s"Failed to delete memory: ${e.getMessage}", cause = Some(e))
    )

  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore] =
    PostgresMemoryStore.filterToSql(filter).flatMap { case (whereClause, params) =>
      Try {
        withConnection { conn =>
          val sql = s"DELETE FROM $tableName WHERE $whereClause"

          Using.resource(conn.prepareStatement(sql)) { stmt =>
            params.zipWithIndex.foreach { case (param, idx) =>
              setParameter(stmt, idx + 1, param)
            }
            stmt.executeUpdate()
          }
        }
        this
      }.toEither.left.map(e =>
        ProcessingError(
          "postgres-memory-store",
          s"Failed to delete matching memories: ${e.getMessage}",
          cause = Some(e)
        )
      )
    }

  override def update(id: MemoryId, updateFn: Memory => Memory): Result[MemoryStore] =
    getVersioned(id).flatMap {
      case Some(VersionedMemory(existing, version)) =>
        val updated = updateFn(existing)
        Try {
          withConnection { conn =>
            val sql = s"""
              UPDATE $tableName SET
                content = ?,
                memory_type = ?,
                metadata = ?::jsonb,
                created_at = ?,
                importance = ?,
                embedding = ?::vector,
                version = ?
              WHERE id = ? AND version = ?
            """
            Using.resource(conn.prepareStatement(sql)) { stmt =>
              stmt.setString(1, updated.content)
              stmt.setString(2, updated.memoryType.name)
              stmt.setString(3, PostgresMemoryStore.metadataToJson(updated.metadata))
              stmt.setTimestamp(4, Timestamp.from(updated.timestamp))

              updated.importance match {
                case Some(v) => stmt.setDouble(5, v)
                case None    => stmt.setNull(5, java.sql.Types.DOUBLE)
              }

              updated.embedding match {
                case Some(vec) => stmt.setString(6, PostgresVectorHelpers.embeddingToString(vec))
                case None      => stmt.setNull(6, java.sql.Types.OTHER, "vector")
              }

              stmt.setLong(7, version + 1)
              stmt.setString(8, id.value)
              stmt.setLong(9, version)
              stmt.executeUpdate()
            }
          }
        }.toEither.left
          .map(e =>
            ProcessingError("postgres-memory-store", s"Failed to update memory: ${e.getMessage}", cause = Some(e))
          )
          .flatMap { rowsAffected =>
            if (rowsAffected == 0)
              Left(
                OptimisticLockFailure(
                  s"Concurrent modification detected for memory: ${id.value}",
                  id.value,
                  version
                )
              )
            else
              Right(this)
          }
      case None => Left(NotFoundError(s"Memory not found: ${id.value}", id.value))
    }

  override def count(filter: MemoryFilter): Result[Long] =
    PostgresMemoryStore.filterToSql(filter).flatMap { case (whereClause, params) =>
      Try {
        withConnection { conn =>
          val sql = s"SELECT COUNT(*) FROM $tableName WHERE $whereClause"

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
      }.toEither.left.map(e =>
        ProcessingError("postgres-memory-store", s"Failed to count memories: ${e.getMessage}", cause = Some(e))
      )
    }

  override def clear(): Result[MemoryStore] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement())(stmt => stmt.execute(s"TRUNCATE TABLE $tableName"))
      }
      this
    }.toEither.left.map(e =>
      ProcessingError("postgres-memory-store", s"Failed to clear memories: ${e.getMessage}", cause = Some(e))
    )

  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] =
    recall(filter, limit)

  override def close(): Unit =
    if (!dataSource.isClosed) dataSource.close()

  private case class VersionedMemory(memory: Memory, version: Long)

  private def getVersioned(id: MemoryId): Result[Option[VersionedMemory]] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"SELECT * FROM $tableName WHERE id = ?")) { stmt =>
          stmt.setString(1, id.value)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) rowToMemory(rs).map(mem => Some(VersionedMemory(mem, rs.getLong("version"))))
            else Right(None)
          }
        }
      }
    }.toEither.left
      .map[LLMError](e =>
        ProcessingError("postgres-memory-store", s"Failed to get memory: ${e.getMessage}", cause = Some(e))
      )
      .flatMap(identity)

  private def withConnection[A](f: Connection => A): A =
    Using.resource(dataSource.getConnection)(f)

  private def rowToMemory(rs: ResultSet): Result[Memory] = {
    val embeddingStr = rs.getString("embedding")

    val embeddingResult =
      if (embeddingStr == null) Right(None)
      else PostgresVectorHelpers.stringToEmbedding(embeddingStr).map(Some(_))

    embeddingResult.map { embedding =>
      Memory(
        id = MemoryId(rs.getString("id")),
        content = rs.getString("content"),
        memoryType = MemoryType.fromString(rs.getString("memory_type")),
        metadata = PostgresMemoryStore.jsonToMetadata(rs.getString("metadata")),
        timestamp = rs.getTimestamp("created_at").toInstant,
        importance = Option(rs.getDouble("importance")).filterNot(_ => rs.wasNull()),
        embedding = embedding
      )
    }
  }

  private def setParameter(stmt: PreparedStatement, index: Int, value: SqlParam): Unit = value match {
    case SqlParam.PString(s)    => stmt.setString(index, s)
    case SqlParam.PInt(i)       => stmt.setInt(index, i)
    case SqlParam.PLong(l)      => stmt.setLong(index, l)
    case SqlParam.PDouble(d)    => stmt.setDouble(index, d)
    case SqlParam.PBoolean(b)   => stmt.setBoolean(index, b)
    case SqlParam.PTimestamp(t) => stmt.setTimestamp(index, t)
    case SqlParam.PNullDouble   => stmt.setNull(index, java.sql.Types.DOUBLE)
    case SqlParam.PNullVector   => stmt.setNull(index, java.sql.Types.OTHER, "vector")
  }
}

object PostgresMemoryStore {

  sealed trait SqlParam
  object SqlParam {
    final case class PString(v: String)                extends SqlParam
    final case class PInt(v: Int)                      extends SqlParam
    final case class PLong(v: Long)                    extends SqlParam
    final case class PDouble(v: Double)                extends SqlParam
    final case class PBoolean(v: Boolean)              extends SqlParam
    final case class PTimestamp(v: java.sql.Timestamp) extends SqlParam
    case object PNullDouble                            extends SqlParam
    case object PNullVector                            extends SqlParam
  }

  import SqlParam._

  private val ValidIdentifierPattern  = "^[a-zA-Z_][a-zA-Z0-9_]{0,62}$".r
  private val ValidMetadataKeyPattern = "^[a-zA-Z_][a-zA-Z0-9_]*$".r

  /** Helper for binary filter composition (And/Or) */
  private def composeBinary(
    left: MemoryFilter,
    right: MemoryFilter,
    operator: String
  ): Result[(String, Seq[SqlParam])] =
    filterToSql(left).flatMap { case (leftSql, leftParams) =>
      filterToSql(right).map { case (rightSql, rightParams) =>
        (s"($leftSql $operator $rightSql)", leftParams ++ rightParams)
      }
    }

  final case class Config(
    host: String = "localhost",
    port: Int = 5432,
    database: String = "postgres",
    user: String = "postgres",
    password: String = "",
    tableName: String = "agent_memories",
    maxPoolSize: Int = 10
  ) {
    require(
      ValidIdentifierPattern.matches(tableName),
      s"Invalid table name '$tableName': must match pattern [a-zA-Z_][a-zA-Z0-9_]{0,62}"
    )
    def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
  }

  def apply(
    config: Config,
    embeddingService: Option[EmbeddingService] = None
  ): Result[PostgresMemoryStore] =
    Try {
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(config.jdbcUrl)
      hikariConfig.setUsername(config.user)
      hikariConfig.setPassword(config.password)
      hikariConfig.setMaximumPoolSize(config.maxPoolSize)
      hikariConfig.setMinimumIdle(1)

      val dataSource = new HikariDataSource(hikariConfig)
      val store      = new PostgresMemoryStore(dataSource, config.tableName, embeddingService)
      store.initializeSchema()
      store
    }.toEither.left.map(e =>
      ProcessingError("postgres-memory-store", s"Failed to initialize: ${e.getMessage}", cause = Some(e))
    )

  private[memory] def filterToSql(filter: MemoryFilter): Result[(String, Seq[SqlParam])] = filter match {
    case MemoryFilter.All =>
      Right("TRUE" -> Seq.empty)

    case MemoryFilter.None =>
      Right("FALSE" -> Seq.empty)

    case MemoryFilter.ByEntity(entityId) =>
      Right("metadata->>'entity_id' = ?" -> Seq(PString(entityId.value)))

    case MemoryFilter.ByConversation(convId) =>
      Right("metadata->>'conversation_id' = ?" -> Seq(PString(convId)))

    case MemoryFilter.ByType(memType) =>
      Right("memory_type = ?" -> Seq(PString(memType.name)))

    case MemoryFilter.ByTypes(memoryTypes) =>
      if (memoryTypes.isEmpty) Right("FALSE" -> Seq.empty)
      else {
        val sortedTypes  = memoryTypes.toSeq.sortBy(_.name)
        val placeholders = sortedTypes.map(_ => "?").mkString(",")
        Right(s"memory_type IN ($placeholders)" -> sortedTypes.map(t => PString(t.name)))
      }

    case MemoryFilter.MinImportance(threshold) =>
      Right("importance >= ?" -> Seq(PDouble(threshold)))

    case MemoryFilter.ByTimeRange(after, before) =>
      val (clause, params) = (after, before) match {
        case (Some(a), Some(b)) =>
          ("created_at >= ? AND created_at <= ?", Seq(PTimestamp(Timestamp.from(a)), PTimestamp(Timestamp.from(b))))
        case (Some(a), None) =>
          ("created_at >= ?", Seq(PTimestamp(Timestamp.from(a))))
        case (None, Some(b)) =>
          ("created_at <= ?", Seq(PTimestamp(Timestamp.from(b))))
        case (None, None) =>
          ("TRUE", Seq.empty)
      }
      Right(clause -> params)

    case MemoryFilter.ByMetadata(key, value) =>
      if (!ValidMetadataKeyPattern.matches(key)) {
        Left(ProcessingError("postgres-memory-store", s"Invalid metadata key: '$key'"))
      } else {
        Right(s"metadata->>'$key' = ?" -> Seq(PString(value)))
      }

    case MemoryFilter.And(l, r) => composeBinary(l, r, "AND")
    case MemoryFilter.Or(l, r)  => composeBinary(l, r, "OR")

    case MemoryFilter.Not(inner) =>
      filterToSql(inner).map { case (innerSql, innerParams) =>
        (s"NOT ($innerSql)", innerParams)
      }

    case unsupported =>
      Left(ProcessingError("postgres-memory-store", s"Unsupported filter: ${unsupported.getClass.getSimpleName}"))
  }

  private[memory] def metadataToJson(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else write(metadata)

  private[memory] def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json == "{}" || json.isEmpty) Map.empty
    else {
      Try(read(json)).toOption match {
        case Some(Obj(items)) =>
          items.map {
            case (k, Str(s)) => k -> s
            case (k, v)      => k -> v.toString()
          }.toMap
        case _ => Map.empty
      }
    }

}
