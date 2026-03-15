package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.TracingMode
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Internal loader that builds [[org.llm4s.llmconnect.config.TracingSettings]]
 * from a PureConfig [[pureconfig.ConfigSource]].
 *
 * Reads `llm4s.tracing.mode` to select the tracing backend (`langfuse`,
 * `opentelemetry`, `console`, or `none`), then populates the corresponding
 * backend configuration (Langfuse keys/URL, OpenTelemetry endpoint, etc.).
 * When variables are absent, sensible defaults are applied (e.g. console
 * mode, localhost OTLP endpoint).
 *
 * This object is package-private; callers should use [[Llm4sConfig.tracing]]
 * instead.
 */
private[config] object TracingConfigLoader {

  final private case class LangfuseSection(
    url: Option[String],
    publicKey: Option[String],
    secretKey: Option[String],
    env: Option[String],
    release: Option[String],
    version: Option[String]
  )

  final private case class OpenTelemetrySection(
    serviceName: Option[String],
    endpoint: Option[String],
    headers: Option[Map[String, String]]
  )

  final private case class TracingSection(
    mode: Option[String],
    langfuse: Option[LangfuseSection],
    opentelemetry: Option[OpenTelemetrySection]
  )

  final private case class TracingRoot(tracing: Option[TracingSection])

  implicit private val langfuseSectionReader: PureConfigReader[LangfuseSection] =
    PureConfigReader.forProduct6("url", "publicKey", "secretKey", "env", "release", "version")(LangfuseSection.apply)

  implicit private val opentelemetrySectionReader: PureConfigReader[OpenTelemetrySection] =
    PureConfigReader.forProduct3("serviceName", "endpoint", "headers")(OpenTelemetrySection.apply)

  implicit private val tracingSectionReader: PureConfigReader[TracingSection] =
    PureConfigReader.forProduct3("mode", "langfuse", "opentelemetry")(TracingSection.apply)

  implicit private val tracingRootReader: PureConfigReader[TracingRoot] =
    PureConfigReader.forProduct1("tracing")(TracingRoot.apply)

  /**
   * Loads tracing settings from `source`.
   *
   * Reads the `llm4s.tracing` config tree, determines the active
   * [[org.llm4s.trace.TracingMode]], and assembles Langfuse and
   * OpenTelemetry sub-configurations with defaults for any missing values.
   *
   * @param source PureConfig source to read from; use `ConfigSource.default`
   *               in production to read environment variables and
   *               `application.conf`.
   * @return the tracing settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when the config tree cannot be parsed.
   */
  def load(source: ConfigSource): Result[TracingSettings] = {
    val rootEither = source.at("llm4s").load[TracingRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s tracing config via PureConfig: $msg")
      }
      .map(buildTracingSettings)
  }

  private def buildTracingSettings(root: TracingRoot): TracingSettings = {
    val tracing = root.tracing.getOrElse(TracingSection(None, None, None))

    val modeStr =
      tracing.mode.map(_.trim).filter(_.nonEmpty).getOrElse("console")
    val mode = TracingMode.fromString(modeStr)

    val lfSection = tracing.langfuse.getOrElse(LangfuseSection(None, None, None, None, None, None))

    val url =
      lfSection.url.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_LANGFUSE_URL)
    val publicKey = lfSection.publicKey.map(_.trim).filter(_.nonEmpty)
    val secretKey = lfSection.secretKey.map(_.trim).filter(_.nonEmpty)
    val env =
      lfSection.env.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_LANGFUSE_ENV)
    val release =
      lfSection.release.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_LANGFUSE_RELEASE)
    val version =
      lfSection.version.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_LANGFUSE_VERSION)

    val lfCfg = LangfuseConfig(url, publicKey, secretKey, env, release, version)

    // OpenTelemetry
    val otelSection = tracing.opentelemetry.getOrElse(OpenTelemetrySection(None, None, None))
    import org.llm4s.llmconnect.config.OpenTelemetryConfig

    val otelCfg = OpenTelemetryConfig(
      serviceName = otelSection.serviceName.getOrElse("llm4s-agent"),
      endpoint = otelSection.endpoint.getOrElse("http://localhost:4317"),
      headers = otelSection.headers.getOrElse(Map.empty)
    )

    TracingSettings(mode, lfCfg, otelCfg)
  }
}
