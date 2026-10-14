// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * The well-known file `gcloud auth application-default login` writes, found the way Google's
 * client libraries find it (#1939).
 *
 *  - `CLOUDSDK_CONFIG`, when set and non-empty, names the gcloud config directory on every OS,
 *    Windows included (google-auth-library-java checks it before the OS default);
 *  - otherwise on Windows the directory is `%APPDATA%\gcloud`;
 *  - otherwise `~/.config/gcloud`.
 *
 * The environment reaches this object through `llm4s-gemini`'s `reference.conf`, which binds
 * `llm4s.vertexai.adc.appData` to `${?APPDATA}` and `llm4s.vertexai.adc.cloudSdkConfig` to
 * `${?CLOUDSDK_CONFIG}`; the OS and home directory are the `os.name` and `user.home` system
 * properties. [[wellKnownPath]] is pure, so all three cases are testable on any OS.
 */
private[llm4s] object GcloudAdcLocation {

  val FileName = "application_default_credentials.json"

  /** The optional `llm4s.vertexai.adc` bindings: an absent or blank variable is `None`. */
  final case class Settings(appData: Option[String], cloudSdkConfig: Option[String])

  object Settings {
    val empty: Settings = Settings(None, None)
  }

  private val ConfigPath = "llm4s.vertexai.adc"

  implicit private val settingsReader: PureConfigReader[Settings] =
    PureConfigReader.forProduct2("appData", "cloudSdkConfig")(Settings.apply)

  def isWindows(osName: String): Boolean = osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows")

  /**
   * Where gcloud keeps its Application Default Credentials file.
   *
   * Separators follow `osName`, so the result is the same on every host.
   *
   * @param osName         the `os.name` system property
   * @param home           the `user.home` system property
   * @param appData        `%APPDATA%`, if set
   * @param cloudSdkConfig `CLOUDSDK_CONFIG`, if set
   */
  def wellKnownPath(
    osName: String,
    home: String,
    appData: Option[String],
    cloudSdkConfig: Option[String]
  ): String = {
    val windows = isWindows(osName)
    val sep     = if (windows) "\\" else "/"
    def join(dir: String, rest: String*): String =
      (dir.stripSuffix("/").stripSuffix("\\") +: rest).mkString(sep)
    def set(v: Option[String]): Option[String] = v.map(_.trim).filter(_.nonEmpty)

    val dir = set(cloudSdkConfig).getOrElse {
      if (windows) join(set(appData).getOrElse(join(home, "AppData", "Roaming")), "gcloud")
      else join(home, ".config", "gcloud")
    }
    join(dir, FileName)
  }

  /** Reads the `llm4s.vertexai.adc` block of `source`. */
  def loadSettings(source: ConfigSource): Result[Settings] =
    source.at(ConfigPath).load[Settings].left.map { failures =>
      ConfigurationError(s"Failed to load $ConfigPath: ${failures.toList.map(_.description).mkString("; ")}")
    }

  /** [[wellKnownPath]] for this JVM's OS and home directory and `settings`. */
  def forThisPlatform(settings: Settings): String =
    wellKnownPath(
      Option(System.getProperty("os.name")).getOrElse(""),
      Option(System.getProperty("user.home")).getOrElse(""),
      settings.appData,
      settings.cloudSdkConfig
    )

  /** The path for the current environment (system properties, `application.conf`, every `reference.conf`). */
  def systemDefault(): String =
    forThisPlatform(loadSettings(ConfigSource.default).getOrElse(Settings.empty))
}
