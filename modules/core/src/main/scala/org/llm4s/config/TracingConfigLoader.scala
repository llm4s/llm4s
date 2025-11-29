package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.TracingMode
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

private[config] object TracingConfigLoader {

  final private case class LangfuseSection(
    url: Option[String],
    publicKey: Option[String],
    secretKey: Option[String],
    env: Option[String],
    release: Option[String],
    version: Option[String]
  )

  final private case class TracingSection(
    mode: Option[String],
    langfuse: Option[LangfuseSection]
  )

  final private case class TracingRoot(tracing: Option[TracingSection])

  implicit private val langfuseSectionReader: PureConfigReader[LangfuseSection] =
    PureConfigReader.forProduct6("url", "publicKey", "secretKey", "env", "release", "version")(LangfuseSection.apply)

  implicit private val tracingSectionReader: PureConfigReader[TracingSection] =
    PureConfigReader.forProduct2("mode", "langfuse")(TracingSection.apply)

  implicit private val tracingRootReader: PureConfigReader[TracingRoot] =
    PureConfigReader.forProduct1("tracing")(TracingRoot.apply)

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
