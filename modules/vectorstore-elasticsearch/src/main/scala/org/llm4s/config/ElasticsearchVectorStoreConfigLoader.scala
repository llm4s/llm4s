// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.vectorstore.elasticsearch.{ ElasticsearchAuth, ElasticsearchConfig, ElasticsearchSimilarity }
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.concurrent.duration.FiniteDuration

/**
 * Loads the `llm4s.vectorstore.elasticsearch` block into an
 * [[org.llm4s.vectorstore.elasticsearch.ElasticsearchConfig]].
 *
 * `llm4s-vectorstore-elasticsearch`'s `reference.conf` binds `ELASTICSEARCH_URL`, `ELASTICSEARCH_INDEX`,
 * `ELASTICSEARCH_API_KEY`, `ELASTICSEARCH_USERNAME` and `ELASTICSEARCH_PASSWORD`. Every setting has a default, so
 * with none set this is a local cluster with security disabled. Blank values (an unset variable bound through
 * `application.conf`) count as unset.
 *
 * {{{
 * llm4s.vectorstore.elasticsearch {
 *   baseUrl    = "https://es.example.com:9200"
 *   index      = "docs"
 *   apiKey     = ${?ELASTICSEARCH_API_KEY}
 *   similarity = dot_product
 *   dimensions = 1536
 * }
 * }}}
 *
 * {{{
 * for
 *   config <- ElasticsearchVectorStoreConfigLoader.default()
 *   store  <- ElasticsearchVectorStore(config)
 * yield store
 * }}}
 */
object ElasticsearchVectorStoreConfigLoader {

  private val Section = "llm4s.vectorstore.elasticsearch"

  final private case class Settings(
    baseUrl: Option[String],
    index: Option[String],
    apiKey: Option[String],
    username: Option[String],
    password: Option[String],
    similarity: Option[String],
    dimensions: Option[Int],
    numCandidates: Option[Int],
    refreshOnWrite: Option[Boolean],
    timeout: Option[FiniteDuration]
  )

  implicit private val settingsReader: PureConfigReader[Settings] =
    PureConfigReader.forProduct10(
      "baseUrl",
      "index",
      "apiKey",
      "username",
      "password",
      "similarity",
      "dimensions",
      "numCandidates",
      "refreshOnWrite",
      "timeout"
    )(Settings.apply)

  /**
   * Reads `llm4s.vectorstore.elasticsearch` from `source` and checks the result.
   *
   * @return the config, or a [[org.llm4s.error.ConfigurationError]] for an unreadable block, an unknown similarity,
   *         both an API key and a username set, a username without a password (or the reverse), or a setting
   *         [[org.llm4s.vectorstore.elasticsearch.ElasticsearchConfig#validate]] refuses
   */
  def load(source: ConfigSource): Result[ElasticsearchConfig] = {
    val at = source.at(Section)
    val settings: Result[Settings] =
      if (at.value().isLeft) Right(Settings(None, None, None, None, None, None, None, None, None, None))
      else
        at.load[Settings]
          .left
          .map(failures => ConfigurationError(s"Failed to read $Section: ${failures.prettyPrint()}"))

    for {
      s <- settings
      similarity <- nonEmpty(s.similarity) match {
        case None => Right(ElasticsearchSimilarity.Cosine)
        case Some(name) =>
          ElasticsearchSimilarity
            .fromString(name)
            .toRight(
              ConfigurationError(
                s"$Section.similarity '$name' is not one of " +
                  ElasticsearchSimilarity.values.map(_.wireName).mkString(", ")
              )
            )
      }
      auth <- (nonEmpty(s.apiKey), nonEmpty(s.username), nonEmpty(s.password)) match {
        case (Some(_), Some(_), _) =>
          Left(ConfigurationError(s"$Section sets both apiKey and username: choose one way to authenticate"))
        case (Some(key), None, _)        => Right(ElasticsearchAuth.ApiKey(key))
        case (None, Some(user), Some(p)) => Right(ElasticsearchAuth.Basic(user, p))
        case (None, Some(_), None) =>
          Left(ConfigurationError(s"$Section.username is set without a password", List(s"$Section.password")))
        case (None, None, Some(_)) =>
          Left(ConfigurationError(s"$Section.password is set without a username", List(s"$Section.username")))
        case (None, None, None) => Right(ElasticsearchAuth.NoAuth)
      }
      config <- ElasticsearchConfig(
        baseUrl = nonEmpty(s.baseUrl).getOrElse(ElasticsearchConfig.DefaultBaseUrl),
        index = nonEmpty(s.index).getOrElse(ElasticsearchConfig.DefaultIndex),
        auth = auth,
        similarity = similarity,
        dimensions = s.dimensions,
        numCandidates = s.numCandidates,
        refreshOnWrite = s.refreshOnWrite.getOrElse(true),
        timeout = s.timeout.getOrElse(ElasticsearchConfig.DefaultTimeout)
      ).validate.left.map(e => ConfigurationError(s"$Section: ${e.message}"))
    } yield config
  }

  /** [[load]] against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def default(): Result[ElasticsearchConfig] = load(ConfigSource.default)

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
}
