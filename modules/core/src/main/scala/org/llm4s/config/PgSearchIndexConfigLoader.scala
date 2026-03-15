package org.llm4s.config

import org.llm4s.error.ProcessingError
import org.llm4s.rag.permissions.SearchIndex
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Loader that builds a [[org.llm4s.rag.permissions.SearchIndex.PgConfig]]
 * from a PureConfig [[pureconfig.ConfigSource]].
 *
 * Reads the `llm4s.rag.permissions.pg` config tree to obtain PostgreSQL
 * connection details (host, port, database, credentials) and table names
 * used by the RAG permission-aware search index.
 *
 * Typically accessed via [[Llm4sConfig.pgSearchIndex]] rather than called
 * directly.
 */
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

  /**
   * Loads PostgreSQL search index configuration from `source`.
   *
   * @param source PureConfig source to read from; use `ConfigSource.default`
   *               in production to read environment variables and
   *               `application.conf`.
   * @return the PgConfig, or a [[org.llm4s.error.ProcessingError]] when
   *         required connection variables are missing or malformed.
   */
  def load(source: ConfigSource): Result[SearchIndex.PgConfig] =
    source
      .at("llm4s.rag.permissions.pg")
      .load[SearchIndex.PgConfig]
      .left
      .map(e => ProcessingError("pg-search-index-config", e.prettyPrint()))
}
