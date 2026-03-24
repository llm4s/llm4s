package org.llm4s.samples.dashboard.providersetup

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import pureconfig.{ ConfigSource, ConfigReader as PureConfigReader }

/**
 * Typed configuration for the provider setup dashboard demo.
 *
 * This keeps all demo-specific inputs explicit at the application edge:
 * - whether debug payload logging is enabled
 * - where debug logs should be written
 */
final case class ProviderSetupDemoConfig(
  streamingEnabled: Boolean,
  debugLog: ProviderSetupDebugLogConfig,
  historyBasePath: String
)

final case class ProviderSetupDebugLogConfig(
  enabled: Boolean,
  path: String
)

object ProviderSetupDemoConfig:

  private val configPath = "llm4s.samples.dashboard.provider-setup-demo"

  given PureConfigReader[ProviderSetupDebugLogConfig] =
    PureConfigReader.forProduct2("enabled", "path")(ProviderSetupDebugLogConfig.apply)

  given PureConfigReader[ProviderSetupDemoConfig] =
    PureConfigReader.forProduct3("streaming-enabled", "debug-log", "history-base-path")(
      ProviderSetupDemoConfig.apply
    )

  def load(source: ConfigSource = ConfigSource.default): Result[ProviderSetupDemoConfig] =
    source
      .at(configPath)
      .load[ProviderSetupDemoConfig]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load provider setup demo config: $msg")
      }
      .flatMap { config =>
        val trimmedPath     = config.debugLog.path.trim
        val historyBasePath = config.historyBasePath.trim

        if trimmedPath.isEmpty then
          Left(ConfigurationError("Provider setup demo debug-log.path cannot be empty", List("debug-log.path")))
        else if historyBasePath.isEmpty then
          Left(
            ConfigurationError("Provider setup demo trimmed-history-path cannot be empty", List("trimmed-history-path"))
          )
        else
          Right(
            config.copy(
              debugLog = config.debugLog.copy(path = trimmedPath),
              historyBasePath = historyBasePath
            )
          )
      }
