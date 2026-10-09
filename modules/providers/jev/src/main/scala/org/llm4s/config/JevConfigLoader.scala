// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.jev.{ JevConfig, JevRetryPolicy }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.concurrent.duration.FiniteDuration

/**
 * Loads the `llm4s.jev` block into a [[org.llm4s.jev.JevConfig]].
 *
 * It lives in `org.llm4s.config`, the one package that reads configuration; `org.llm4s.jev.JevClient` takes the
 * typed config it returns and reads nothing itself.
 *
 * `llm4s-jev`'s `reference.conf` binds `TYPESAFE_BASE_URL` to `baseUrl` and `TYPESAFE_DEFAULT_MODEL` to `model`
 * (the variables TypeSafe's own SDKs read), and `TYPESAFE_API_KEY` to `llm4s.credentials.jev.apiKey`. The key is
 * `llm4s.jev.apiKey` when that is set and the shared `llm4s.credentials.jev.apiKey` otherwise, so one variable serves
 * every client of the vendor. Everything but the key has a default.
 *
 * {{{
 * llm4s.jev {
 *   model   = "jev-1.13.0"        # pin the answers; the default is the alias jev-latest
 *   timeout = 20 seconds
 *   retry { maxRetries = 3 }
 * }
 * }}}
 *
 * {{{
 * for
 *   config <- JevConfigLoader.default()
 *   client <- JevClient(config)
 * yield client
 * }}}
 */
object JevConfigLoader {

  private val Id: ProviderId = ProviderId("jev")
  private val Section        = "llm4s.jev"

  /** The variable this module's `reference.conf` binds to `llm4s.credentials.jev.apiKey`, named in the missing-key error. */
  val TYPESAFE_API_KEY: String = "TYPESAFE_API_KEY"

  final private case class RetrySettings(
    maxRetries: Option[Int],
    backoffInitial: Option[FiniteDuration],
    backoffMax: Option[FiniteDuration],
    jitter: Option[Double],
    budget: Option[FiniteDuration]
  )

  final private case class Settings(
    apiKey: Option[String],
    baseUrl: Option[String],
    model: Option[String],
    timeout: Option[FiniteDuration],
    retry: Option[RetrySettings],
    headers: Option[Map[String, String]]
  )

  implicit private val retryReader: PureConfigReader[RetrySettings] =
    PureConfigReader.forProduct5("maxRetries", "backoffInitial", "backoffMax", "jitter", "budget")(RetrySettings.apply)

  implicit private val settingsReader: PureConfigReader[Settings] =
    PureConfigReader.forProduct6("apiKey", "baseUrl", "model", "timeout", "retry", "headers")(Settings.apply)

  /**
   * Reads `llm4s.jev` from `source` and checks the result.
   *
   * @return the config, or a [[org.llm4s.error.ConfigurationError]] for an unreadable block, a key set in neither
   *         place, or a setting [[org.llm4s.jev.JevConfig#validate]] refuses
   */
  def load(source: ConfigSource): Result[JevConfig] = {
    val at = source.at(Section)
    val settings: Result[Settings] =
      if (at.value().isLeft) Right(Settings(None, None, None, None, None, None))
      else
        at.load[Settings]
          .left
          .map(failures => ConfigurationError(s"Failed to read $Section: ${failures.prettyPrint()}"))

    for {
      s        <- settings
      resolved <- SharedCredentials.read(source).resolve(s.apiKey, s"$Section.apiKey", Id)
      key <- resolved.toRight(
        ConfigurationError(
          "Missing Jev apiKey: " + SharedCredentials.missingKeyHint(Seq(TYPESAFE_API_KEY), Id, Section)
        )
      )
      config <- {
        SharedCredentials.logSource(Section, key)
        val defaults = JevRetryPolicy.default
        val retry = s.retry.fold(defaults) { r =>
          JevRetryPolicy(
            maxRetries = r.maxRetries.getOrElse(defaults.maxRetries),
            backoffInitial = r.backoffInitial.getOrElse(defaults.backoffInitial),
            backoffMax = r.backoffMax.getOrElse(defaults.backoffMax),
            jitter = r.jitter.getOrElse(defaults.jitter),
            budget = r.budget.getOrElse(defaults.budget)
          )
        }
        JevConfig(
          apiKey = key.value,
          baseUrl = nonEmpty(s.baseUrl).getOrElse(JevConfig.DefaultBaseUrl),
          model = nonEmpty(s.model).getOrElse(JevConfig.DefaultModel),
          timeout = s.timeout.getOrElse(JevConfig.DefaultTimeout),
          retry = retry,
          headers = s.headers.getOrElse(Map.empty)
        ).validate
      }
    } yield config
  }

  /** [[load]] against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def default(): Result[JevConfig] = load(ConfigSource.default)

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
}
