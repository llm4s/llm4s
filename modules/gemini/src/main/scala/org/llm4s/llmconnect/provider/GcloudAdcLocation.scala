package org.llm4s.llmconnect.provider

/**
 * The well-known file `gcloud auth application-default login` writes, found the way Google's
 * client libraries find it (#1939).
 *
 *  - `CLOUDSDK_CONFIG`, when set and non-empty, names the gcloud config directory on every OS,
 *    Windows included (google-auth-library-java checks it before the OS default);
 *  - otherwise on Windows the directory is `%APPDATA%\gcloud`;
 *  - otherwise `~/.config/gcloud`.
 *
 * This object is pure and reads no configuration. The environment reaches it through
 * [[org.llm4s.config.VertexAIAdcConfigLoader]], which reads `llm4s-gemini`'s `reference.conf`
 * (`llm4s.vertexai.adc.appData` bound to `${?APPDATA}`, `cloudSdkConfig` to `${?CLOUDSDK_CONFIG}`)
 * together with the `os.name` and `user.home` system properties, and hands the resolved path to
 * the client through `VertexAIConfig.wellKnownCredentialPath`. All three cases are testable on any OS.
 */
private[llm4s] object GcloudAdcLocation {

  val FileName = "application_default_credentials.json"

  /** The optional `llm4s.vertexai.adc` bindings: an absent or blank variable is `None`. */
  final case class Settings(appData: Option[String], cloudSdkConfig: Option[String])

  object Settings {
    val empty: Settings = Settings(None, None)
  }

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
}
