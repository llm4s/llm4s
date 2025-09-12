package org.llm4s.llmconnect.provider.dbx

import org.llm4s.llmconnect.model.dbx._
import java.sql.Connection

trait DbxProvider {
  def probe(conn: Connection, expectedDb: String, requirePgvector: Boolean): Either[DbxError, String]
  def ensureSchema(conn: Connection, schema: String): Either[DbxError, Unit]
  def ensureSystemTable(conn: Connection, schema: String, table: String): Either[DbxError, Unit]
  def recordVersion(conn: Connection, schema: String, table: String, version: String): Either[DbxError, Unit]
}
