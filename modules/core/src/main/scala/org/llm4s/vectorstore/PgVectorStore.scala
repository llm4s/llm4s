package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.{ ProcessingError, LLMError }

import java.sql.{ Connection, PreparedStatement, ResultSet }
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }
import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }

/**
 * PostgreSQL + pgvector implementation of VectorStore.
 *
 * Uses pgvector extension for efficient vector similarity search
 * with support for IVFFlat and HNSW indexes.
 *
 * Features:
 * - Hardware-accelerated vector operations
 * - HNSW indexing for fast approximate nearest neighbor search
 * - Connection pooling via HikariCP
 * - ACID transactions
 * - Scalable to millions of vectors
 * - Graceful handling of corrupt embeddings
 *
 * Corrupt Embedding Handling:
 * - Records with unparseable embeddings are skipped during search/query/list operations
 * - get() returns None for records with corrupt embeddings
 * - Corrupt records are logged with id and embedding_dim for debugging
 * - Operations continue successfully with remaining valid records
 * - No silent data corruption - failures are observable via logs
 *
 * Requirements:
 * - PostgreSQL 16+ with pgvector extension (18+ recommended)
 * - Run: CREATE EXTENSION IF NOT EXISTS vector;
 *
 * @param dataSource HikariCP connection pool
 * @param tableName  Name of the vectors table
 * @param ownsDataSource Whether to close dataSource on close() (default: true)
 */
final class PgVectorStore private (
  private val dataSource: HikariDataSource,
  val tableName: String,
  private val ownsDataSource: Boolean = true
) extends VectorStore {

  // Initialize schema on creation
  initializeSchema()

  private def initializeSchema(): Unit =
    withConnection { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        // Enable pgvector extension
        stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")

        // Main vectors table with vector column
        stmt.execute(s"""
          CREATE TABLE IF NOT EXISTS $tableName (
            id TEXT PRIMARY KEY,
            embedding vector,
            embedding_dim INTEGER NOT NULL,
            content TEXT,
            metadata JSONB DEFAULT '{}',
            created_at TIMESTAMPTZ DEFAULT NOW()
          )
        """)

        // Index for dimension queries
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_dim ON $tableName(embedding_dim)")

        // Index for created_at ordering
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_created ON $tableName(created_at)")

        // GIN index for JSONB metadata queries
        stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_metadata ON $tableName USING GIN(metadata)")
      }
      ()
    }

  /**
   * Create HNSW index for faster similarity search.
   * Call this after bulk loading data for best performance.
   *
   * @param m Maximum number of connections per layer (default 16)
   * @param efConstruction Size of dynamic candidate list (default 64)
   */
  def createHnswIndex(m: Int = 16, efConstruction: Int = 64): Result[Unit] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          // Drop existing index if any
          stmt.execute(s"DROP INDEX IF EXISTS idx_${tableName}_hnsw")

          // Create HNSW index for cosine similarity
          stmt.execute(s"""
            CREATE INDEX idx_${tableName}_hnsw ON $tableName
            USING hnsw (embedding vector_cosine_ops)
            WITH (m = $m, ef_construction = $efConstruction)
          """)
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to create HNSW index: ${e.getMessage}"))

  override def upsert(record: VectorRecord): Result[Unit] =
    Try {
      withConnection { conn =>
        val sql = s"""
          INSERT INTO $tableName (id, embedding, embedding_dim, content, metadata)
          VALUES (?, ?::vector, ?, ?, ?::jsonb)
          ON CONFLICT (id) DO UPDATE SET
            embedding = EXCLUDED.embedding,
            embedding_dim = EXCLUDED.embedding_dim,
            content = EXCLUDED.content,
            metadata = EXCLUDED.metadata,
            created_at = NOW()
        """

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          stmt.setString(1, record.id)
          stmt.setString(2, PostgresVectorHelpers.embeddingToString(record.embedding))
          stmt.setInt(3, record.dimensions)
          record.content match {
            case Some(c) => stmt.setString(4, c)
            case None    => stmt.setNull(4, java.sql.Types.VARCHAR)
          }
          stmt.setString(5, metadataToJson(record.metadata))
          stmt.executeUpdate()
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to upsert: ${e.getMessage}"))

  override def upsertBatch(records: Seq[VectorRecord]): Result[Unit] =
    if (records.isEmpty) Right(())
    else
      Try {
        withConnection { conn =>
          conn.setAutoCommit(false)
          val result = Try {
            val sql = s"""
            INSERT INTO $tableName (id, embedding, embedding_dim, content, metadata)
            VALUES (?, ?::vector, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO UPDATE SET
              embedding = EXCLUDED.embedding,
              embedding_dim = EXCLUDED.embedding_dim,
              content = EXCLUDED.content,
              metadata = EXCLUDED.metadata,
              created_at = NOW()
          """

            Using.resource(conn.prepareStatement(sql)) { stmt =>
              records.foreach { record =>
                stmt.setString(1, record.id)
                stmt.setString(2, PostgresVectorHelpers.embeddingToString(record.embedding))
                stmt.setInt(3, record.dimensions)
                record.content match {
                  case Some(c) => stmt.setString(4, c)
                  case None    => stmt.setNull(4, java.sql.Types.VARCHAR)
                }
                stmt.setString(5, metadataToJson(record.metadata))
                stmt.addBatch()
              }
              stmt.executeBatch()
            }
          }

          result match {
            case scala.util.Success(_) =>
              conn.commit()
              conn.setAutoCommit(true)
            case scala.util.Failure(e) =>
              conn.rollback()
              conn.setAutoCommit(true)
              throw e
          }
        }
      }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to upsert batch: ${e.getMessage}"))

  /**
   * Search for the most similar vectors.
   *
   * Note: if the database contains corrupt embeddings, those rows are silently skipped.
   * Because filtering happens after the SQL `LIMIT topK`, fewer than `topK` results may
   * be returned when corrupt rows are present.
   */
  override def search(
    queryVector: Array[Float],
    topK: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[ScoredRecord]] =
    Try {
      withConnection { conn =>
        val (whereClause, params) = filter.map(filterToSql).getOrElse(("TRUE", Seq.empty))

        // Use cosine distance operator (<=>)
        // Convert to similarity: 1 - distance (cosine distance ranges 0-2, similarity 0-1)
        val sql = s"""
          SELECT id, embedding, embedding_dim, content, metadata,
                 1 - (embedding <=> ?::vector) AS similarity
          FROM $tableName
          WHERE $whereClause
          ORDER BY embedding <=> ?::vector
          LIMIT ?
        """

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          val vectorStr = PostgresVectorHelpers.embeddingToString(queryVector)
          stmt.setString(1, vectorStr)

          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 2, param)
          }

          stmt.setString(params.size + 2, vectorStr)
          stmt.setInt(params.size + 3, topK)

          Using.resource(stmt.executeQuery()) { rs =>
            val results                 = ArrayBuffer.empty[ScoredRecord]
            var error: Option[LLMError] = None

            while (rs.next() && error.isEmpty)
              rowToRecord(rs) match {
                case Right(record) =>
                  val score           = rs.getDouble("similarity")
                  val normalizedScore = math.max(0.0, math.min(1.0, score))
                  results += ScoredRecord(record, normalizedScore)
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
      .map[LLMError](e => ProcessingError("pgvector-store", s"Search failed: ${e.getMessage}"))
      .flatMap(identity)

  override def get(id: String): Result[Option[VectorRecord]] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"SELECT * FROM $tableName WHERE id = ?")) { stmt =>
          stmt.setString(1, id)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) {
              rowToRecord(rs).map(Some(_))
            } else {
              Right(None)
            }
          }
        }
      }
    }.toEither.left
      .map[LLMError](e => ProcessingError("pgvector-store", s"Failed to get: ${e.getMessage}"))
      .flatMap(identity)

  override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] =
    if (ids.isEmpty) Right(Seq.empty)
    else
      Try {
        withConnection { conn =>
          val placeholders = ids.map(_ => "?").mkString(",")
          val sql          = s"SELECT * FROM $tableName WHERE id IN ($placeholders)"

          Using.resource(conn.prepareStatement(sql)) { stmt =>
            ids.zipWithIndex.foreach { case (id, idx) =>
              stmt.setString(idx + 1, id)
            }
            Using.resource(stmt.executeQuery()) { rs =>
              val records                 = ArrayBuffer.empty[VectorRecord]
              var error: Option[LLMError] = None

              while (rs.next() && error.isEmpty)
                rowToRecord(rs) match {
                  case Right(r) => records += r
                  case Left(e)  => error = Some(e)
                }

              error match {
                case Some(e) => Left(e)
                case None    => Right(records.toSeq)
              }
            }
          }
        }
      }.toEither.left
        .map[LLMError](e => ProcessingError("pgvector-store", s"Failed to get batch: ${e.getMessage}"))
        .flatMap(identity)

  override def delete(id: String): Result[Unit] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id = ?")) { stmt =>
          stmt.setString(1, id)
          stmt.executeUpdate()
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to delete: ${e.getMessage}"))

  override def deleteBatch(ids: Seq[String]): Result[Unit] =
    if (ids.isEmpty) Right(())
    else
      Try {
        withConnection { conn =>
          val placeholders = ids.map(_ => "?").mkString(",")
          Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id IN ($placeholders)")) { stmt =>
            ids.zipWithIndex.foreach { case (id, idx) =>
              stmt.setString(idx + 1, id)
            }
            stmt.executeUpdate()
            ()
          }
        }
      }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to delete batch: ${e.getMessage}"))

  override def deleteByPrefix(prefix: String): Result[Long] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE id LIKE ?")) { stmt =>
          stmt.setString(1, prefix + "%")
          stmt.executeUpdate().toLong
        }
      }
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to delete by prefix: ${e.getMessage}"))

  override def deleteByFilter(filter: MetadataFilter): Result[Long] =
    Try {
      withConnection { conn =>
        val (whereClause, params) = filterToSql(filter)
        val sql                   = s"DELETE FROM $tableName WHERE $whereClause"

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          stmt.executeUpdate().toLong
        }
      }
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to delete by filter: ${e.getMessage}"))

  override def count(filter: Option[MetadataFilter]): Result[Long] =
    Try {
      withConnection { conn =>
        val (whereClause, params) = filter.map(filterToSql).getOrElse(("TRUE", Seq.empty))
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
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to count: ${e.getMessage}"))

  override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
    Try {
      withConnection { conn =>
        val (whereClause, params) = filter.map(filterToSql).getOrElse(("TRUE", Seq.empty))
        val sql = s"SELECT * FROM $tableName WHERE $whereClause ORDER BY created_at DESC LIMIT ? OFFSET ?"

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          params.zipWithIndex.foreach { case (param, idx) =>
            setParameter(stmt, idx + 1, param)
          }
          stmt.setInt(params.size + 1, limit)
          stmt.setInt(params.size + 2, offset)

          Using.resource(stmt.executeQuery()) { rs =>
            val records                 = ArrayBuffer.empty[VectorRecord]
            var error: Option[LLMError] = None

            while (rs.next() && error.isEmpty)
              rowToRecord(rs) match {
                case Right(r) => records += r
                case Left(e)  => error = Some(e)
              }

            error match {
              case Some(e) => Left(e)
              case None    => Right(records.toSeq)
            }
          }
        }
      }
    }.toEither.left
      .map[LLMError](e => ProcessingError("pgvector-store", s"Failed to list: ${e.getMessage}"))
      .flatMap(identity)

  override def clear(): Result[Unit] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          stmt.execute(s"TRUNCATE TABLE $tableName")
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to clear: ${e.getMessage}"))

  override def stats(): Result[VectorStoreStats] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          val total = Using.resource(stmt.executeQuery(s"SELECT COUNT(*) FROM $tableName")) { rs =>
            rs.next()
            rs.getLong(1)
          }

          val dims =
            Using.resource(stmt.executeQuery(s"SELECT DISTINCT embedding_dim FROM $tableName")) { rs =>
              val buffer = ArrayBuffer.empty[Int]
              while (rs.next())
                buffer += rs.getInt(1)
              buffer.toSet
            }

          // Get table size
          val size = Try {
            Using.resource(stmt.executeQuery(s"SELECT pg_total_relation_size('$tableName')")) { rs =>
              rs.next()
              Some(rs.getLong(1))
            }
          }.getOrElse(None)

          VectorStoreStats(
            totalRecords = total,
            dimensions = dims,
            sizeBytes = size
          )
        }
      }
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to get stats: ${e.getMessage}"))

  override def close(): Unit =
    if (ownsDataSource && !dataSource.isClosed) {
      dataSource.close()
    }

  // ============================================================
  // Helper Methods
  // ============================================================

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

  private def rowToRecord(rs: ResultSet): Result[VectorRecord] = {
    val embeddingStr = rs.getString("embedding")

    PostgresVectorHelpers.stringToEmbedding(embeddingStr).map { embedding =>
      val content      = Option(rs.getString("content")).filter(_.nonEmpty)
      val metadataJson = rs.getString("metadata")
      val metadata     = jsonToMetadata(metadataJson)

      VectorRecord(
        id = rs.getString("id"),
        embedding = embedding,
        content = content,
        metadata = metadata
      )
    }
  }

  private def filterToSql(filter: MetadataFilter): (String, Seq[Any]) = filter match {
    case MetadataFilter.All => ("TRUE", Seq.empty)

    case MetadataFilter.Equals(key, value) =>
      ("metadata->>? = ?", Seq(key, value))

    case MetadataFilter.Contains(key, substring) =>
      ("metadata->>? LIKE ?", Seq(key, s"%$substring%"))

    case MetadataFilter.HasKey(key) =>
      ("metadata ? ?", Seq(key))

    case MetadataFilter.In(key, values) =>
      val placeholders = values.map(_ => "?").mkString(",")
      (s"metadata->>? IN ($placeholders)", Seq(key) ++ values.toSeq)

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

  private def metadataToJson(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else metadata.map { case (k, v) => s""""$k":"$v"""" }.mkString("{", ",", "}")

  private def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json == "{}" || json.isEmpty) Map.empty
    else {
      val pattern = """"([^"]+)":\s*"([^"]*)"""".r
      pattern.findAllMatchIn(json).map(m => m.group(1) -> m.group(2)).toMap
    }
}

object PgVectorStore {

  /**
   * Configuration for PgVectorStore.
   *
   * @param host Database host
   * @param port Database port
   * @param database Database name
   * @param user Database user
   * @param password Database password
   * @param tableName Table name for vectors (default: "vectors")
   * @param maxPoolSize Maximum connection pool size (default: 10)
   */
  final case class Config(
    host: String = "localhost",
    port: Int = 5432,
    database: String = "postgres",
    user: String = "postgres",
    password: String = "",
    tableName: String = "vectors",
    maxPoolSize: Int = 10
  ) {
    def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
  }

  /**
   * Create a PgVectorStore from configuration.
   *
   * @param config The store configuration
   * @return The vector store or error
   */
  def apply(config: Config): Result[PgVectorStore] =
    Try {
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(config.jdbcUrl)
      hikariConfig.setUsername(config.user)
      hikariConfig.setPassword(config.password)
      hikariConfig.setMaximumPoolSize(config.maxPoolSize)
      hikariConfig.setMinimumIdle(1)
      hikariConfig.setConnectionTimeout(30000)
      hikariConfig.setIdleTimeout(600000)
      hikariConfig.setMaxLifetime(1800000)

      val dataSource = new HikariDataSource(hikariConfig)
      new PgVectorStore(dataSource, config.tableName)
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to create store: ${e.getMessage}"))

  /**
   * Create a PgVectorStore from connection string.
   *
   * @param connectionString PostgreSQL connection string (jdbc:postgresql://...)
   * @param user Database user
   * @param password Database password
   * @param tableName Table name for vectors
   * @return The vector store or error
   */
  def apply(
    connectionString: String,
    user: String = "postgres",
    password: String = "",
    tableName: String = "vectors"
  ): Result[PgVectorStore] =
    Try {
      val hikariConfig = new HikariConfig()
      hikariConfig.setJdbcUrl(connectionString)
      hikariConfig.setUsername(user)
      hikariConfig.setPassword(password)
      hikariConfig.setMaximumPoolSize(10)
      hikariConfig.setMinimumIdle(1)

      val dataSource = new HikariDataSource(hikariConfig)
      new PgVectorStore(dataSource, tableName)
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to create store: ${e.getMessage}"))

  /**
   * Create a PgVectorStore with default local settings.
   *
   * Connects to localhost:5432/postgres with user postgres.
   *
   * @param tableName Table name for vectors
   * @return The vector store or error
   */
  def local(tableName: String = "vectors"): Result[PgVectorStore] =
    apply(Config(tableName = tableName))

  /**
   * Create a PgVectorStore with an existing HikariDataSource.
   *
   * Useful for sharing a connection pool with other PostgreSQL stores
   * (e.g., PgKeywordIndex for hybrid search).
   *
   * Note: The provided dataSource will NOT be closed when the store is closed.
   * The caller is responsible for managing the dataSource lifecycle.
   *
   * @param dataSource Existing HikariDataSource
   * @param tableName Table name for vectors
   * @return The vector store or error
   */
  def apply(dataSource: HikariDataSource, tableName: String): Result[PgVectorStore] =
    Try {
      new PgVectorStore(dataSource, tableName, ownsDataSource = false)
    }.toEither.left.map(e => ProcessingError("pgvector-store", s"Failed to create store: ${e.getMessage}"))
}
