// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.deploy

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Where the deploy service listens.
 *
 * Read from `llm4s.deploy-service`, whose defaults are in this module's `reference.conf`:
 * {{{
 * llm4s.deploy-service {
 *   host = "0.0.0.0"
 *   port = 8080      # PORT in the environment overrides it
 * }
 * }}}
 */
final case class DeployServiceConfig(host: String, port: Int)

object DeployServiceConfig {

  implicit private val reader: PureConfigReader[DeployServiceConfig] =
    PureConfigReader.forProduct2("host", "port")(DeployServiceConfig.apply)

  /** The configuration of this process: `application.conf`, system properties, then `reference.conf`. */
  def load(): Result[DeployServiceConfig] = load(ConfigSource.default)

  /** Load from `source`, which must contain an `llm4s.deploy-service` block. */
  def load(source: ConfigSource): Result[DeployServiceConfig] =
    source
      .at("llm4s.deploy-service")
      .load[DeployServiceConfig]
      .left
      .map(failures => ConfigurationError(s"Invalid llm4s.deploy-service configuration: ${failures.prettyPrint()}"))
      .flatMap(validate)

  private def validate(config: DeployServiceConfig): Result[DeployServiceConfig] =
    if (config.host.trim.isEmpty)
      Left(ConfigurationError("llm4s.deploy-service.host must not be empty", List("llm4s.deploy-service.host")))
    else if (config.port < 1 || config.port > 65535)
      Left(
        ConfigurationError(
          s"llm4s.deploy-service.port must be between 1 and 65535, was ${config.port}",
          List("llm4s.deploy-service.port")
        )
      )
    else Right(config)
}
