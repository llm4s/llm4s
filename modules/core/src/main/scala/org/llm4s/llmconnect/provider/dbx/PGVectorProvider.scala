package org.llm4s.llmconnect.provider.dbx

import org.llm4s.llmconnect.model.dbx._
import org.llm4s.llmconnect.utils.dbx.SqlSafetyUtils
import org.llm4s.core.safety.UsingOps._
import java.sql.Connection
import scala.util.Try

object PGVectorProvider extends DbxProvider {
  override def probe(conn: Connection, expectedDb: String, requirePgvector: Boolean): Either[DbxError, String] =
    queryString(conn, "select current_database()") match {
      case Left(err) => Left(err)
      case Right(dbName) =>
        if (dbName != expectedDb) {
          Left(ConnectionError(s"Connected to '$dbName' not expected '$expectedDb'"))
        } else {
          queryStringOpt(conn, "select extversion from pg_extension where extname = 'vector'") match {
            case Left(err) =>
              // Permission errors on pg_extension are common - treat as missing
              if (err.isInstanceOf[PermissionError]) {
                if (requirePgvector) {
                  Left(PgvectorMissing("Cannot verify pgvector extension (insufficient privileges)"))
                } else {
                  Right("unknown")
                }
              } else {
                Left(err)
              }
            case Right(versionOpt) =>
              (requirePgvector, versionOpt) match {
                case (true, None)  => Left(PgvectorMissing("pgvector extension not enabled on this database"))
                case (_, Some(v))  => Right(v)
                case (false, None) => Right("unknown")
              }
          }
        }
    }

  override def ensureSchema(conn: Connection, schema: String): Either[DbxError, Unit] =
    SqlSafetyUtils.validateIdentifier(schema) match {
      case Left(err) => Left(err)
      case Right(_) =>
        val quotedSchema = SqlSafetyUtils.quoteIdentifier(schema)
        safeExec(conn, s"create schema if not exists $quotedSchema")
    }

  override def ensureSystemTable(conn: Connection, schema: String, table: String): Either[DbxError, Unit] =
    for {
      _             <- SqlSafetyUtils.validateIdentifiers(schema, table)
      qualifiedName <- SqlSafetyUtils.qualifiedTableName(schema, table)
      sql = s"""
               |create table if not exists $qualifiedName (
               | pgvector_version text not null,
               | created_at timestamptz not null default now()
               |)
               |""".stripMargin
      result <- safeExec(conn, sql)
    } yield result

  override def recordVersion(
    conn: Connection,
    schema: String,
    table: String,
    version: String
  ): Either[DbxError, Unit] =
    SqlSafetyUtils.qualifiedTableName(schema, table) match {
      case Left(err) => Left(err)
      case Right(qualifiedName) =>
        queryInt(conn, s"select count(*) from $qualifiedName") match {
          case Left(err) => Left(err)
          case Right(count) =>
            if (count == 0) {
              // Use parameterized query for the version value
              val sql = s"insert into $qualifiedName(pgvector_version) values (?)"
              withDbOperation("Record version") {
                using(conn.prepareStatement(sql)) { ps =>
                  ps.setString(1, version)
                  ps.execute()
                  ()
                }
              }
            } else Right(())
        }
    }

  // ---- helpers ----

  /**
   * Converts SQLException to appropriate DbxError based on the error message and context
   */
  private def handleSQLException(e: java.sql.SQLException, operation: String): DbxError = {
    val message = Option(e.getMessage).getOrElse("Unknown SQL error")

    // Check for common error patterns
    if (
      message.toLowerCase.contains("permission") || message.toLowerCase.contains("denied") ||
      message.toLowerCase.contains("privilege")
    ) {
      PermissionError(s"$operation failed - permission denied: $message")
    } else if (
      message.toLowerCase.contains("connection") || message.toLowerCase.contains("timeout") ||
      message.toLowerCase.contains("network")
    ) {
      ConnectionError(s"$operation failed - connection error: $message")
    } else if (message.toLowerCase.contains("duplicate") || message.toLowerCase.contains("unique")) {
      WriteError(s"$operation failed - duplicate entry: $message")
    } else if (message.toLowerCase.contains("not found") || message.toLowerCase.contains("does not exist")) {
      SchemaError(s"$operation failed - schema error: $message")
    } else {
      // Default to ConnectionError for unclassified SQL errors
      ConnectionError(s"$operation failed: $message")
    }
  }

  /**
   * Wraps a database operation and handles exceptions consistently
   */
  private def withDbOperation[T](operation: String)(block: => T): Either[DbxError, T] =
    Try(block).toEither.left.map {
      case e: java.sql.SQLException => handleSQLException(e, operation)
      case e: Throwable             => ConnectionError(s"$operation failed with unexpected error: ${e.getMessage}")
    }

  private def safeExec(conn: Connection, sql: String): Either[DbxError, Unit] =
    withDbOperation("Execute SQL") {
      using(conn.prepareStatement(sql)) { ps =>
        ps.execute()
        ()
      }
    }

  private def queryString(conn: Connection, sql: String): Either[DbxError, String] =
    withDbOperation("Query string") {
      using(conn.prepareStatement(sql)) { ps =>
        using(ps.executeQuery()) { rs =>
          if (rs.next()) {
            rs.getString(1)
          } else {
            throw new java.sql.SQLException(s"No results from query")
          }
        }
      }
    }

  private def queryStringOpt(conn: Connection, sql: String): Either[DbxError, Option[String]] =
    withDbOperation("Query optional string") {
      using(conn.prepareStatement(sql)) { ps =>
        using(ps.executeQuery())(rs => if (rs.next()) Option(rs.getString(1)) else None)
      }
    }

  private def queryInt(conn: Connection, sql: String): Either[DbxError, Int] =
    withDbOperation("Query integer") {
      using(conn.prepareStatement(sql)) { ps =>
        using(ps.executeQuery()) { rs =>
          if (rs.next()) {
            rs.getInt(1)
          } else {
            throw new java.sql.SQLException(s"No results from query")
          }
        }
      }
    }
}
