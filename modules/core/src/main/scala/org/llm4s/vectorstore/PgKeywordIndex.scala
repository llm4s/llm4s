package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.{ LLMError, ProcessingError }
import org.llm4s.util.SqlIdentifier

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import java.sql.{ Connection, PreparedStatement, ResultSet }
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

/**
 * PostgreSQL-based keyword index implementation using native full-text search.
 *
 * Uses PostgreSQL's tsvector/tsquery for efficient text indexing and ranking.
 * Provides BM25-like scoring via ts_rank_cd (cover density ranking).
 *
 * Requirements:
 * - PostgreSQL 16+ (18+ recommended for best performance)
 *
 * Features:
 * - Native PostgreSQL full-text search with tsvector
 * - ts_rank_cd scoring for relevance ranking
 * - ts_headline for snippet highlighting
 * - GIN indexing for fast full-text lookups
 * - JSONB metadata storage with GIN index
 * - Connection pooling via HikariCP
 *
 * Query syntax (via websearch_to_tsquery):
 * - "hello world" - documents containing both terms
 * - "hello OR world" - documents containing either term
 * - "-hello" - exclude documents with hello
 * - "\"hello world\"" - exact phrase match
 *
 * @param dataSource HikariCP data source for connection pooling
 * @param tableName Base table name (creates {tableName}_keyword table)
 * @param language PostgreSQL text search configuration (default: "english")
 * @param ownsDataSource Whether to close dataSource on close()
 */
final class PgKeywordIndex private (
  private val dataSource: HikariDataSource,
  val tableName: String,
  val language: String,
  val ownsDataSource: Boolean
) extends KeywordIndex {

  private val keywordTableName = s"${tableName}_keyword"

  // Initialize schema on creation
  initializeSchema()

  private def initializeSchema(): Unit =
    withConnection { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        // Main keyword table with generated tsvector column
        stmt.execute(s"""
          CREATE TABLE IF NOT EXISTS $keywordTableName (
            id TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('$language', content)) STORED,
            metadata JSONB DEFAULT '{}',
            created_at TIMESTAMPTZ DEFAULT NOW()
          )
        """)

        // GIN index for fast full-text search
        stmt.execute(
          s"CREATE INDEX IF NOT EXISTS idx_${keywordTableName}_tsv ON $keywordTableName USING GIN(content_tsv)"
        )

        // GIN index for JSONB metadata queries
        stmt.execute(
          s"CREATE INDEX IF NOT EXISTS idx_${keywordTableName}_metadata ON $keywordTableName USING GIN(metadata)"
        )

        // Index for created_at ordering
        stmt.execute(
          s"CREATE INDEX IF NOT EXISTS idx_${keywordTableName}_created ON $keywordTableName(created_at)"
        )
      }
      ()
    }

  override def index(doc: KeywordDocument): Result[Unit] =
    indexBatch(Seq(doc))

  override def indexBatch(docs: Seq[KeywordDocument]): Result[Unit] =
    if (docs.isEmpty) Right(())
    else
      Try {
        withConnection { conn =>
          conn.setAutoCommit(false)
          val result = Try {
            val sql = s"""
              INSERT INTO $keywordTableName (id, content, metadata)
              VALUES (?, ?, ?::jsonb)
              ON CONFLICT (id) DO UPDATE SET
                content = EXCLUDED.content,
                metadata = EXCLUDED.metadata,
                created_at = NOW()
            """

            Using.resource(conn.prepareStatement(sql)) { stmt =>
              docs.foreach { doc =>
                stmt.setString(1, doc.id)
                stmt.setString(2, doc.content)
                stmt.setString(3, metadataToJson(doc.metadata))
                stmt.addBatch()
              }
              stmt.executeBatch()
            }
          }

          result match {
            case scala.util.Success(_) =>
              conn.commit()
              conn.setAutoCommit(true)
              Right(())
            case scala.util.Failure(e) =>
              conn.rollback()
              conn.setAutoCommit(true)
              Left(ProcessingError("pg-keyword-index", s"Index batch failed: ${e.getMessage}"))
          }
        }
      }.toEither.left
        .map[LLMError](e => ProcessingError("pg-keyword-index", s"Index batch failed: ${e.getMessage}"))
        .flatMap(identity)

  override def search(
    query: String,
    topK: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[KeywordSearchResult]] =
    Try {
      if (query.trim.isEmpty) {
        Seq.empty[KeywordSearchResult]
      } else {
        withConnection { conn =>
          val (filterClause, filterParams) = filter.map(filterToSql).getOrElse(("TRUE", Seq.empty))

          val sql = s"""
            SELECT id, content, metadata, ts_rank_cd(content_tsv, query) AS score
            FROM $keywordTableName, websearch_to_tsquery('$language', ?) AS query
            WHERE content_tsv @@ query AND $filterClause
            ORDER BY score DESC
            LIMIT ?
          """

          Using.resource(conn.prepareStatement(sql)) { stmt =>
            var paramIdx = 1
            stmt.setString(paramIdx, query)
            paramIdx += 1

            filterParams.foreach { param =>
              setParameter(stmt, paramIdx, param)
              paramIdx += 1
            }

            stmt.setInt(paramIdx, topK)

            Using.resource(stmt.executeQuery())(rs => collectResults(rs, includeHighlights = false))
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Search failed: ${e.getMessage}"))

  override def searchWithHighlights(
    query: String,
    topK: Int,
    snippetLength: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[KeywordSearchResult]] =
    Try {
      if (query.trim.isEmpty) {
        Seq.empty[KeywordSearchResult]
      } else {
        withConnection { conn =>
          val (filterClause, filterParams) = filter.map(filterToSql).getOrElse(("TRUE", Seq.empty))

          // ts_headline options:
          // StartSel/StopSel - highlight markers
          // MaxFragments - number of fragments to return
          // MaxWords - maximum words per fragment
          val maxWords = math.max(10, snippetLength / 5) // Approximate words from snippet length
          val sql = s"""
            SELECT id, content, metadata,
                   ts_rank_cd(content_tsv, query) AS score,
                   ts_headline('$language', content, query,
                     'StartSel=<b>, StopSel=</b>, MaxFragments=3, MaxWords=$maxWords, MinWords=5, FragmentDelimiter=...') AS highlight
            FROM $keywordTableName, websearch_to_tsquery('$language', ?) AS query
            WHERE content_tsv @@ query AND $filterClause
            ORDER BY score DESC
            LIMIT ?
          """

          Using.resource(conn.prepareStatement(sql)) { stmt =>
            var paramIdx = 1
            stmt.setString(paramIdx, query)
            paramIdx += 1

            filterParams.foreach { param =>
              setParameter(stmt, paramIdx, param)
              paramIdx += 1
            }

            stmt.setInt(paramIdx, topK)

            Using.resource(stmt.executeQuery())(rs => collectResults(rs, includeHighlights = true))
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Search with highlights failed: ${e.getMessage}"))

  override def get(id: String): Result[Option[KeywordDocument]] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"SELECT id, content, metadata FROM $keywordTableName WHERE id = ?")) {
          stmt =>
            stmt.setString(1, id)
            Using.resource(stmt.executeQuery()) { rs =>
              if (rs.next()) {
                Some(
                  KeywordDocument(
                    id = rs.getString("id"),
                    content = rs.getString("content"),
                    metadata = jsonToMetadata(rs.getString("metadata"))
                  )
                )
              } else None
            }
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Get failed: ${e.getMessage}"))

  override def delete(id: String): Result[Unit] =
    deleteBatch(Seq(id))

  override def deleteBatch(ids: Seq[String]): Result[Unit] =
    if (ids.isEmpty) Right(())
    else
      Try {
        withConnection { conn =>
          val placeholders = ids.map(_ => "?").mkString(",")
          Using.resource(conn.prepareStatement(s"DELETE FROM $keywordTableName WHERE id IN ($placeholders)")) { stmt =>
            ids.zipWithIndex.foreach { case (id, idx) =>
              stmt.setString(idx + 1, id)
            }
            stmt.executeUpdate()
            ()
          }
        }
      }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Delete batch failed: ${e.getMessage}"))

  override def deleteByPrefix(prefix: String): Result[Long] =
    Try {
      withConnection { conn =>
        Using.resource(conn.prepareStatement(s"DELETE FROM $keywordTableName WHERE id LIKE ?")) { stmt =>
          stmt.setString(1, prefix + "%")
          stmt.executeUpdate().toLong
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Delete by prefix failed: ${e.getMessage}"))

  override def count(): Result[Long] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          Using.resource(stmt.executeQuery(s"SELECT COUNT(*) FROM $keywordTableName")) { rs =>
            rs.next()
            rs.getLong(1)
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Count failed: ${e.getMessage}"))

  override def clear(): Result[Unit] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          stmt.execute(s"TRUNCATE TABLE $keywordTableName")
          ()
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Clear failed: ${e.getMessage}"))

  override def close(): Unit =
    if (ownsDataSource && !dataSource.isClosed) {
      dataSource.close()
    }

  override def stats(): Result[KeywordIndexStats] =
    Try {
      withConnection { conn =>
        Using.resource(conn.createStatement()) { stmt =>
          // Document count
          val total = Using.resource(stmt.executeQuery(s"SELECT COUNT(*) FROM $keywordTableName")) { rs =>
            rs.next()
            rs.getLong(1)
          }

          // Approximate token count (word count)
          val totalTokens = Using.resource(
            stmt.executeQuery(
              s"SELECT SUM(array_length(regexp_split_to_array(content, '\\s+'), 1)) FROM $keywordTableName"
            )
          ) { rs =>
            rs.next()
            Option(rs.getLong(1)).filter(_ > 0)
          }

          // Average document length
          val avgLength = totalTokens.map(_.toDouble / total.max(1))

          // Table size
          val size = Try {
            Using.resource(stmt.executeQuery(s"SELECT pg_total_relation_size('$keywordTableName')")) { rs =>
              rs.next()
              Some(rs.getLong(1))
            }
          }.getOrElse(None)

          KeywordIndexStats(
            totalDocuments = total,
            totalTokens = totalTokens,
            avgDocumentLength = avgLength,
            indexSizeBytes = size
          )
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Stats failed: ${e.getMessage}"))

  // ============================================================
  // Helper Methods
  // ============================================================

  private def withConnection[A](f: Connection => A): A =
    Using.resource(dataSource.getConnection)(f)

  private def collectResults(rs: ResultSet, includeHighlights: Boolean): Seq[KeywordSearchResult] = {
    val builder = ArrayBuffer.empty[KeywordSearchResult]
    while (rs.next()) {
      val highlights =
        if (includeHighlights) {
          val highlight = rs.getString("highlight")
          if (highlight != null && highlight.nonEmpty) Seq(highlight)
          else Seq.empty
        } else Seq.empty

      builder += KeywordSearchResult(
        id = rs.getString("id"),
        content = rs.getString("content"),
        score = rs.getDouble("score"),
        metadata = jsonToMetadata(rs.getString("metadata")),
        highlights = highlights
      )
    }
    builder.toSeq
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
    else {
      val entries = metadata.map { case (k, v) =>
        val escapedKey   = k.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedValue = v.replace("\\", "\\\\").replace("\"", "\\\"")
        s""""$escapedKey":"$escapedValue""""
      }
      s"{${entries.mkString(",")}}"
    }

  private def jsonToMetadata(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else {
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
}

object PgKeywordIndex {

  private val AllowedLanguages: Set[String] = Set(
    "simple",
    "english",
    "arabic",
    "armenian",
    "basque",
    "catalan",
    "danish",
    "dutch",
    "finnish",
    "french",
    "german",
    "greek",
    "hindi",
    "hungarian",
    "indonesian",
    "irish",
    "italian",
    "lithuanian",
    "nepali",
    "norwegian",
    "portuguese",
    "romanian",
    "russian",
    "serbian",
    "spanish",
    "swedish",
    "tamil",
    "turkish",
    "yiddish"
  )

  private def validateLanguage(language: String, operation: String): Result[String] =
    if (language == null)
      Left(ProcessingError(operation, "Language must not be null"))
    else {
      val normalized = language.trim.toLowerCase
      if (AllowedLanguages.contains(normalized)) Right(normalized)
      else
        Left(
          ProcessingError(
            operation,
            s"Invalid language: '$language'. Allowed values: ${AllowedLanguages.toSeq.sorted.mkString(", ")}."
          )
        )
    }

  /**
   * Configuration for PgKeywordIndex.
   *
   * @param host Database host
   * @param port Database port
   * @param database Database name
   * @param user Database user
   * @param password Database password
   * @param tableName Base table name (creates {tableName}_keyword table)
   * @param maxPoolSize Maximum connection pool size (default: 10)
   * @param language PostgreSQL text search configuration (default: "english")
   */
  final case class Config(
    host: String = "localhost",
    port: Int = 5432,
    database: String = "postgres",
    user: String = "postgres",
    password: String = "",
    tableName: String = "documents",
    maxPoolSize: Int = 10,
    language: String = "english"
  ) {
    def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"

    def withTableName(name: String): Config           = copy(tableName = name)
    def withLanguage(lang: String): Config            = copy(language = lang)
    def withMaxPoolSize(size: Int): Config            = copy(maxPoolSize = size)
    def withHost(h: String): Config                   = copy(host = h)
    def withPort(p: Int): Config                      = copy(port = p)
    def withDatabase(db: String): Config              = copy(database = db)
    def withCredentials(u: String, p: String): Config = copy(user = u, password = p)
  }

  object Config {
    val default: Config = Config()

    def local(tableName: String = "documents"): Config =
      Config(tableName = tableName)

    def fromJdbc(jdbcUrl: String, user: String, password: String, tableName: String = "documents"): Config = {
      val pattern = """jdbc:postgresql://([^:/]+):?(\d+)?/(.+)""".r
      jdbcUrl match {
        case pattern(host, portStr, database) =>
          val port = Option(portStr).map(_.toInt).getOrElse(5432)
          Config(
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            tableName = tableName
          )
        case _ =>
          // Fallback: just use defaults with provided credentials
          Config(user = user, password = password, tableName = tableName)
      }
    }
  }

  /**
   * Create a PgKeywordIndex from configuration.
   *
   * @param config The index configuration
   * @return The keyword index or error
   */
  def apply(config: Config): Result[PgKeywordIndex] =
    SqlIdentifier.validate(config.tableName, "pg-keyword-index").flatMap { _ =>
      validateLanguage(config.language, "pg-keyword-index").flatMap { validatedLanguage =>
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
          new PgKeywordIndex(dataSource, config.tableName, validatedLanguage, ownsDataSource = true)
        }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Failed to create index: ${e.getMessage}"))
      }
    }

  /**
   * Create a PgKeywordIndex with an existing HikariDataSource.
   *
   * Useful for sharing a connection pool with PgVectorStore.
   *
   * @param dataSource Existing HikariDataSource
   * @param tableName Base table name
   * @param language PostgreSQL text search configuration
   * @return The keyword index or error
   */
  def apply(dataSource: HikariDataSource, tableName: String, language: String): Result[PgKeywordIndex] =
    SqlIdentifier.validate(tableName, "pg-keyword-index").flatMap { _ =>
      validateLanguage(language, "pg-keyword-index").flatMap { validatedLanguage =>
        Try {
          new PgKeywordIndex(dataSource, tableName, validatedLanguage, ownsDataSource = false)
        }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Failed to create index: ${e.getMessage}"))
      }
    }

  /**
   * Create a PgKeywordIndex with an existing HikariDataSource using default language.
   *
   * Note: The provided dataSource will NOT be closed when the index is closed.
   * The caller is responsible for managing the dataSource lifecycle.
   *
   * @param dataSource Existing HikariDataSource
   * @param tableName Base table name
   * @return The keyword index or error
   */
  def apply(dataSource: HikariDataSource, tableName: String): Result[PgKeywordIndex] =
    SqlIdentifier.validate(tableName, "pg-keyword-index").flatMap { _ =>
      Try {
        new PgKeywordIndex(dataSource, tableName, language = "english", ownsDataSource = false)
      }.toEither.left.map(e => ProcessingError("pg-keyword-index", s"Failed to create index: ${e.getMessage}"))
    }

  /**
   * Create a PgKeywordIndex from connection string.
   *
   * @param connectionString PostgreSQL connection string (jdbc:postgresql://...)
   * @param user Database user
   * @param password Database password
   * @param tableName Base table name
   * @return The keyword index or error
   */
  def apply(
    connectionString: String,
    user: String,
    password: String,
    tableName: String
  ): Result[PgKeywordIndex] =
    apply(Config.fromJdbc(connectionString, user, password, tableName))

  /**
   * Create a PgKeywordIndex with default local settings.
   *
   * Connects to localhost:5432/postgres with user postgres.
   *
   * @param tableName Base table name
   * @return The keyword index or error
   */
  def local(tableName: String = "documents"): Result[PgKeywordIndex] =
    apply(Config.local(tableName))
}
