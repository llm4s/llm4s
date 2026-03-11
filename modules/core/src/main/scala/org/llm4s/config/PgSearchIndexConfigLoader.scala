package org.llm4s.config

import org.llm4s.error.ProcessingError
import org.llm4s.rag.permissions.SearchIndex
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

object PgSearchIndexConfigLoader {

  implicit private val pgConfigReader: PureConfigReader[SearchIndex.PgConfig] =
    PureConfigReader.forProduct8(
      "host",
      "port",
      "database",
      "user",
      "password",
      "vectorTableName",
      "keywordTableName",
      "maxPoolSize"
    )(SearchIndex.PgConfig.apply)

  def load(source: ConfigSource): Result[SearchIndex.PgConfig] =
    source
      .at("llm4s.rag.permissions.pg")
      .load[SearchIndex.PgConfig]
      .left
      .map(e => ProcessingError("pg-search-index-config", e.prettyPrint()))
}
