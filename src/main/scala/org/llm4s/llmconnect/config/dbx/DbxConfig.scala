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

final case class DbxConfig(pg: PgConfig, core: CoreConfig)

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
