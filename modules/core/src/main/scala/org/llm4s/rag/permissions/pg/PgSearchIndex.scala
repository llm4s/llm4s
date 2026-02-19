package org.llm4s.rag.permissions.pg

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.error.ProcessingError
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord }
import org.llm4s.util.RateLimitedLogger

import org.slf4j.LoggerFactory

import java.sql.{ Array => SqlArray, Connection, ResultSet }
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

/**
 * PostgreSQL implementation of SearchIndex with permission-based filtering.
 *
 * Uses HikariCP for connection pooling and pgvector for efficient
 * vector similarity search.
 *
 * The implementation:
 * 1. Extends the vectors table with collection_id and readable_by columns
 * 2. Uses GIN indexes for efficient array containment queries
 * 3. Applies two-level permission filtering (collection + document)
 * 4. Gracefully handles corrupt embeddings without failing queries
 *
 * Corrupt Embedding Handling:
 * - Records with unparseable embeddings are skipped during query operations
 * - Corrupt records are logged with id and embedding_dim for debugging
 * - Queries continue successfully and return topK valid results
 * - No silent data corruption - failures are observable via logs
 *
 * @param dataSource HikariCP data source for connection pooling
 * @param vectorTableName Name of the vectors table
 */
final class PgSearchIndex private (
  private val dataSource: HikariDataSource,
  private val vectorTableName: String,
  private val _pgConfig: SearchIndex.PgConfig
) extends SearchIndex {

  private val logger            = LoggerFactory.getLogger(getClass)
  private val rateLimitedLogger = RateLimitedLogger(logger, throttleSeconds = 60, throttleCount = 100)

  /** Expose PostgreSQL configuration for automatic RAG integration */
  override def pgConfig: Option[SearchIndex.PgConfig] = Some(_pgConfig)

  private val _principals  = new PgPrincipalStore(() => dataSource.getConnection)
  private val _collections = new PgCollectionStore(() => dataSource.getConnection, vectorTableName)

  override def principals: PrincipalStore   = _principals
  override def collections: CollectionStore = _collections

  /**
   * Query for the most similar vectors matching the user's permissions.
   *
   * Note: if the database contains corrupt embeddings, those rows are silently skipped.
   * Because filtering happens after the SQL `LIMIT topK`, fewer than `topK` results may
   * be returned when corrupt rows are present.
   */
  override def query(
    auth: UserAuthorization,
    collectionPattern: CollectionPattern,
    queryVector: Array[Float],
    topK: Int,
    additionalFilter: Option[MetadataFilter]
  ): Result[Seq[ScoredRecord]] =
    for {
      // Step 1: Get accessible collection IDs
      accessibleCollections <- _collections.findAccessible(auth, collectionPattern)
      collectionIds = accessibleCollections.filter(_.isLeaf).map(_.id)

      // Step 2: Perform vector search with permission filter
      results <-
        if (collectionIds.isEmpty) {
          Right(Seq.empty[ScoredRecord])
        } else {
          searchWithPermissions(auth, collectionIds, queryVector, topK, additionalFilter)
        }
    } yield results

  private def searchWithPermissions(
    auth: UserAuthorization,
    collectionIds: Seq[Int],
    queryVector: Array[Float],
    topK: Int,
    additionalFilter: Option[MetadataFilter]
  ): Result[Seq[ScoredRecord]] = Try {
    withConnection { conn =>
      val vectorStr = embeddingToString(queryVector)

      // Build collection filter
      val collectionPlaceholders = collectionIds.map(_ => "?").mkString(",")

      // Build the query with permission filtering
      val (additionalWhereClause, additionalParams) = additionalFilter match {
        case Some(filter) => filterToSql(filter)
        case None         => ("", Seq.empty[Any])
      }

      val whereClause = if (auth.isAdmin) {
        s"collection_id IN ($collectionPlaceholders)" +
          (if (additionalWhereClause.nonEmpty) s" AND $additionalWhereClause" else "")
      } else {
        s"collection_id IN ($collectionPlaceholders) AND (readable_by = '{}' OR readable_by && ?)" +
          (if (additionalWhereClause.nonEmpty) s" AND $additionalWhereClause" else "")
      }

      val sql = s"""
        SELECT id, embedding, embedding_dim, content, metadata,
               1 - (embedding <=> ?::vector) AS similarity
        FROM $vectorTableName
        WHERE $whereClause
        ORDER BY embedding <=> ?::vector
        LIMIT ?
      """

      Using.resource(conn.prepareStatement(sql)) { stmt =>
        var paramIdx = 1

        // Query vector for similarity calculation
        stmt.setString(paramIdx, vectorStr)
        paramIdx += 1

        // Collection IDs
        collectionIds.foreach { id =>
          stmt.setInt(paramIdx, id)
          paramIdx += 1
        }

        // Permission filter (unless admin)
        if (!auth.isAdmin) {
          val authArray = createIntArray(conn, auth.asSeq)
          stmt.setArray(paramIdx, authArray)
          paramIdx += 1
        }

        // Additional filter params
        additionalParams.foreach { param =>
          param match {
            case s: String  => stmt.setString(paramIdx, s)
            case i: Int     => stmt.setInt(paramIdx, i)
            case l: Long    => stmt.setLong(paramIdx, l)
            case d: Double  => stmt.setDouble(paramIdx, d)
            case b: Boolean => stmt.setBoolean(paramIdx, b)
            case _          => stmt.setObject(paramIdx, param)
          }
          paramIdx += 1
        }

        // Query vector for ordering
        stmt.setString(paramIdx, vectorStr)
        paramIdx += 1

        // Limit
        stmt.setInt(paramIdx, topK)

        Using.resource(stmt.executeQuery()) { rs =>
          val buffer = ArrayBuffer[ScoredRecord]()
          while (rs.next())
            rowToRecord(rs).foreach { record =>
              val score = math.max(0.0, math.min(1.0, rs.getDouble("similarity")))
              buffer += ScoredRecord(record, score)
            }
          buffer.toSeq
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-search-index-query", e.getMessage))

  override def ingest(
    collectionPath: CollectionPath,
    documentId: String,
    chunks: Seq[ChunkWithEmbedding],
    metadata: Map[String, String],
    readableBy: Set[PrincipalId]
  ): Result[Int] =
    for {
      // Verify collection exists and is a leaf
      collectionOpt <- _collections.get(collectionPath)
      collection <- collectionOpt.toRight(
        ProcessingError("pg-search-index-ingest", s"Collection not found: ${collectionPath.value}")
      )
      _ <-
        if (!collection.isLeaf) {
          Left(
            ProcessingError(
              "pg-search-index-ingest",
              s"Cannot ingest into non-leaf collection: ${collectionPath.value}"
            )
          )
        } else {
          Right(())
        }

      // Insert chunks
      count <- insertChunks(collection.id, documentId, chunks, metadata, readableBy)
    } yield count

  private def insertChunks(
    collectionId: Int,
    documentId: String,
    chunks: Seq[ChunkWithEmbedding],
    metadata: Map[String, String],
    readableBy: Set[PrincipalId]
  ): Result[Int] = Try {
    withConnection { conn =>
      Using.resource(new AutoCloseable {
        private val original = conn.getAutoCommit
        conn.setAutoCommit(false)
        override def close(): Unit = conn.setAutoCommit(original)
      }) { _ =>
        val attempt =
          Try {
            val sql = s"""
              INSERT INTO $vectorTableName
              (id, embedding, embedding_dim, content, metadata, collection_id, readable_by)
              VALUES (?, ?::vector, ?, ?, ?::jsonb, ?, ?)
              ON CONFLICT (id) DO UPDATE SET
                embedding = EXCLUDED.embedding,
                embedding_dim = EXCLUDED.embedding_dim,
                content = EXCLUDED.content,
                metadata = EXCLUDED.metadata,
                collection_id = EXCLUDED.collection_id,
                readable_by = EXCLUDED.readable_by
            """

            val readableByArray = createIntArray(conn, readableBy.map(_.value).toSeq)

            Using.resource(conn.prepareStatement(sql)) { stmt =>
              chunks.foreach { chunk =>
                // Include collectionId in chunk ID to prevent cross-collection overwrites
                // when the same documentId is ingested into multiple collections
                val chunkId = s"coll-$collectionId-$documentId-chunk-${chunk.chunkIndex}"
                val chunkMetadata =
                  metadata ++ chunk.metadata + ("docId" -> documentId) + ("chunkIndex" -> chunk.chunkIndex.toString)

                stmt.setString(1, chunkId)
                stmt.setString(2, embeddingToString(chunk.embedding))
                stmt.setInt(3, chunk.dimensions)
                stmt.setString(4, chunk.content)
                stmt.setString(5, mapToJson(chunkMetadata))
                stmt.setInt(6, collectionId)
                stmt.setArray(7, readableByArray)
                stmt.addBatch()
              }
              stmt.executeBatch()
            }

            chunks.size
          }.flatMap(v => Try(conn.commit()).map(_ => v))

        attempt.recoverWith { case e: Exception =>
          Try(conn.rollback()).flatMap(_ => scala.util.Failure(e))
        }.get
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-search-index-insert", e.getMessage))

  override def deleteDocument(collectionPath: CollectionPath, documentId: String): Result[Long] =
    for {
      collectionOpt <- _collections.get(collectionPath)
      collection <- collectionOpt.toRight(
        ProcessingError("pg-search-index-delete", s"Collection not found: ${collectionPath.value}")
      )
      count <- doDeleteDocument(collection.id, documentId)
    } yield count

  private def doDeleteDocument(collectionId: Int, documentId: String): Result[Long] = Try {
    withConnection { conn =>
      Using.resource(conn.prepareStatement(s"""
        DELETE FROM $vectorTableName
        WHERE collection_id = ? AND metadata->>'docId' = ?
      """)) { stmt =>
        stmt.setInt(1, collectionId)
        stmt.setString(2, documentId)
        stmt.executeUpdate().toLong
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-search-index-delete-doc", e.getMessage))

  override def clearCollection(collectionPath: CollectionPath): Result[Long] =
    for {
      collectionOpt <- _collections.get(collectionPath)
      collection <- collectionOpt.toRight(
        ProcessingError("pg-search-index-clear", s"Collection not found: ${collectionPath.value}")
      )
      count <- doClearCollection(collection.id)
    } yield count

  private def doClearCollection(collectionId: Int): Result[Long] = Try {
    withConnection { conn =>
      Using.resource(conn.prepareStatement(s"""
        DELETE FROM $vectorTableName WHERE collection_id = ?
      """)) { stmt =>
        stmt.setInt(1, collectionId)
        stmt.executeUpdate().toLong
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-search-index-clear", e.getMessage))

  override def initializeSchema(): Result[Unit] =
    withConnection { conn =>
      for {
        _ <- PgSchemaManager.initializeSchema(conn)
        _ <- PgSchemaManager.extendVectorsTable(conn, vectorTableName)
      } yield ()
    }

  override def dropSchema(): Result[Unit] =
    withConnection(conn => PgSchemaManager.dropSchema(conn, vectorTableName))

  override def close(): Unit =
    dataSource.close()

  // Helper methods

  private def filterToSql(filter: MetadataFilter): (String, Seq[Any]) = filter match {
    case MetadataFilter.All =>
      ("", Seq.empty)

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
      if (leftSql.isEmpty && rightSql.isEmpty) ("", Seq.empty)
      else if (leftSql.isEmpty) (rightSql, rightParams)
      else if (rightSql.isEmpty) (leftSql, leftParams)
      else (s"($leftSql AND $rightSql)", leftParams ++ rightParams)

    case MetadataFilter.Or(left, right) =>
      val (leftSql, leftParams)   = filterToSql(left)
      val (rightSql, rightParams) = filterToSql(right)
      if (leftSql.isEmpty || rightSql.isEmpty) ("", Seq.empty)
      else (s"($leftSql OR $rightSql)", leftParams ++ rightParams)

    case MetadataFilter.Not(inner) =>
      val (innerSql, innerParams) = filterToSql(inner)
      if (innerSql.isEmpty) ("", Seq.empty)
      else (s"NOT ($innerSql)", innerParams)
  }

  private def rowToRecord(rs: ResultSet): Option[VectorRecord] = {
    val id           = rs.getString("id")
    val embeddingStr = rs.getString("embedding")
    val embeddingDim = rs.getInt("embedding_dim")

    parseEmbedding(embeddingStr) match {
      case Some(embedding) =>
        val metadata = jsonToMap(rs.getString("metadata"))

        Some(
          VectorRecord(
            id = id,
            embedding = embedding,
            content = Option(rs.getString("content")),
            metadata = metadata
          )
        )

      case None =>
        rateLimitedLogger.warn(s"Skipping corrupt vector record: id=$id, embedding_dim=$embeddingDim")
        None
    }
  }

  private def embeddingToString(embedding: Array[Float]): String =
    "[" + embedding.map(f => f.toString).mkString(",") + "]"

  /**
   * Parse embedding string to float array.
   * Returns None if parsing fails (logged by caller with context).
   * Package-private for unit testing.
   */
  private[pg] def parseEmbedding(str: String): Option[Array[Float]] =
    org.llm4s.vectorstore.EmbeddingParser.parse(str)

  private def createIntArray(conn: Connection, values: Seq[Int]): SqlArray =
    conn.createArrayOf("integer", values.map(Int.box).toArray)

  private def mapToJson(map: Map[String, String]): String =
    if (map.isEmpty) "{}"
    else {
      val entries = map.map { case (k, v) =>
        val escapedKey   = k.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedValue = v.replace("\\", "\\\\").replace("\"", "\\\"")
        s""""$escapedKey":"$escapedValue""""
      }
      s"{${entries.mkString(",")}}"
    }

  private def jsonToMap(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else {
      // Simple JSON parsing for flat string maps
      val pattern = """"([^"\\]*(?:\\.[^"\\]*)*)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".r
      pattern
        .findAllMatchIn(json)
        .map { m =>
          val key   = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\")
          val value = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\")
          key -> value
        }
        .toMap
    }

  private def withConnection[A](f: Connection => A): A =
    Using.resource(dataSource.getConnection)(f)
}

object PgSearchIndex {

  /**
   * Create a new PgSearchIndex with the given configuration.
   *
   * @param config PostgreSQL configuration
   * @return The search index, or error
   */
  def apply(config: SearchIndex.PgConfig): Result[PgSearchIndex] = Try {
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
    new PgSearchIndex(dataSource, config.vectorTableName, config)
  }.toEither.left.map(e => ProcessingError("pg-search-index-create", e.getMessage))

  /**
   * Create a PgSearchIndex from a JDBC URL.
   *
   * @param jdbcUrl JDBC URL (e.g., "jdbc:postgresql://localhost:5432/mydb")
   * @param user Database user
   * @param password Database password
   * @param vectorTableName Name of the vectors table
   * @return The search index, or error
   */
  def fromJdbcUrl(
    jdbcUrl: String,
    user: String,
    password: String,
    vectorTableName: String = "vectors"
  ): Result[PgSearchIndex] = {
    // Parse JDBC URL to extract host, port, database
    val pattern = """jdbc:postgresql://([^:/]+):?(\d+)?/(.+)""".r
    jdbcUrl match {
      case pattern(host, portStr, database) =>
        val port = Option(portStr).map(_.toInt).getOrElse(5432)
        apply(
          SearchIndex.PgConfig(
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            vectorTableName = vectorTableName
          )
        )
      case _ =>
        Left(ProcessingError("pg-search-index", s"Invalid JDBC URL: $jdbcUrl"))
    }
  }
}
