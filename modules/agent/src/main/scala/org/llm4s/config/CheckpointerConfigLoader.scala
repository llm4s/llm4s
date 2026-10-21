// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import com.typesafe.config.{ ConfigObject, ConfigRenderOptions, ConfigUtil, ConfigValue, ConfigValueType }
import org.llm4s.agent.graph.spi.{ CheckpointStore, CheckpointerBackends, CheckpointerSettings }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource
import pureconfig.error.{ ConfigReaderFailures, ConvertFailure, KeyNotFound }

import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Opens the checkpoint store `application.conf` chooses, at the application's edge (design 4.54).
 *
 * `llm4s.agent.checkpointer.kind` names the store - `memory` (the default, built into `llm4s-agent`), `sqlite`
 * (`llm4s-agent-checkpoint-sqlite`), `postgres` (`llm4s-agent-checkpoint-postgres`) or the kind of any other
 * [[org.llm4s.agent.graph.spi.CheckpointerBackend CheckpointerBackend]] on the classpath - and the backend for that
 * kind opens it from its own block, `llm4s.agent.checkpointer.<kind>`, which its module's `reference.conf` fills with
 * defaults and `${?VAR}` bindings:
 *
 * {{{
 * llm4s.agent.checkpointer {
 *   kind = "postgres"
 *   postgres { url = "jdbc:postgresql://db:5432/app", user = "agents", schema = "agents" }
 * }
 * }}}
 *
 * {{{
 * for
 *   store   <- CheckpointerConfigLoader.open()
 *   runtime <- RuntimeConfigLoader.runtime(store.checkpointer)
 *   agent   <- Agent.builder("assistant", client).withRuntime(runtime).build()
 * yield (store, agent)   // store.close() when the application stops
 * }}}
 *
 * Only the selected kind's block is read, and only a load is affected by a missing module: a kind whose backend is not
 * on the classpath fails `open` with a `ConfigurationError` naming the module to add, and nothing else. No setting's
 * value reaches an error or a log line.
 */
@Experimental
object CheckpointerConfigLoader:

  private val logger = LoggerFactory.getLogger(getClass)

  /** The configuration path of the store settings. */
  val Path: String = CheckpointerSettings.Path

  /** The selected kind and its block, from system properties, `application.conf` and every `reference.conf`. */
  def settings(): Result[CheckpointerSettings] = settings(ConfigSource.default)

  /** The store the configuration chooses, opened by a backend found on the classpath. */
  def open(): Result[CheckpointStore] = open(CheckpointerBackends.discover())

  /** The store the configuration chooses, opened by one of `backends`. */
  def open(backends: CheckpointerBackends): Result[CheckpointStore] = open(ConfigSource.default, backends)

  private[llm4s] def open(source: ConfigSource, backends: CheckpointerBackends): Result[CheckpointStore] =
    settings(source).flatMap(open(_, backends))

  private[llm4s] def open(settings: CheckpointerSettings, backends: CheckpointerBackends): Result[CheckpointStore] =
    backends.find(settings.kind) match
      case None => Left(missingBackend(settings.kind, backends))
      case Some(backend) =>
        logger.info(s"Opening the '${settings.kind}' checkpoint store (${backend.getClass.getName})")
        try backend.open(settings).left.map(scrubbed(settings, _))
        catch
          // A stale or half-present backend jar throws a LinkageError, which NonFatal lets through.
          case e: LinkageError => Left(thrown(settings, backend.getClass.getName, e))
          case NonFatal(e)     => Left(thrown(settings, backend.getClass.getName, e))

  /**
   * `error`, unless its text - message, formatted context, or the message of any exception it carries - contains one
   * of the block's sensitive values (`CheckpointerSettings.sensitiveValues`) (a driver that echoes the JDBC URL, say).
   * Then a `ConfigurationError` with that text redacted and no cause, so no secret leaves through `message`,
   * `formatted` or a logged stack trace.
   */
  private def scrubbed(settings: CheckpointerSettings, error: LLMError): LLMError =
    val secrets = settings.sensitiveValues
    val texts   = Vector(error.message, error.formatted) ++ causes(error).flatMap(c => Option(c.getMessage))
    if secrets.isEmpty || !texts.exists(t => secrets.exists(t.contains)) then error
    else
      ConfigurationError(
        s"The '${settings.kind}' checkpoint store failed to open: ${settings.redact(error.message)}",
        List(s"$Path.${settings.kind}")
      )

  /** The exceptions `error` carries, with their causes. */
  private def causes(error: LLMError): Vector[Throwable] =
    error.productIterator.toVector
      .flatMap {
        case t: Throwable       => Vector(t)
        case Some(t: Throwable) => Vector(t)
        case _                  => Vector.empty
      }
      .flatMap(t => Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(16).toVector)

  /** What a backend's thrown `e` is reported as: its class and its message, redacted, with no cause. */
  private def thrown(settings: CheckpointerSettings, backend: String, e: Throwable): ConfigurationError =
    ConfigurationError(
      s"The '${settings.kind}' checkpoint store backend ($backend) failed to open: " +
        s"${e.getClass.getName}: ${settings.redact(Option(e.getMessage).getOrElse(""))}",
      List(s"$Path.kind")
    )

  private[llm4s] def settings(source: ConfigSource): Result[CheckpointerSettings] =
    source
      .at(s"$Path.kind")
      .load[String]
      .left
      .map(failures => ConfigurationError(s"Failed to read $Path.kind: ${describe(failures)}", List(s"$Path.kind")))
      .flatMap { raw =>
        val kind = raw.trim.toLowerCase(Locale.ROOT)
        if kind.isEmpty then
          Left(ConfigurationError(s"$Path.kind is blank: set it to memory, sqlite or postgres", List(s"$Path.kind")))
        else block(source, kind).map(CheckpointerSettings(kind, _))
      }

  private def missingBackend(kind: String, backends: CheckpointerBackends): ConfigurationError =
    val diagnosis =
      if backends.failures.isEmpty then ""
      else s" Some backends could not be loaded: ${backends.failures.mkString("; ")}."
    val message = CheckpointerBackends.KnownModules.get(kind) match
      case Some(module) =>
        s"$Path.kind is '$kind', but $module, which provides it, is not on the classpath: add it as a dependency."
      case None =>
        s"$Path.kind is '$kind', but no checkpointer backend serves it (available: ${backends.kinds.mkString(", ")}). " +
          "A store module registers one in META-INF/services/org.llm4s.agent.graph.spi.CheckpointerBackend."
    ConfigurationError(message + diagnosis, List(s"$Path.kind"))

  /**
   * The kind's own block, flattened to strings; empty when absent. A block that is present but is not an object is a
   * `ConfigurationError` naming the path, never showing the value, which may be a secret.
   */
  private def block(source: ConfigSource, kind: String): Result[Map[String, String]] =
    // Quoted, so a kind containing a dot is one key rather than a nested path.
    val path = ConfigUtil.joinPath("llm4s", "agent", "checkpointer", kind)
    source.at(path).value() match
      case Right(obj: ConfigObject) =>
        Right(obj.toConfig.entrySet().asScala.map(e => e.getKey -> render(e.getValue)).toMap)
      case Right(value) if value.valueType == ConfigValueType.NULL => Right(Map.empty)
      case Right(value) =>
        Left(
          ConfigurationError(
            s"$path must be an object, but is a ${value.valueType.name.toLowerCase(Locale.ROOT)}: " +
              s"write the '$kind' store's settings as $path { ... }",
            List(path)
          )
        )
      case Left(failures) if isAbsent(failures) => Right(Map.empty)
      case Left(failures) =>
        Left(ConfigurationError(s"$path cannot be read: ${describe(failures)}", List(path)))

  private def isAbsent(failures: ConfigReaderFailures): Boolean =
    failures.toList.forall {
      case ConvertFailure(_: KeyNotFound, _, _) => true
      case _                                    => false
    }

  private def describe(failures: ConfigReaderFailures): String = failures.toList.map(_.description).mkString("; ")

  private def render(value: ConfigValue): String =
    if value.valueType == ConfigValueType.STRING then value.unwrapped.toString
    else value.render(ConfigRenderOptions.concise())
