// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.vectorstore.opensearch.{ OpenSearchAuth, OpenSearchConfig, OpenSearchEngine, OpenSearchSpaceType }
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.concurrent.duration.FiniteDuration

/**
 * Loads the `llm4s.vectorstore.opensearch` block into an [[org.llm4s.vectorstore.opensearch.OpenSearchConfig]].
 *
 * `llm4s-vectorstore-elasticsearch`'s `reference.conf` binds `OPENSEARCH_URL`, `OPENSEARCH_INDEX`,
 * `OPENSEARCH_USERNAME`, `OPENSEARCH_PASSWORD` and `OPENSEARCH_TOKEN`. Every setting has a default, so with none set
 * this is a local cluster with the security plugin disabled. Blank values (an unset variable bound through
 * `application.conf`) count as unset.
 *
 * {{{
 * llm4s.vectorstore.opensearch {
 *   baseUrl   = "https://search.example.com:9200"
 *   index     = "docs"
 *   username  = llm4s
 *   password  = ${?OPENSEARCH_PASSWORD}
 *   spaceType = innerproduct
 *   engine    = faiss
 * }
 * }}}
 *
 * {{{
 * for
 *   config <- OpenSearchVectorStoreConfigLoader.default()
 *   store  <- OpenSearchVectorStore(config)
 * yield store
 * }}}
 */
object OpenSearchVectorStoreConfigLoader {

  private val Section = "llm4s.vectorstore.opensearch"

  final private case class Settings(
    baseUrl: Option[String],
    index: Option[String],
    username: Option[String],
    password: Option[String],
    token: Option[String],
    spaceType: Option[String],
    engine: Option[String],
    dimensions: Option[Int],
    m: Option[Int],
    efConstruction: Option[Int],
    numCandidates: Option[Int],
    refreshOnWrite: Option[Boolean],
    timeout: Option[FiniteDuration]
  )

  implicit private val settingsReader: PureConfigReader[Settings] =
    PureConfigReader.forProduct13(
      "baseUrl",
      "index",
      "username",
      "password",
      "token",
      "spaceType",
      "engine",
      "dimensions",
      "m",
      "efConstruction",
      "numCandidates",
      "refreshOnWrite",
      "timeout"
    )(Settings.apply)

  private val Empty = Settings(None, None, None, None, None, None, None, None, None, None, None, None, None)

  /**
   * Reads `llm4s.vectorstore.opensearch` from `source` and checks the result.
   *
   * @return the config, or a [[org.llm4s.error.ConfigurationError]] for an unreadable block, an unknown space type or
   *         engine, both a token and a username set, a username without a password (or the reverse), or a setting
   *         [[org.llm4s.vectorstore.opensearch.OpenSearchConfig#validate]] refuses
   */
  def load(source: ConfigSource): Result[OpenSearchConfig] = {
    val at = source.at(Section)
    val settings: Result[Settings] =
      if (at.value().isLeft) Right(Empty)
      else
        at.load[Settings]
          .left
          .map(failures => ConfigurationError(s"Failed to read $Section: ${failures.prettyPrint()}"))

    for {
      s <- settings
      spaceType <- named("spaceType", s.spaceType, OpenSearchSpaceType.CosineSimil)(
        OpenSearchSpaceType.fromString,
        OpenSearchSpaceType.values.toSeq.map(_.wireName)
      )
      engine <- named("engine", s.engine, OpenSearchEngine.Lucene)(
        OpenSearchEngine.fromString,
        OpenSearchEngine.values.toSeq.map(_.wireName)
      )
      auth <- (nonEmpty(s.token), nonEmpty(s.username), nonEmpty(s.password)) match {
        case (Some(_), Some(_), _) =>
          Left(ConfigurationError(s"$Section sets both token and username: choose one way to authenticate"))
        case (Some(token), None, _)      => Right(OpenSearchAuth.BearerToken(token))
        case (None, Some(user), Some(p)) => Right(OpenSearchAuth.Basic(user, p))
        case (None, Some(_), None) =>
          Left(ConfigurationError(s"$Section.username is set without a password", List(s"$Section.password")))
        case (None, None, Some(_)) =>
          Left(ConfigurationError(s"$Section.password is set without a username", List(s"$Section.username")))
        case (None, None, None) => Right(OpenSearchAuth.NoAuth)
      }
      config <- OpenSearchConfig(
        baseUrl = nonEmpty(s.baseUrl).getOrElse(OpenSearchConfig.DefaultBaseUrl),
        index = nonEmpty(s.index).getOrElse(OpenSearchConfig.DefaultIndex),
        auth = auth,
        spaceType = spaceType,
        engine = engine,
        dimensions = s.dimensions,
        m = s.m,
        efConstruction = s.efConstruction,
        numCandidates = s.numCandidates,
        refreshOnWrite = s.refreshOnWrite.getOrElse(true),
        timeout = s.timeout.getOrElse(OpenSearchConfig.DefaultTimeout)
      ).validate.left.map(e => ConfigurationError(s"$Section: ${e.message}"))
    } yield config
  }

  /** [[load]] against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def default(): Result[OpenSearchConfig] = load(ConfigSource.default)

  private def named[A](key: String, value: Option[String], default: A)(
    parse: String => Option[A],
    choices: Seq[String]
  ): Result[A] =
    nonEmpty(value) match {
      case None => Right(default)
      case Some(name) =>
        parse(name).toRight(ConfigurationError(s"$Section.$key '$name' is not one of ${choices.mkString(", ")}"))
    }

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
}
