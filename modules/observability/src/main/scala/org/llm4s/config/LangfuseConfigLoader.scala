// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import com.typesafe.config.{ ConfigObject, ConfigRenderOptions, ConfigValueType }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.LangfuseConfig
import org.llm4s.types.Result
import pureconfig.ConfigSource

import scala.jdk.CollectionConverters.*

/**
 * Loads [[org.llm4s.llmconnect.config.LangfuseConfig]] from `llm4s.tracing.langfuse`,
 * whatever `TRACING_MODE` selects.
 *
 * The tracing backend does not need this: with `TRACING_MODE=langfuse` the block
 * reaches it as `TracingSettings.extras`. This is for code that talks to Langfuse
 * alongside another tracer - `RAGASLangfuseObserver` in `llm4s-rag`, or a
 * `TracingComposer.combine` of Langfuse with Console:
 *
 * {{{
 * for
 *   langfuse <- LangfuseConfigLoader.default()
 * yield TracingComposer.combine(LangfuseTracing.from(langfuse), new ConsoleTracing())
 * }}}
 */
object LangfuseConfigLoader {

  private val Path = "llm4s.tracing.langfuse"

  /**
   * Reads `llm4s.tracing.langfuse` from `source`; an absent block gives the defaults.
   *
   * @return the config, or a [[org.llm4s.error.ConfigurationError]] when
   *         `llm4s.tracing.langfuse` is set to something other than an object.
   */
  def load(source: ConfigSource): Result[LangfuseConfig] =
    source.at(Path).value() match {
      case Right(block: ConfigObject) =>
        val values = block.toConfig.entrySet().asScala.map { entry =>
          val value = entry.getValue
          val text =
            if (value.valueType == ConfigValueType.STRING) value.unwrapped.toString
            else value.render(ConfigRenderOptions.concise())
          entry.getKey -> text
        }
        Right(LangfuseConfig.fromExtras(values.toMap))
      case Right(other) =>
        Left(
          ConfigurationError(
            s"$Path must be an object, but is a ${other.valueType.toString.toLowerCase(java.util.Locale.ROOT)}"
          )
        )
      case Left(_) =>
        Right(LangfuseConfig())
    }

  /** [[load]] against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def default(): Result[LangfuseConfig] =
    load(ConfigSource.default)
}
