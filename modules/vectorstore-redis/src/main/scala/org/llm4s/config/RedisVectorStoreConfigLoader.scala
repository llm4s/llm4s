// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.vectorstore.redis.{ RedisConfig, RedisDistanceMetric, RedisVectorAlgorithm }
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.concurrent.duration.FiniteDuration

/**
 * Loads the `llm4s.vectorstore.redis` block into a [[org.llm4s.vectorstore.redis.RedisConfig]].
 *
 * `llm4s-vectorstore-redis`'s `reference.conf` binds `REDIS_HOST`, `REDIS_PORT`, `REDIS_TLS`, `REDIS_USERNAME`,
 * `REDIS_PASSWORD` and `REDIS_VECTOR_INDEX`. Every setting has a default, so with none set this is a local server
 * without authentication. Blank strings (an unset variable bound through `application.conf`) count as unset.
 *
 * {{{
 * llm4s.vectorstore.redis {
 *   host           = "redis.example.com"
 *   tls            = true
 *   password       = ${?REDIS_PASSWORD}
 *   index          = "docs"
 *   distanceMetric = COSINE
 *   dimensions     = 1536
 * }
 * }}}
 *
 * {{{
 * for
 *   config <- RedisVectorStoreConfigLoader.default()
 *   store  <- RedisVectorStore(config)
 * yield store
 * }}}
 */
object RedisVectorStoreConfigLoader {

  private val Section = "llm4s.vectorstore.redis"

  final private case class Settings(
    host: Option[String],
    port: Option[Int],
    tls: Option[Boolean],
    database: Option[Int],
    username: Option[String],
    password: Option[String],
    index: Option[String],
    keyPrefix: Option[String],
    algorithm: Option[String],
    distanceMetric: Option[String],
    dimensions: Option[Int],
    timeout: Option[FiniteDuration]
  )

  implicit private val settingsReader: PureConfigReader[Settings] =
    PureConfigReader.forProduct12(
      "host",
      "port",
      "tls",
      "database",
      "username",
      "password",
      "index",
      "keyPrefix",
      "algorithm",
      "distanceMetric",
      "dimensions",
      "timeout"
    )(Settings.apply)

  /**
   * Reads `llm4s.vectorstore.redis` from `source` and checks the result.
   *
   * @return the config, or a [[org.llm4s.error.ConfigurationError]] for an unreadable block, an unknown algorithm or
   *         distance metric, or a setting [[org.llm4s.vectorstore.redis.RedisConfig#validate]] refuses
   */
  def load(source: ConfigSource): Result[RedisConfig] = {
    val at = source.at(Section)
    val settings: Result[Settings] =
      if (at.value().isLeft) Right(Settings(None, None, None, None, None, None, None, None, None, None, None, None))
      else
        at.load[Settings]
          .left
          .map(failures => ConfigurationError(s"Failed to read $Section: ${failures.prettyPrint()}"))

    for {
      s <- settings
      algorithm <- nonEmpty(s.algorithm) match {
        case None => Right(RedisVectorAlgorithm.Hnsw)
        case Some(name) =>
          RedisVectorAlgorithm
            .fromString(name)
            .toRight(
              ConfigurationError(
                s"$Section.algorithm '$name' is not one of ${RedisVectorAlgorithm.values.map(_.wireName).mkString(", ")}"
              )
            )
      }
      metric <- nonEmpty(s.distanceMetric) match {
        case None => Right(RedisDistanceMetric.Cosine)
        case Some(name) =>
          RedisDistanceMetric
            .fromString(name)
            .toRight(
              ConfigurationError(
                s"$Section.distanceMetric '$name' is not one of " +
                  RedisDistanceMetric.values.map(_.wireName).mkString(", ")
              )
            )
      }
      config <- RedisConfig(
        host = nonEmpty(s.host).getOrElse(RedisConfig.DefaultHost),
        port = s.port.getOrElse(RedisConfig.DefaultPort),
        tls = s.tls.getOrElse(false),
        database = s.database.getOrElse(0),
        username = nonEmpty(s.username),
        password = nonEmpty(s.password),
        index = nonEmpty(s.index).getOrElse(RedisConfig.DefaultIndex),
        keyPrefix = nonEmpty(s.keyPrefix),
        algorithm = algorithm,
        distanceMetric = metric,
        dimensions = s.dimensions,
        timeout = s.timeout.getOrElse(RedisConfig.DefaultTimeout)
      ).validate.left.map(e => ConfigurationError(s"$Section: ${e.message}"))
    } yield config
  }

  /** [[load]] against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def default(): Result[RedisConfig] = load(ConfigSource.default)

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
}
