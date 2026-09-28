// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.TracingSettings
import org.llm4s.trace.TracingMode
import org.llm4s.types.Result
import com.typesafe.config.{ ConfigObject, ConfigRenderOptions, ConfigUtil, ConfigValue, ConfigValueType }
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }
import pureconfig.error.{ ConfigReaderFailures, ConvertFailure, KeyNotFound }

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
   *         when the config tree cannot be parsed, or when the selected mode's block
   *         `llm4s.tracing.<mode>` is present but is not an object or cannot be read.
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
      .flatMap { root =>
        val modeStr = root.tracing.flatMap(_.mode).map(_.trim).filter(_.nonEmpty).getOrElse("console")
        val mode    = TracingMode.fromString(modeStr)
        modeExtras(source, mode).map(TracingSettings(mode, _))
      }

  /**
   * The selected mode's own block, `llm4s.tracing.<mode>`, flattened to strings: what a
   * [[org.llm4s.trace.spi.TracingBackend]] reads its settings from. Only the selected
   * mode's block is read, so a malformed block for another backend never matters.
   *
   * An absent (or `null`) block is an empty map: the backend applies its defaults. A block
   * that is present but is not an object - `opentelemetry = "http://collector:4317"` - or
   * cannot be read is a [[org.llm4s.error.ConfigurationError]] naming the path. Treating it
   * as absent would let the backend start on its defaults and silently ignore what the
   * operator configured.
   */
  private def modeExtras(source: ConfigSource, mode: TracingMode): Result[Map[String, String]] = {
    // Quoted, so a mode name containing a dot is one key rather than a nested path.
    val path = ConfigUtil.joinPath("llm4s", "tracing", mode.name)
    source.at(path).value() match {
      case Right(block: ConfigObject) =>
        Right(block.toConfig.entrySet().asScala.map(e => e.getKey -> render(e.getValue)).toMap)
      case Right(value) if value.valueType == ConfigValueType.NULL => Right(Map.empty)
      case Right(value) =>
        Left(
          ConfigurationError(
            // The value itself is left out: a misplaced scalar here may well be a secret.
            s"$path must be an object, but is a ${value.valueType.name.toLowerCase(java.util.Locale.ROOT)}. " +
              s"It holds the settings for tracing mode '${mode.name}': write it as $path { ... }."
          )
        )
      case Left(failures) if isAbsent(failures) => Right(Map.empty)
      case Left(failures) =>
        Left(
          ConfigurationError(
            s"Tracing mode '${mode.name}' is selected but $path cannot be read: " +
              failures.toList.map(_.description).mkString("; ")
          )
        )
    }
  }

  private def isAbsent(failures: ConfigReaderFailures): Boolean =
    failures.toList.forall {
      case ConvertFailure(_: KeyNotFound, _, _) => true
      case _                                    => false
    }

  private def render(value: ConfigValue): String =
    if (value.valueType == ConfigValueType.STRING) value.unwrapped.toString
    else value.render(ConfigRenderOptions.concise())
}
