// scalafix:off DisableSyntax.NoPureConfigDefault
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
 * Use [[default]] to read the ambient environment; `Llm4sConfig` cannot expose this
 * because `SearchIndex` lives in `llm4s-rag` and `Llm4sConfig` lives in `llm4s-core`.
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

  /**
   * Loads PostgreSQL search index configuration from the current environment
   * (environment variables plus `application.conf`).
   *
   * Replaces `Llm4sConfig.pgSearchIndex()`, which could not follow `SearchIndex`
   * out of `llm4s-core`.
   *
   * @return the PgConfig, or a [[org.llm4s.error.ProcessingError]] when required
   *         connection variables are missing or malformed.
   */
  def default(): Result[SearchIndex.PgConfig] =
    load(ConfigSource.default)
}
