package org.llm4s.config

import org.llm4s.error.ProcessingError
import org.llm4s.rag.permissions.SearchIndex
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

object PgSearchIndexConfigLoader {

  private final case class PgConfigOpt(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    vectorTableName: String,
    maxPoolSize: Int,
    keywordTableName: Option[String]
  )

  implicit private val pgConfigOptReader: PureConfigReader[PgConfigOpt] =
    PureConfigReader.forProduct8(
      "host",
      "port",
      "database",
      "user",
      "password",
      "vectorTableName",
      "maxPoolSize",
      "keywordTableName"
    )(PgConfigOpt.apply)

  implicit private val pgConfigReader: PureConfigReader[SearchIndex.PgConfig] =
    pgConfigOptReader.map { opt =>
      SearchIndex.PgConfig(
        host = opt.host,
        port = opt.port,
        database = opt.database,
        user = opt.user,
        password = opt.password,
        vectorTableName = opt.vectorTableName,
        maxPoolSize = opt.maxPoolSize,
        keywordTableName = opt.keywordTableName.getOrElse("documents")
      )
    }

  def load(source: ConfigSource): Result[SearchIndex.PgConfig] =
    source
      .at("llm4s.rag.permissions.pg")
      .load[SearchIndex.PgConfig]
      .left
      .map(e => ProcessingError("pg-search-index-config", e.prettyPrint()))
}
