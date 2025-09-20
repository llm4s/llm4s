package org.llm4s.llmconnect.config.dbx

import com.typesafe.config.ConfigFactory

final case class PgConfig(
  host: String,
  port: Int,
  database: String,
  user: String,
  password: String,
  sslmode: String,
  schema: String
)

final case class CoreConfig(
  requirePgvector: Boolean,
  systemTable: String
)

final case class DbxConfig(pg: PgConfig, core: CoreConfig) {
  import org.llm4s.llmconnect.utils.dbx.SqlSafetyUtils
  import org.llm4s.llmconnect.model.dbx.ConfigError

  /**
   * Validates the configuration
   */
  def validate(): Either[ConfigError, Unit] =
    for {
      _ <-
        if (pg.port > 0 && pg.port < 65536) Right(())
        else Left(ConfigError(s"Invalid port: ${pg.port}. Must be between 1 and 65535"))
      _ <-
        if (pg.database.nonEmpty) Right(())
        else Left(ConfigError("Database name cannot be empty"))
      _ <-
        if (pg.host.nonEmpty) Right(())
        else Left(ConfigError("Host cannot be empty"))
      _ <-
        if (pg.user.nonEmpty) Right(())
        else Left(ConfigError("User cannot be empty"))
      _ <- SqlSafetyUtils.validateIdentifier(pg.schema).left.map(e => ConfigError(s"Invalid schema name: ${e.message}"))
      _ <- SqlSafetyUtils
        .validateIdentifier(core.systemTable)
        .left
        .map(e => ConfigError(s"Invalid system table name: ${e.message}"))
      _ <-
        if (pg.sslmode.matches("require|disable|prefer|allow|verify-ca|verify-full")) Right(())
        else
          Left(
            ConfigError(
              s"Invalid SSL mode: ${pg.sslmode}. Must be one of: require, disable, prefer, allow, verify-ca, verify-full"
            )
          )
    } yield ()
}

object DbxConfig {
  def load(): DbxConfig = {
    val c = ConfigFactory.load()
    def must(path: String): String = {
      val v = c.getString(path)
      if (v == null || v.trim.isEmpty) throw new IllegalArgumentException(s"Missing config: $path")
      v
    }

    // Helper to safely get optional string with default
    def getStringOpt(path: String, default: String): String =
      if (c.hasPath(path)) {
        val value = c.getString(path)
        if (value != null && value.trim.nonEmpty) value else default
      } else default

    val pg = PgConfig(
      host = must("dbx.pg.host"),
      port = c.getInt("dbx.pg.port"),
      database = must("dbx.pg.database"),
      user = must("dbx.pg.user"),
      password = must("dbx.pg.password"),
      sslmode = must("dbx.pg.sslmode"),
      schema = getStringOpt("dbx.pg.schema", "dbx")
    )
    val core = CoreConfig(
      requirePgvector = c.getBoolean("dbx.core.requirePgvector"),
      systemTable = c.getString("dbx.core.systemTable")
    )
    DbxConfig(pg, core)
  }
}
