package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.{ ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import java.nio.file.Paths

/**
 * Loader for provider exchange logging configuration.
 *
 * Reads configuration from `llm4s.exchangeLogging` and constructs the runtime
 * [[org.llm4s.llmconnect.ProviderExchangeLogging]] setting.
 *
 * Supported keys:
 *  - `llm4s.exchangeLogging.enabled`: enable or disable exchange capture
 *  - `llm4s.exchangeLogging.dir`: output directory for per-run JSONL files when enabled
 */
private[config] object ProviderExchangeLoggingConfigLoader:

  final private case class ExchangeLoggingSection(
    enabled: Option[Boolean],
    dir: Option[String]
  )

  private given exchangeLoggingSectionReader: PureConfigReader[ExchangeLoggingSection] =
    PureConfigReader.forProduct2("enabled", "dir")(ExchangeLoggingSection.apply)

  def load(source: ConfigSource = ConfigSource.default): Result[ProviderExchangeLogging] =
    source.at("llm4s.exchangeLogging").load[ExchangeLoggingSection] match
      case Right(section) =>
        buildExchangeLogging(section)
      case Left(failures) if failures.toList.exists(_.description.contains("Key not found")) =>
        Right(ProviderExchangeLogging.Disabled)
      case Left(failures) =>
        val msg = failures.toList.map(_.description).mkString("; ")
        Left(ConfigurationError(s"Failed to load llm4s exchange logging config via PureConfig: $msg"))

  private def buildExchangeLogging(section: ExchangeLoggingSection): Result[ProviderExchangeLogging] =
    if !section.enabled.getOrElse(false) then Right(ProviderExchangeLogging.Disabled)
    else
      section.dir
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(ConfigurationError("Provider exchange logging is enabled but llm4s.exchangeLogging.dir is missing"))
        .flatMap(dir => ProviderExchangeSink.createRunScopedJsonl(Paths.get(dir)).map(ProviderExchangeLogging.enabled))
