package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.dbx.DbxConfig
import org.llm4s.llmconnect.model.dbx._
import org.llm4s.llmconnect.provider.dbx.PGVectorProvider
import org.llm4s.llmconnect.utils.dbx.{ ConnectionPool, PoolStats }
import org.slf4j.LoggerFactory

final class DbxClient(cfg: DbxConfig) extends AutoCloseable {

  private val logger = LoggerFactory.getLogger(getClass)

  // Validate configuration on initialization
  cfg.validate() match {
    case Left(error) =>
      logger.error(s"Invalid DBx configuration: ${error.message}")
      throw new IllegalArgumentException(s"Invalid DBx configuration: ${error.message}")
    case Right(_) =>
      logger.debug("DBx configuration validated successfully")
  }

  private val connectionPool: ConnectionPool = ConnectionPool.create(cfg.pg)

  def initCore(): Either[DbxError, CoreHealthReport] = {
    val pg = cfg.pg
    logger.info(s"Initializing DBx Core with schema: ${pg.schema}")

    connectionPool.withTransaction { conn =>
      val probe = PGVectorProvider.probe(conn, pg.database, cfg.core.requirePgvector)
      probe match {
        case Left(err) =>
          logger.error(s"Failed to probe database: ${err.message}")
          Left(err)
        case Right(version) =>
          logger.info(s"Database probe successful, pgvector version: $version")
          for {
            _ <- PGVectorProvider.ensureSchema(conn, pg.schema).map { _ =>
              logger.debug(s"Schema ${pg.schema} ensured")
            }
            _ <- PGVectorProvider.ensureSystemTable(conn, pg.schema, cfg.core.systemTable).map { _ =>
              logger.debug(s"System table ${cfg.core.systemTable} ensured")
            }
            _ <- PGVectorProvider.recordVersion(conn, pg.schema, cfg.core.systemTable, version).map { _ =>
              logger.debug(s"Version $version recorded")
            }
          } yield {
            logger.info("DBx Core initialization completed successfully")
            CoreHealthReport(
              connectionOk = true,
              schemaOk = true,
              pgvectorVersion = Some(version),
              writeOk = true,
              messages = Nil
            )
          }
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
