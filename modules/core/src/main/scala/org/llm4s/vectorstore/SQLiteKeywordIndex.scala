package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError

import java.sql.{ Connection, DriverManager, ResultSet }
import scala.util.Try
import upickle.default._

/**
 * SQLite FTS5-based keyword index implementation.
 *
 * Uses SQLite's Full-Text Search 5 extension with BM25 scoring.
 * FTS5 provides efficient text indexing and ranking capabilities.
 *
 * Features:
 * - BM25 relevance scoring
 * - Snippet highlighting
 * - Boolean query operators (AND, OR, NOT)
 * - Phrase matching with quotes
 * - Prefix matching with *
 *
 * Query syntax examples:
 * - "hello world" - documents containing both terms
 * - "hello OR world" - documents containing either term
 * - "hello NOT world" - documents with hello but not world
 * - "\"hello world\"" - exact phrase match
 * - "hello*" - prefix match
 */
final class SQLiteKeywordIndex private (
  private val connection: Connection,
  val tableName: String
) extends KeywordIndex {

  private val ftsTableName = s"${tableName}_fts"

  // Initialize tables
  initializeTables()

  private def initializeTables(): Unit = {
    val stmt = connection.createStatement()

    // Main table for document storage and metadata
    stmt.executeUpdate(s"""
      CREATE TABLE IF NOT EXISTS $tableName (
        id TEXT PRIMARY KEY,
        content TEXT NOT NULL,
        metadata TEXT
      )
    """)

    // FTS5 virtual table for full-text search
    // Standard FTS5 table that stores its own content for snippet() support
    stmt.executeUpdate(s"""
      CREATE VIRTUAL TABLE IF NOT EXISTS $ftsTableName USING fts5(
        id,
        content
      )
    """)

    // Triggers to keep FTS index in sync with main table
    stmt.executeUpdate(s"""
      CREATE TRIGGER IF NOT EXISTS ${tableName}_ai AFTER INSERT ON $tableName BEGIN
        INSERT INTO $ftsTableName(id, content) VALUES (NEW.id, NEW.content);
      END
    """)

    stmt.executeUpdate(s"""
      CREATE TRIGGER IF NOT EXISTS ${tableName}_ad AFTER DELETE ON $tableName BEGIN
        DELETE FROM $ftsTableName WHERE id = OLD.id;
      END
    """)

    stmt.executeUpdate(s"""
      CREATE TRIGGER IF NOT EXISTS ${tableName}_au AFTER UPDATE ON $tableName BEGIN
        DELETE FROM $ftsTableName WHERE id = OLD.id;
        INSERT INTO $ftsTableName(id, content) VALUES (NEW.id, NEW.content);
      END
    """)

    stmt.close()
  }

  override def index(doc: KeywordDocument): Result[Unit] =
    indexBatch(Seq(doc))

  override def indexBatch(docs: Seq[KeywordDocument]): Result[Unit] =
    if (docs.isEmpty) Right(())
    else
      withTransaction {
        val sql  = s"INSERT OR REPLACE INTO $tableName (id, content, metadata) VALUES (?, ?, ?)"
        val stmt = connection.prepareStatement(sql)

        docs.foreach { doc =>
          stmt.setString(1, doc.id)
          stmt.setString(2, doc.content)
          stmt.setString(3, serializeMetadata(doc.metadata))
          stmt.addBatch()
        }

        stmt.executeBatch()
        stmt.close()
      }

  override def search(
    query: String,
    topK: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[KeywordSearchResult]] =
    buildOptionalFilterClause(filter).flatMap { filterClause =>
      Try {
        val escapedQuery = escapeQuery(query)

        // FTS5 match with BM25 scoring
        // bm25() returns negative values (more negative = more relevant), so we negate it
        val sql =
          s"""
        SELECT d.id, d.content, d.metadata, -bm25($ftsTableName) as score
        FROM $ftsTableName f
        JOIN $tableName d ON f.id = d.id
        WHERE f.content MATCH ?
        $filterClause
        ORDER BY score DESC
        LIMIT ?
      """

        val stmt = connection.prepareStatement(sql)
        stmt.setString(1, escapedQuery)
        stmt.setInt(2, topK)

        val rs      = stmt.executeQuery()
        val results = collectResults(rs, includeHighlights = false)
        stmt.close()
        results
      }.toEither.left.map(e => ProcessingError("keyword-index", s"Search failed: ${e.getMessage}"))
    }

  override def searchWithHighlights(
    query: String,
    topK: Int,
    snippetLength: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[KeywordSearchResult]] =
    buildOptionalFilterClause(filter).flatMap { filterClause =>
      Try {
        val escapedQuery = escapeQuery(query)

        // FTS5 snippet function for highlighting
        val sql =
          s"""
        SELECT d.id, d.content, d.metadata, -bm25($ftsTableName) as score,
               snippet($ftsTableName, 1, '<b>', '</b>', '...', $snippetLength) as highlight
        FROM $ftsTableName f
        JOIN $tableName d ON f.id = d.id
        WHERE f.content MATCH ?
        $filterClause
        ORDER BY score DESC
        LIMIT ?
      """

        val stmt = connection.prepareStatement(sql)
        stmt.setString(1, escapedQuery)
        stmt.setInt(2, topK)

        val rs      = stmt.executeQuery()
        val results = collectResults(rs, includeHighlights = true)
        stmt.close()
        results
      }.toEither.left.map(e => ProcessingError("keyword-index", s"Search with highlights failed: ${e.getMessage}"))
    }

  override def get(id: String): Result[Option[KeywordDocument]] =
    Try {
      val sql  = s"SELECT id, content, metadata FROM $tableName WHERE id = ?"
      val stmt = connection.prepareStatement(sql)
      stmt.setString(1, id)

      val rs = stmt.executeQuery()
      val result =
        if (rs.next()) {
          Some(
            KeywordDocument(
              id = rs.getString("id"),
              content = rs.getString("content"),
              metadata = deserializeMetadata(rs.getString("metadata"))
            )
          )
        } else None

      stmt.close()
      result
    }.toEither.left.map(e => ProcessingError("keyword-index", s"Get failed: ${e.getMessage}"))

  override def delete(id: String): Result[Unit] =
    deleteBatch(Seq(id))

  override def deleteBatch(ids: Seq[String]): Result[Unit] =
    if (ids.isEmpty) Right(())
    else
      withTransaction {
        val placeholders = ids.map(_ => "?").mkString(",")
        val sql          = s"DELETE FROM $tableName WHERE id IN ($placeholders)"
        val stmt         = connection.prepareStatement(sql)

        ids.zipWithIndex.foreach { case (id, i) =>
          stmt.setString(i + 1, id)
        }

        stmt.executeUpdate()
        stmt.close()
      }

  override def deleteByPrefix(prefix: String): Result[Long] =
    Try {
      val stmt = connection.prepareStatement(s"DELETE FROM $tableName WHERE id LIKE ?")
      stmt.setString(1, prefix + "%")
      val deleted = stmt.executeUpdate().toLong
      stmt.close()
      deleted
    }.toEither.left.map(e => ProcessingError("keyword-index", s"Delete by prefix failed: ${e.getMessage}"))

  override def count(): Result[Long] =
    Try {
      val stmt = connection.createStatement()
      val rs   = stmt.executeQuery(s"SELECT COUNT(*) FROM $tableName")
      rs.next()
      val count = rs.getLong(1)
      stmt.close()
      count
    }.toEither.left.map(e => ProcessingError("keyword-index", s"Count failed: ${e.getMessage}"))

  override def clear(): Result[Unit] =
    withTransaction {
      val stmt = connection.createStatement()
      stmt.executeUpdate(s"DELETE FROM $tableName")
      stmt.close()
    }

  override def close(): Unit =
    if (!connection.isClosed) {
      connection.close()
    }

  override def stats(): Result[KeywordIndexStats] =
    Try {
      val stmt = connection.createStatement()

      // Get document count
      val countRs = stmt.executeQuery(s"SELECT COUNT(*) FROM $tableName")
      countRs.next()
      val totalDocs = countRs.getLong(1)

      // Approximate total tokens (word count)
      val tokensRs = stmt.executeQuery(
        s"SELECT SUM(LENGTH(content) - LENGTH(REPLACE(content, ' ', '')) + 1) FROM $tableName"
      )
      tokensRs.next()
      val totalTokens = Option(tokensRs.getLong(1)).filter(_ > 0)

      // Average document length
      val avgLength = totalTokens.map(_.toDouble / totalDocs.max(1))

      stmt.close()

      KeywordIndexStats(
        totalDocuments = totalDocs,
        totalTokens = totalTokens,
        avgDocumentLength = avgLength
      )
    }.toEither.left.map(e => ProcessingError("keyword-index", s"Stats failed: ${e.getMessage}"))

  // Helper methods

  private val ValidMetadataKeyPattern: String = "^[a-zA-Z0-9_][a-zA-Z0-9_.-]*$"

  private def withTransaction[T](f: => T): Result[Unit] =
    Try {
      val autoCommit = connection.getAutoCommit
      connection.setAutoCommit(false)
      f
      connection.commit()
      connection.setAutoCommit(autoCommit)
    }.toEither.left.map { e =>
      connection.rollback()
      ProcessingError("keyword-index", s"Transaction failed: ${e.getMessage}")
    }

  private def collectResults(rs: ResultSet, includeHighlights: Boolean): Seq[KeywordSearchResult] = {
    val builder = Seq.newBuilder[KeywordSearchResult]
    while (rs.next()) {
      val highlights =
        if (includeHighlights) Seq(rs.getString("highlight"))
        else Seq.empty

      builder += KeywordSearchResult(
        id = rs.getString("id"),
        content = rs.getString("content"),
        score = rs.getDouble("score"),
        metadata = deserializeMetadata(rs.getString("metadata")),
        highlights = highlights
      )
    }
    builder.result()
  }

  private def escapeQuery(query: String): String = {
    // FTS5 query escaping
    // Preserve quoted phrases for phrase matching while escaping individual terms
    val normalized = query
      .replaceAll("\\s+", " ") // Normalize whitespace
      .trim

    // Check if query already contains FTS5 phrase quotes
    if (normalized.contains("\"")) {
      // Query has explicit phrase markers - pass through with minimal escaping
      // Just ensure single quotes become double quotes for FTS5
      normalized.replace("'", "\"")
    } else {
      // No phrase markers - wrap each word in quotes to treat as literals
      // This prevents FTS5 from interpreting special characters as operators
      normalized
        .split("\\s+")
        .filter(_.nonEmpty)
        .map(word => s""""$word"""")
        .mkString(" ")
    }
  }

  private def buildOptionalFilterClause(filter: Option[MetadataFilter]): Result[String] =
    filter match {
      case Some(f) => buildFilterClause(f).map(c => s"AND $c")
      case None    => Right("")
    }

  private def buildFilterClause(filter: MetadataFilter): Result[String] = filter match {
    case MetadataFilter.All =>
      Right("1=1")
    case MetadataFilter.Equals(key, value) =>
      jsonPathForKey(key).map(path => s"json_extract(d.metadata, '$path') = '${escapeString(value)}'")
    case MetadataFilter.Contains(key, substring) =>
      jsonPathForKey(key).map(path => s"json_extract(d.metadata, '$path') LIKE '%${escapeString(substring)}%'")
    case MetadataFilter.In(key, values) =>
      jsonPathForKey(key).map { path =>
        val escaped = values.map(v => s"'${escapeString(v)}'").mkString(",")
        s"json_extract(d.metadata, '$path') IN ($escaped)"
      }
    case MetadataFilter.HasKey(key) =>
      jsonPathForKey(key).map(path => s"json_extract(d.metadata, '$path') IS NOT NULL")
    case MetadataFilter.And(left, right) =>
      for {
        l <- buildFilterClause(left)
        r <- buildFilterClause(right)
      } yield s"($l AND $r)"
    case MetadataFilter.Or(left, right) =>
      for {
        l <- buildFilterClause(left)
        r <- buildFilterClause(right)
      } yield s"($l OR $r)"
    case MetadataFilter.Not(inner) =>
      buildFilterClause(inner).map(s => s"NOT ($s)")
  }

  private def validateMetadataKey(key: String): Result[String] = {
    val trimmed = Option(key).map(_.trim).getOrElse("")
    if (trimmed.isEmpty)
      Left(ProcessingError("keyword-index", "Invalid metadata key: key must not be empty"))
    else if (!trimmed.matches(ValidMetadataKeyPattern) || trimmed.endsWith(".") || trimmed.contains(".."))
      Left(ProcessingError("keyword-index", s"Invalid metadata key: '$key'"))
    else
      Right(trimmed)
  }

  // Validation guarantees segments contain only [a-zA-Z0-9_-]; the segment
  // escaping is defence-in-depth for the SQLite JSON path quoting.
  private def jsonPathForKey(key: String): Result[String] =
    validateMetadataKey(key).map { trimmed =>
      val segments = trimmed.split("\\.").toSeq
      "$" + segments.map(seg => s"""."${escapeJsonPathSegment(seg)}"""").mkString
    }

  private def escapeJsonPathSegment(seg: String): String =
    seg.replace("\\", "\\\\").replace("\"", "\\\"")

  private def escapeString(s: String): String =
    s.replace("'", "''")

  private def serializeMetadata(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else write(metadata)

  private def deserializeMetadata(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else
      Try(read[Map[String, String]](json)).getOrElse(Map.empty)
}

object SQLiteKeywordIndex {

  private val ValidSqliteTableNamePattern: String = "^[a-zA-Z_][a-zA-Z0-9_]*$"

  private def validateTableName(tableName: String): Result[String] = {
    val trimmed = Option(tableName).map(_.trim).getOrElse("")
    if (trimmed.isEmpty)
      Left(ProcessingError("keyword-index", "Table name must not be empty"))
    else if (!trimmed.matches(ValidSqliteTableNamePattern))
      Left(
        ProcessingError(
          "keyword-index",
          s"Invalid table name: '$tableName'. Must start with a letter or underscore and contain only letters, digits, or underscores."
        )
      )
    else
      Right(trimmed)
  }

  /**
   * Create a file-based keyword index.
   *
   * @param path Path to SQLite database file
   * @param tableName Table name for documents (default: "documents")
   * @return New keyword index
   */
  def apply(path: String, tableName: String = "documents"): Result[SQLiteKeywordIndex] =
    validateTableName(tableName).flatMap { validatedTableName =>
      Try {
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection(s"jdbc:sqlite:$path")
        new SQLiteKeywordIndex(connection, validatedTableName)
      }.toEither.left.map(e => ProcessingError("keyword-index", s"Failed to create index: ${e.getMessage}"))
    }

  /**
   * Create an in-memory keyword index.
   *
   * @param tableName Table name for documents (default: "documents")
   * @return New keyword index
   */
  def inMemory(tableName: String = "documents"): Result[SQLiteKeywordIndex] =
    validateTableName(tableName).flatMap { validatedTableName =>
      Try {
        Class.forName("org.sqlite.JDBC")
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        new SQLiteKeywordIndex(connection, validatedTableName)
      }.toEither.left.map(e => ProcessingError("keyword-index", s"Failed to create in-memory index: ${e.getMessage}"))
    }

  /**
   * Configuration for SQLite keyword index.
   *
   * @param path Path to database file (None for in-memory)
   * @param tableName Table name for documents
   */
  final case class Config(
    path: Option[String] = None,
    tableName: String = "documents"
  ) {
    def withPath(p: String): Config         = copy(path = Some(p))
    def inMemory: Config                    = copy(path = None)
    def withTableName(name: String): Config = copy(tableName = name)
  }

  object Config {
    val default: Config            = Config()
    val inMemory: Config           = Config(path = None)
    def file(path: String): Config = Config(path = Some(path))
  }

  /**
   * Create a keyword index from configuration.
   *
   * @param config Index configuration
   * @return New keyword index
   */
  def apply(config: Config): Result[SQLiteKeywordIndex] =
    config.path match {
      case Some(path) => apply(path, config.tableName)
      case None       => inMemory(config.tableName)
    }
}
