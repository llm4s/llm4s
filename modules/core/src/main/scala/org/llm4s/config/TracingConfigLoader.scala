package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.TracingMode
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Internal PureConfig-based loader for tracing configuration.
 *
 * External code should use Llm4sConfig.tracing() rather than this object directly.
 */
private[config] object TracingConfigLoader {

  // ---- Internal shapes that mirror llm4s.tracing.* config ----

  private final case class LangfuseSection(
    url: Option[String],
    publicKey: Option[String],
    secretKey: Option[String],
    env: Option[String],
    release: Option[String],
    version: Option[String]
  )

  private final case class TracingSection(
    mode: Option[String],
    langfuse: Option[LangfuseSection]
  )

  private final case class TracingRoot(tracing: Option[TracingSection])

  private implicit val langfuseSectionReader: PureConfigReader[LangfuseSection] =
    PureConfigReader.forProduct6("url", "publicKey", "secretKey", "env", "release", "version")(LangfuseSection.apply)

  private implicit val tracingSectionReader: PureConfigReader[TracingSection] =
    PureConfigReader.forProduct2("mode", "langfuse")(TracingSection.apply)

  private implicit val tracingRootReader: PureConfigReader[TracingRoot] =
    PureConfigReader.forProduct1("tracing")(TracingRoot.apply)

  // ---- Public API used by Llm4sConfig ----

  /** Load TracingSettings from the given ConfigSource under llm4s.*. */
  def load(source: ConfigSource): Result[TracingSettings] = {
    val rootEither = source.at("llm4s").load[TracingRoot]

    rootEither
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s tracing config via PureConfig: $msg")
      }
      .map(buildTracingSettings)
  }

  // ---- Internal helpers ----

  private def buildTracingSettings(root: TracingRoot): TracingSettings = {
    val tracing = root.tracing.getOrElse(TracingSection(None, None))

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
    TracingSettings(mode, lfCfg)
  }
}

