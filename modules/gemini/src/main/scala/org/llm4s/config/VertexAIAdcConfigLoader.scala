// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.provider.GcloudAdcLocation
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Loads where gcloud keeps its Application Default Credentials file, at the configuration edge (#1939).
 *
 * Reads the `llm4s.vertexai.adc` block (`appData`, `cloudSdkConfig`; `llm4s-gemini`'s `reference.conf`
 * binds them to `${?APPDATA}` and `${?CLOUDSDK_CONFIG}`) and the `os.name` and `user.home` system
 * properties, and resolves them with [[org.llm4s.llmconnect.provider.GcloudAdcLocation.wellKnownPath]].
 * The result is injected into the client as `VertexAIConfig.wellKnownCredentialPath`; the
 * authentication code reads no configuration itself.
 */
@Experimental
object VertexAIAdcConfigLoader {

  private val ConfigPath = "llm4s.vertexai.adc"

  implicit private val settingsReader: PureConfigReader[GcloudAdcLocation.Settings] =
    PureConfigReader.forProduct2("appData", "cloudSdkConfig")(GcloudAdcLocation.Settings.apply)

  /** The well-known ADC file path for this JVM's OS and home directory and the application's configuration. */
  def load(): Result[String] = load(ConfigSource.default)

  private[llm4s] def load(source: ConfigSource): Result[String] =
    loadSettings(source).map(forThisPlatform)

  private[llm4s] def loadSettings(source: ConfigSource): Result[GcloudAdcLocation.Settings] =
    source.at(ConfigPath).load[GcloudAdcLocation.Settings].left.map { failures =>
      ConfigurationError(s"Failed to load $ConfigPath: ${failures.toList.map(_.description).mkString("; ")}")
    }

  private[llm4s] def forThisPlatform(settings: GcloudAdcLocation.Settings): String =
    GcloudAdcLocation.wellKnownPath(
      Option(System.getProperty("os.name")).getOrElse(""),
      Option(System.getProperty("user.home")).getOrElse(""),
      settings.appData,
      settings.cloudSdkConfig
    )
}
