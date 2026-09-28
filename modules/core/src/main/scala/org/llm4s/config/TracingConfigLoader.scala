// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.TracingMode
import org.llm4s.types.Result
import com.typesafe.config.{ ConfigObject, ConfigRenderOptions, ConfigUtil, ConfigValue, ConfigValueType }
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

import scala.jdk.CollectionConverters._

/**
 * Internal loader that builds [[org.llm4s.llmconnect.config.TracingSettings]]
 * from a PureConfig [[pureconfig.ConfigSource]].
 *
 * Reads `llm4s.tracing.mode` to select the tracing backend (`console` when unset,
 * `noop`/`none`, or the mode of any backend on the classpath, such as `langfuse` or
 * `opentelemetry`), and copies the selected mode's own block,
 * `llm4s.tracing.<mode>`, into `TracingSettings.extras`.
 *
 * Core reads no backend's keys itself. Since slice 6 (#1133) the Langfuse and
 * OpenTelemetry blocks, their `reference.conf` bindings and their typed configs live
 * in `llm4s-observability` and `llm4s-observability-otel`, with the code that reads them.
 *
 * This object is package-private; callers should use [[Llm4sConfig.tracing]]
 * instead.
 */
private[config] object TracingConfigLoader {

  final private case class TracingSection(mode: Option[String])

  final private case class TracingRoot(tracing: Option[TracingSection])

  implicit private val tracingSectionReader: PureConfigReader[TracingSection] =
    PureConfigReader.forProduct1("mode")(TracingSection.apply)

  implicit private val tracingRootReader: PureConfigReader[TracingRoot] =
    PureConfigReader.forProduct1("tracing")(TracingRoot.apply)

  /**
   * Loads tracing settings from `source`.
   *
   * Reads `llm4s.tracing.mode`, defaulting to `console` when it is unset or blank, and
   * the selected mode's block into `extras`.
   *
   * @param source PureConfig source to read from; use `ConfigSource.default`
   *               in production to read environment variables and
   *               `application.conf`.
   * @return the tracing settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when the config tree cannot be parsed.
   */
  def load(source: ConfigSource): Result[TracingSettings] =
    source
      .at("llm4s")
      .load[TracingRoot]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s tracing config via PureConfig: $msg")
      }
      .map { root =>
        val modeStr = root.tracing.flatMap(_.mode).map(_.trim).filter(_.nonEmpty).getOrElse("console")
        val mode    = TracingMode.fromString(modeStr)
        TracingSettings(mode, modeExtras(source, mode))
      }

  /**
   * The selected mode's own block, `llm4s.tracing.<mode>`, flattened to strings: what a
   * [[org.llm4s.trace.spi.TracingBackend]] reads its settings from. Only the selected
   * mode's block is read, so a malformed block for another backend never matters.
   */
  private def modeExtras(source: ConfigSource, mode: TracingMode): Map[String, String] = {
    // Quoted, so a mode name containing a dot is one key rather than a nested path.
    val path = ConfigUtil.joinPath("llm4s", "tracing", mode.name)
    source.at(path).value() match {
      case Right(block: ConfigObject) =>
        block.toConfig.entrySet().asScala.map(e => e.getKey -> render(e.getValue)).toMap
      case _ => Map.empty
    }
  }

  private def render(value: ConfigValue): String =
    if (value.valueType == ConfigValueType.STRING) value.unwrapped.toString
    else value.render(ConfigRenderOptions.concise())
}
