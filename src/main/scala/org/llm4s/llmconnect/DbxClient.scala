package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.dbx.DbxConfig
import org.llm4s.llmconnect.model.dbx._
import org.llm4s.llmconnect.provider.dbx.PGVectorProvider
import org.llm4s.llmconnect.utils.dbx.{ ConnectionPool, PoolStats }

final class DbxClient(cfg: DbxConfig) extends AutoCloseable {

  private val connectionPool: ConnectionPool = ConnectionPool.create(cfg.pg)

  def initCore(): Either[DbxError, CoreHealthReport] = {
    val pg = cfg.pg

    connectionPool.withTransaction { conn =>
      val probe = PGVectorProvider.probe(conn, pg.database, cfg.core.requirePgvector)
      probe match {
        case Left(err) => Left(err)
        case Right(version) =>
          for {
            _ <- PGVectorProvider.ensureSchema(conn, pg.schema)
            _ <- PGVectorProvider.ensureSystemTable(conn, pg.schema, cfg.core.systemTable)
            _ <- PGVectorProvider.recordVersion(conn, pg.schema, cfg.core.systemTable, version)
          } yield CoreHealthReport(
            connectionOk = true,
            schemaOk = true,
            pgvectorVersion = Some(version),
            writeOk = true,
            messages = Nil
          )
      }
    }
  }

  /**
   * Gets the current pool statistics
   */
  def getPoolStats(): PoolStats = connectionPool.getPoolStats()

  /**
   * Closes the connection pool
   */
  override def close(): Unit = connectionPool.close()
}
