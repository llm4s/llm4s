package org.llm4s.rag.loader

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ProcessingError
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.util.{ Redaction, SqlIdentifier }
import org.llm4s.vectorstore.{ HikariDefaults, JdbcUrlCredentials, PgDataSources }

import java.sql.{ Connection, ResultSet, Types }
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import scala.util.{ Try, Using }

/**
 * A [[DocumentRegistry]] in a PostgreSQL table, so that `RAG.sync` survives a restart: the next sync finds the
 * versions the last one registered, re-embeds only what changed, and deletes what the source dropped while the
 * process was down.
 *
 * One row per document: its id, version (`content_hash`, `version_timestamp`, `etag`) and the source that owns it.
 * Several pipelines can share one table only if their document ids do not collide; give each its own table
 * otherwise.
 *
 * Build it over a pool the application already has with [[PgDocumentRegistry.fromDataSource]] - the same one a
 * `PgVectorStore` or `PgSearchIndex` uses (#2300) - or let it open its own with [[PgDocumentRegistry.apply]].
 * [[close]] closes only a pool the registry opened. Calls are blocking JDBC.
 *
 * @param dataSource the connection pool
 * @param tableName the registry table, created if it does not exist
 * @param ownsDataSource whether [[close]] closes `dataSource`
 */
@Experimental
final class PgDocumentRegistry private (
  private val dataSource: DataSource,
  val tableName: String,
  private val ownsDataSource: Boolean
) extends DocumentRegistry
    with AutoCloseable {

  def getVersion(docId: String): Result[Option[DocumentVersion]] =
    attempt("get") { conn =>
      Using.resource(
        conn.prepareStatement(s"SELECT content_hash, version_timestamp, etag FROM $tableName WHERE doc_id = ?")
      ) { stmt =>
        stmt.setString(1, docId)
        Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(readVersion(rs)) else None)
      }
    }

  def register(docId: String, version: DocumentVersion, sourceId: String): Result[Unit] =
    attempt("register") { conn =>
      Using.resource(conn.prepareStatement(s"""
        INSERT INTO $tableName (doc_id, content_hash, version_timestamp, etag, source_id, updated_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (doc_id) DO UPDATE SET
          content_hash = EXCLUDED.content_hash,
          version_timestamp = EXCLUDED.version_timestamp,
          etag = EXCLUDED.etag,
          source_id = EXCLUDED.source_id,
          updated_at = EXCLUDED.updated_at
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
    attempt("unregister") { conn =>
      Using.resource(conn.prepareStatement(s"DELETE FROM $tableName WHERE doc_id = ?")) { stmt =>
        stmt.setString(1, docId)
        stmt.executeUpdate()
        ()
      }
    }

  def allDocumentIds(): Result[Set[String]] =
    attempt("list")(conn => ids(conn, s"SELECT doc_id FROM $tableName", None))

  def documentIds(sourceId: String): Result[Set[String]] =
    attempt("list")(conn => ids(conn, s"SELECT doc_id FROM $tableName WHERE source_id = ?", Some(sourceId)))

  def clear(): Result[Unit] =
    attempt("clear") { conn =>
      Using.resource(conn.createStatement())(_.executeUpdate(s"DELETE FROM $tableName"))
      ()
    }

  override def count(): Result[Int] =
    attempt("count") { conn =>
      Using.resource(conn.createStatement()) { stmt =>
        Using.resource(stmt.executeQuery(s"SELECT count(*) FROM $tableName"))(rs => if (rs.next()) rs.getInt(1) else 0)
      }
    }

  private val closed = new AtomicBoolean(false)

  /** Closes the connection pool only if this registry opened it; a caller-owned `DataSource` is left open. */
  override def close(): Unit =
    if (ownsDataSource && closed.compareAndSet(false, true)) PgDataSources.closeOwned(dataSource)

  private def ids(conn: Connection, sql: String, sourceId: Option[String]): Set[String] =
    Using.resource(conn.prepareStatement(sql)) { stmt =>
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

  private def attempt[A](operation: String)(f: Connection => A): Result[A] =
    PgDocumentRegistry.attempt(operation)(Using.resource(dataSource.getConnection)(f))
}

@Experimental
object PgDocumentRegistry {

  /** The table a registry uses when none is named. */
  val DefaultTableName: String = "llm4s_document_registry"

  /**
   * A registry over `dataSource`, a pool the caller owns: a doobie `HikariTransactor`'s `kernel`, a Play or Slick
   * pool, pgjdbc's `PGSimpleDataSource`, or the pool a `PgVectorStore` was built over. [[PgDocumentRegistry.close]]
   * leaves it open. Creates the table if it does not exist.
   *
   * @param dataSource a `javax.sql.DataSource` the caller owns and closes
   * @param tableName the registry table; a valid SQL identifier
   */
  def fromDataSource(dataSource: DataSource, tableName: String = DefaultTableName): Result[PgDocumentRegistry] =
    if (dataSource == null) Left(ProcessingError("pg-document-registry-create", "dataSource must not be null"))
    else create(dataSource, tableName, ownsDataSource = false)

  /**
   * A registry with a connection pool of its own, which [[PgDocumentRegistry.close]] closes. Creates the table if it
   * does not exist.
   *
   * @param jdbcUrl e.g. `jdbc:postgresql://localhost:5432/mydb`
   * @param user database user
   * @param password database password
   * @param tableName the registry table; a valid SQL identifier
   * @param maxPoolSize the pool's size; a registry makes one short call per document
   */
  def apply(
    jdbcUrl: String,
    user: String,
    password: Secret,
    tableName: String = DefaultTableName,
    maxPoolSize: Int = 4
  ): Result[PgDocumentRegistry] =
    SqlIdentifier.validate(tableName, "pg-document-registry-create").flatMap { _ =>
      JdbcUrlCredentials.register(jdbcUrl)
      attempt("create") {
        val config = new HikariConfig()
        config.setJdbcUrl(jdbcUrl)
        config.setUsername(user)
        config.setPassword(password.reveal)
        config.setMaximumPoolSize(maxPoolSize)
        config.setMinimumIdle(1)
        config.setConnectionTimeout(HikariDefaults.ConnectionTimeout.toMillis)
        config.setIdleTimeout(HikariDefaults.IdleTimeout.toMillis)
        config.setMaxLifetime(HikariDefaults.MaxLifetime.toMillis)
        new HikariDataSource(config)
      }.flatMap { pool =>
        create(pool, tableName, ownsDataSource = true).left.map { e =>
          PgDataSources.closeOwned(pool)
          e
        }
      }
    }

  private def create(dataSource: DataSource, tableName: String, ownsDataSource: Boolean): Result[PgDocumentRegistry] =
    for {
      _ <- SqlIdentifier.validate(tableName, "pg-document-registry-create")
      _ <- attempt("create") {
        Using.resource(dataSource.getConnection) { conn =>
          Using.resource(conn.createStatement()) { stmt =>
            stmt.execute(s"""
              CREATE TABLE IF NOT EXISTS $tableName (
                doc_id TEXT PRIMARY KEY,
                content_hash TEXT NOT NULL,
                version_timestamp BIGINT,
                etag TEXT,
                source_id TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
              )
            """)
            stmt.execute(s"CREATE INDEX IF NOT EXISTS idx_${tableName}_source ON $tableName (source_id)")
          }
        }
      }
    } yield new PgDocumentRegistry(dataSource, tableName, ownsDataSource)

  private[loader] def attempt[A](operation: String)(f: => A): Result[A] =
    Try(f).toEither.left.map(e =>
      ProcessingError(
        s"pg-document-registry-$operation",
        Redaction.scrubKnown(Option(e.getMessage).getOrElse(e.toString))
      )
    )
}
