package org.llm4s.llmconnect.provider.dbx

import org.llm4s.llmconnect.model.dbx._
import org.llm4s.llmconnect.utils.dbx.SqlSafetyUtils
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
        val count = queryInt(conn, s"select count(*) from $qualifiedName")
        if (count == 0) {
          // Use parameterized query for the version value
          val sql = s"insert into $qualifiedName(pgvector_version) values (?)"
          val ps  = conn.prepareStatement(sql)
          try {
            ps.setString(1, version)
            ps.execute()
            Right(())
          } catch {
            case e: Throwable => Left(WriteError(e.getMessage))
          } finally ps.close()
        } else Right(())
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
