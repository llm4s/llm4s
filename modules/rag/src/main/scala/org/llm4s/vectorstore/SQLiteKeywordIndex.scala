package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.util.PrivateSqliteFile
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
 * - Phrase matching with balanced double quotes
 * - Prefix matching with a trailing *
 *
 * A query is free text, never FTS5 syntax: every word is an optional term, so a document need not hold all of them
 * and BM25 ranks those holding more first. Operator words and symbols (`NOT`, `NEAR`, `:`, `^`, `-`, parentheses) are
 * searched as text, and a blank query answers with nothing.
 * - "hello world" - documents containing either term, those with both first
 * - "\"hello world\"" - exact phrase match
 * - "hello*" - prefix match
 *
 * Re-indexing an id replaces its document in the full-text index too, and an index written before that was so is
 * rebuilt when it is opened.
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

    // An index written before #2994 may hold an FTS row per earlier version of a re-indexed document: rebuild the FTS
    // table from the documents when the two disagree. Idempotent, and a no-op on a consistent index. The check and the
    // rebuild run in one write transaction, its lock taken before the check (BEGIN IMMEDIATE): a write from another
    // connection between them would otherwise be indexed twice, once by its trigger and once by the rebuild.
    stmt.execute("BEGIN IMMEDIATE")
    val outcome = Try {
      val counts = stmt.executeQuery(s"SELECT (SELECT COUNT(*) FROM $ftsTableName), (SELECT COUNT(*) FROM $tableName)")
      val stale  = counts.next() && counts.getLong(1) != counts.getLong(2)
      counts.close()
      if (stale) {
        stmt.executeUpdate(s"DELETE FROM $ftsTableName")
        stmt.executeUpdate(s"INSERT INTO $ftsTableName(id, content) SELECT id, content FROM $tableName")
      }
    }.flatMap(_ => Try(stmt.execute("COMMIT")).map(_ => ()))
    outcome.failed.foreach(_ => Try(stmt.execute("ROLLBACK")))
    stmt.close()
    outcome.get
  }

  override def index(doc: KeywordDocument): Result[Unit] =
    indexBatch(Seq(doc))

  override def indexBatch(docs: Seq[KeywordDocument]): Result[Unit] =
    if (docs.isEmpty) Right(())
    else
      withTransaction {
        // An upsert, not INSERT OR REPLACE: REPLACE deletes the old row without firing the delete trigger (unless
        // recursive_triggers is on), which left the old text in the FTS table, still searchable (#2994). DO UPDATE
        // fires the update trigger, which replaces the FTS row.
        val sql =
          s"""INSERT INTO $tableName (id, content, metadata) VALUES (?, ?, ?)
             |ON CONFLICT(id) DO UPDATE SET content = excluded.content, metadata = excluded.metadata""".stripMargin
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
    // bm25() returns negative values (more negative = more relevant), so it is negated
    runSearch(query, topK, filter, s"-bm25($ftsTableName) as score", includeHighlights = false, "Search failed")

  /**
   * Runs one FTS5 match: `query` made a safe match expression ([[SQLiteKeywordIndex.matchExpression]]), the filter
   * bound as parameters. A query with no searchable term answers with nothing, as `PgKeywordIndex` does.
   */
  private def runSearch(
    query: String,
    topK: Int,
    filter: Option[MetadataFilter],
    columns: String,
    includeHighlights: Boolean,
    failure: String
  ): Result[Seq[KeywordSearchResult]] =
    SQLiteKeywordIndex.matchExpression(query) match {
      case None => Right(Seq.empty)
      case Some(expression) =>
        Try {
          val (filterSql, filterParams) = filter.fold(("1=1", Seq.empty[String]))(filterToSql)
          val sql =
            s"""
            SELECT d.id, d.content, d.metadata, $columns
            FROM $ftsTableName f
            JOIN $tableName d ON f.id = d.id
            WHERE f.content MATCH ? AND $filterSql
            ORDER BY score DESC
            LIMIT ?
          """
          val stmt = connection.prepareStatement(sql)
          stmt.setString(1, expression)
          filterParams.zipWithIndex.foreach { case (p, i) => stmt.setString(i + 2, p) }
          stmt.setInt(filterParams.size + 2, topK)
          val rs      = stmt.executeQuery()
          val results = collectResults(rs, includeHighlights)
          stmt.close()
          results
        }.toEither.left.map(e => ProcessingError("keyword-index", s"$failure: ${e.getMessage}"))
    }

  override def searchWithHighlights(
    query: String,
    topK: Int,
    snippetLength: Int,
    filter: Option[MetadataFilter]
  ): Result[Seq[KeywordSearchResult]] =
    runSearch(
      query,
      topK,
      filter,
      s"-bm25($ftsTableName) as score, snippet($ftsTableName, 1, '<b>', '</b>', '...', $snippetLength) as highlight",
      includeHighlights = true,
      "Search with highlights failed"
    )

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
      val stmt = connection.prepareStatement(s"DELETE FROM $tableName WHERE id GLOB ?")
      stmt.setString(1, SqlLikePattern.globPrefix(prefix))
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

  /**
   * Whether the metadata has an entry with the bound key that meets `condition`: `SQLiteVectorStore`'s form, so a
   * filter selects the same documents in both legs of a hybrid search. The key is a bound value, not a JSON path, so
   * a key holding `.`, a space, `:` or `/` names itself (#2995).
   */
  private def metadataEntry(condition: String): String =
    "EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid(d.metadata) THEN d.metadata ELSE '{}' END) " +
      s"WHERE key = ? AND type = 'text' AND $condition)"

  private def filterToSql(filter: MetadataFilter): (String, Seq[String]) = filter match {
    case MetadataFilter.All                => ("1=1", Seq.empty)
    case MetadataFilter.Equals(key, value) => (metadataEntry("value = ?"), Seq(key, value))
    // instr is a literal, case-sensitive substring test: LIKE would read % and _ as wildcards and fold ASCII case
    case MetadataFilter.Contains(key, substring)        => (metadataEntry("instr(value, ?) > 0"), Seq(key, substring))
    case MetadataFilter.HasKey(key)                     => (metadataEntry("1=1"), Seq(key))
    case MetadataFilter.In(_, values) if values.isEmpty => ("1=0", Seq.empty)
    case MetadataFilter.In(key, values) =>
      (metadataEntry(s"value IN (${Seq.fill(values.size)("?").mkString(",")})"), Seq(key) ++ values.toSeq)
    case MetadataFilter.And(left, right) =>
      val (l, lp) = filterToSql(left)
      val (r, rp) = filterToSql(right)
      (s"($l AND $r)", lp ++ rp)
    case MetadataFilter.Or(left, right) =>
      val (l, lp) = filterToSql(left)
      val (r, rp) = filterToSql(right)
      (s"($l OR $r)", lp ++ rp)
    case MetadataFilter.Not(inner) =>
      val (i, ip) = filterToSql(inner)
      (s"NOT ($i)", ip)
  }

  private def serializeMetadata(metadata: Map[String, String]): String =
    if (metadata.isEmpty) "{}"
    else write(metadata)

  private def deserializeMetadata(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else
      Try(read[Map[String, String]](json)).getOrElse(Map.empty)
}

object SQLiteKeywordIndex {

  /**
   * The FTS5 match expression for a free-text query, or `None` when it holds no searchable term (#2995).
   *
   *  - A balanced `"..."` is a phrase; an unpaired `"` is text.
   *  - Every other word is a term; a word ending in `*` is a prefix term.
   *  - Each term is quoted, with `"` doubled, so nothing in it is read as FTS5 syntax: `AND`, `NOT`, `NEAR`, `:`,
   *    `^`, `+`, `-` and parentheses are searched as text. A bare upper-case `AND` or `OR` is a connector and dropped.
   *  - Terms are joined with `OR`: a document need not hold every word, and BM25 ranks those holding more first.
   */
  private[vectorstore] def matchExpression(query: String): Option[String] = {
    def searchable(s: String): Boolean = s.exists(Character.isLetterOrDigit)
    def quote(s: String): String       = "\"" + s.replace("\"", "\"\"") + "\""

    val text     = Option(query).getOrElse("")
    val segments = text.split("\"", -1).toVector
    // n quotes make n + 1 segments; with an odd n the last quote is unpaired, so its two sides are one text segment
    val paired =
      if (segments.size % 2 == 0) segments.dropRight(2) :+ (segments(segments.size - 2) + " " + segments.last)
      else segments

    val terms = paired.zipWithIndex.flatMap {
      case (phrase, i) if i % 2 == 1 =>
        val trimmed = phrase.trim.replaceAll("\\s+", " ")
        if (searchable(trimmed)) Seq(quote(trimmed)) else Seq.empty
      case (words, _) =>
        words
          .split("\\s+")
          .toSeq
          .filterNot(w => w == "AND" || w == "OR")
          .flatMap { word =>
            val base = word.stripSuffix("*")
            if (!searchable(base)) Seq.empty
            else if (word.endsWith("*") && base.nonEmpty && !base.endsWith("*")) Seq(quote(base) + "*")
            else Seq(quote(word))
          }
    }
    if (terms.isEmpty) None else Some(terms.distinct.mkString(" OR "))
  }

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
        PrivateSqliteFile.prepare(path, SQLiteVectorStore.JdbcPragmas)
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
