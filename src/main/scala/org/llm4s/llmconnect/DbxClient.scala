package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.dbx.DbxConfig
import org.llm4s.llmconnect.model.dbx._
import org.llm4s.llmconnect.provider.dbx.PGVectorProvider
import org.llm4s.llmconnect.utils.dbx.SqlUtils

final class DbxClient(cfg: DbxConfig) {
  def initCore(): Either[DbxError, CoreHealthReport] = {
    val pg = cfg.pg
    val conn =
      try SqlUtils.connect(pg.host, pg.port, pg.database, pg.user, pg.password, pg.sslmode)
      catch {
        case e: Throwable => return Left(ConnectionError(e.getMessage))
      }
    try {
      val probe = PGVectorProvider.probe(conn, pg.database, cfg.core.requirePgvector)
      probe match {
        case Left(err) => Left(err)
        case Right(version) =>
          PGVectorProvider
            .ensureSchema(conn, pg.schema)
            .flatMap(_ => PGVectorProvider.ensureSystemTable(conn, pg.schema, cfg.core.systemTable))
            .flatMap(_ => PGVectorProvider.recordVersion(conn, pg.schema, cfg.core.systemTable, version))
            .map { _ =>
              CoreHealthReport(
                connectionOk = true,
                schemaOk = true,
                pgvectorVersion = Some(version),
                writeOk = true,
                messages = Nil
              )
            }
      }
    } finally conn.close()
  }
}
