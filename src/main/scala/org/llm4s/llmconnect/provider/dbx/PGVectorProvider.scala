package org.llm4s.llmconnect.provider.dbx

import org.llm4s.llmconnect.model.dbx._
import java.sql.Connection

object PGVectorProvider extends DbxProvider {
  override def probe(conn: Connection, expectedDb: String, requirePgvector: Boolean): Either[DbxError, String] = {
    val dbName = queryString(conn, "select current_database()")
    if (dbName != expectedDb) return Left(ConnectionError(s"Connected to '$dbName' not expected '$expectedDb'"))

    val versionOpt = queryStringOpt(conn, "select extversion from pg_extension where extname = 'vector'")
    (requirePgvector, versionOpt) match {
      case (true, None)  => Left(PgvectorMissing("pgvector extension not enabled on this database"))
      case (_, Some(v))  => Right(v)
      case (false, None) => Right("unknown")
    }
  }

  override def ensureSchema(conn: Connection, schema: String): Either[DbxError, Unit] =
    safeExec(conn, s"create schema if not exists \"$schema\"")

  override def ensureSystemTable(conn: Connection, schema: String, table: String): Either[DbxError, Unit] = {
    val sql = s"""
                 |create table if not exists "%s".%s (
                 | pgvector_version text not null,
                 | created_at timestamptz not null default now()
                 |)
                 |""".stripMargin.format(schema, table)
    safeExec(conn, sql)
  }

  override def recordVersion(
    conn: Connection,
    schema: String,
    table: String,
    version: String
  ): Either[DbxError, Unit] = {
    val count = queryInt(conn, s"select count(*) from \"$schema\".$table")
    if (count == 0) safeExec(conn, s"insert into \"$schema\".$table(pgvector_version) values ('$version')")
    else Right(())
  }

  // ---- helpers ----
  private def safeExec(conn: Connection, sql: String): Either[DbxError, Unit] = {
    val ps = conn.prepareStatement(sql)
    try { ps.execute(); Right(()) }
    catch { case e: Throwable => Left(PermissionError(e.getMessage)) }
    finally ps.close()
  }

  private def queryString(conn: Connection, sql: String): String = {
    val ps = conn.prepareStatement(sql)
    try { val rs = ps.executeQuery(); rs.next(); val s = rs.getString(1); rs.close(); s }
    finally ps.close()
  }

  private def queryStringOpt(conn: Connection, sql: String): Option[String] = {
    val ps = conn.prepareStatement(sql)
    try { val rs = ps.executeQuery(); val out = if (rs.next()) Option(rs.getString(1)) else None; rs.close(); out }
    finally ps.close()
  }

  private def queryInt(conn: Connection, sql: String): Int = {
    val ps = conn.prepareStatement(sql)
    try { val rs = ps.executeQuery(); rs.next(); val i = rs.getInt(1); rs.close(); i }
    finally ps.close()
  }
}
