package org.llm4s.rag.loader

import org.llm4s.annotation.Experimental
import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.llm4s.util.PrivateSqliteFile

import java.sql.{ Connection, DriverManager, ResultSet, Types }
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{ Try, Using }

/**
 * A [[DocumentRegistry]] in a SQLite file, so that `RAG.sync` survives a restart of a single-process application:
 * the next sync re-embeds only what changed and deletes what the source dropped while the process was down. It pairs
 * with `RAGConfig.withSQLite`; for several processes sharing one index, use [[PgDocumentRegistry]].
 *
 * It holds one connection, and serialises calls on it. [[close]] closes it.
 */
@Experimental
final class SQLiteDocumentRegistry private (val dbPath: String, connection: Connection)
    extends DocumentRegistry
    with AutoCloseable {

  def getVersion(docId: String): Result[Option[DocumentVersion]] =
    attempt("get") {
      Using.resource(
        connection.prepareStatement(
          "SELECT content_hash, version_timestamp, etag FROM document_registry WHERE doc_id = ?"
        )
      ) { stmt =>
        stmt.setString(1, docId)
        Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(readVersion(rs)) else None)
      }
    }

  def register(docId: String, version: DocumentVersion, sourceId: String): Result[Unit] =
    attempt("register") {
      Using.resource(connection.prepareStatement("""
        INSERT INTO document_registry (doc_id, content_hash, version_timestamp, etag, source_id)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (doc_id) DO UPDATE SET
          content_hash = excluded.content_hash,
          version_timestamp = excluded.version_timestamp,
          etag = excluded.etag,
          source_id = excluded.source_id
      """)) { stmt =>
        stmt.setString(1, docId)
        stmt.setString(2, version.contentHash)
        version.timestamp match {
          case Some(t) => stmt.setLong(3, t)
          case None    => stmt.setNull(3, Types.BIGINT)
        }
        stmt.setString(4, version.etag.orNull)
        stmt.setString(5, sourceId)
        stmt.executeUpdate()
        ()
      }
    }

  def unregister(docId: String): Result[Unit] =
    attempt("unregister") {
      Using.resource(connection.prepareStatement("DELETE FROM document_registry WHERE doc_id = ?")) { stmt =>
        stmt.setString(1, docId)
        stmt.executeUpdate()
        ()
      }
    }

  def allDocumentIds(): Result[Set[String]] =
    attempt("list")(ids("SELECT doc_id FROM document_registry", None))

  def documentIds(sourceId: String): Result[Set[String]] =
    attempt("list")(ids("SELECT doc_id FROM document_registry WHERE source_id = ?", Some(sourceId)))

  def clear(): Result[Unit] =
    attempt("clear") {
      Using.resource(connection.createStatement())(_.executeUpdate("DELETE FROM document_registry"))
      ()
    }

  private val closed = new AtomicBoolean(false)

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) connection.synchronized(connection.close())

  private def ids(sql: String, sourceId: Option[String]): Set[String] =
    Using.resource(connection.prepareStatement(sql)) { stmt =>
      sourceId.foreach(stmt.setString(1, _))
      Using.resource(stmt.executeQuery()) { rs =>
        Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toSet
      }
    }

  private def readVersion(rs: ResultSet): DocumentVersion =
    DocumentVersion(
      contentHash = rs.getString("content_hash"),
      // getObject, not getLong + wasNull: sqlite-jdbc's wasNull does not follow a read by column label
      timestamp = Option(rs.getObject("version_timestamp")).map(_.asInstanceOf[Number].longValue),
      etag = Option(rs.getString("etag"))
    )

  // One connection: sync's parallel change detection calls the registry from several threads
  private def attempt[A](operation: String)(f: => A): Result[A] =
    Try(connection.synchronized(f)).toEither.left.map(e =>
      ProcessingError(s"sqlite-document-registry-$operation", Option(e.getMessage).getOrElse(e.toString))
    )
}

@Experimental
object SQLiteDocumentRegistry {

  /**
   * A registry in the SQLite file at `dbPath`, created with its table if it does not exist. It may be the same file
   * as the `RAGConfig.withSQLite` stores: its table is `document_registry`.
   */
  def apply(dbPath: String): Result[SQLiteDocumentRegistry] =
    open(dbPath, s"jdbc:sqlite:$dbPath")

  /** A registry in an in-memory SQLite database, gone when it is closed: for tests. */
  def inMemory(): Result[SQLiteDocumentRegistry] =
    open(":memory:", "jdbc:sqlite::memory:")

  private def open(dbPath: String, url: String): Result[SQLiteDocumentRegistry] =
    Try {
      Class.forName("org.sqlite.JDBC")
      PrivateSqliteFile.prepare(dbPath, org.llm4s.vectorstore.SQLiteVectorStore.JdbcPragmas)
      val connection = DriverManager.getConnection(url)
      val created = Try {
        Using.resource(connection.createStatement()) { stmt =>
          stmt.execute("""
            CREATE TABLE IF NOT EXISTS document_registry (
              doc_id TEXT PRIMARY KEY,
              content_hash TEXT NOT NULL,
              version_timestamp INTEGER,
              etag TEXT,
              source_id TEXT NOT NULL
            )
          """)
          stmt.execute("CREATE INDEX IF NOT EXISTS idx_document_registry_source ON document_registry (source_id)")
        }
        new SQLiteDocumentRegistry(dbPath, connection)
      }
      created.failed.foreach(_ => Try(connection.close()))
      created.get
    }.toEither.left.map(e =>
      ProcessingError("sqlite-document-registry-create", s"Failed to open $dbPath: ${e.getMessage}")
    )
}
