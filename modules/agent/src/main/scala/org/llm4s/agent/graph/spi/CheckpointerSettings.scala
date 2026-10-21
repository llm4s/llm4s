package org.llm4s.agent.graph.spi

import com.typesafe.config.ConfigValueFactory
import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.util.Locale
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*
import scala.util.Try

/**
 * What a [[CheckpointerBackend]] opens its store from: the selected `kind` and that kind's own block,
 * `llm4s.agent.checkpointer.<kind>`, flattened to strings keyed by their path inside the block (`busyTimeout`,
 * `pool.size`).
 *
 * The typed readers turn a missing or malformed value into a `ConfigurationError` that names the full key, never the
 * value, which may be a password. `toString` lists only the keys, for the same reason.
 *
 * @param kind   the selected kind, lower-cased
 * @param extras the kind's block; empty when the configuration has none
 */
@Experimental
final class CheckpointerSettings private (val kind: String, val extras: Map[String, String]):

  /** The full configuration key of `name` in this kind's block: `llm4s.agent.checkpointer.<kind>.<name>`. */
  def key(name: String): String = s"${CheckpointerSettings.Path}.$kind.$name"

  /** `name`'s value, unless it is absent or blank. */
  def optionalString(name: String): Option[String] = extras.get(name).filterNot(_.isBlank)

  /**
   * `name`'s value, or a `ConfigurationError` naming the key (and `envVar`, the variable the module's
   * `reference.conf` binds to it, when there is one) if it is absent or blank.
   */
  def string(name: String, envVar: Option[String] = None): Result[String] =
    optionalString(name).toRight {
      val bound = envVar.fold("")(v => s" (or $v)")
      ConfigurationError(s"Checkpointer store '$kind' needs ${key(name)}$bound, which is not set", List(key(name)))
    }

  /** `name` as a HOCON duration (`5 seconds`, `250ms`; a bare number is milliseconds), or `default` when absent. */
  def duration(name: String, default: FiniteDuration): Result[FiniteDuration] =
    read(name, default, "a duration such as \"5 seconds\"") { value =>
      ConfigValueFactory.fromMap(java.util.Map.of("v", value)).toConfig.getDuration("v").toScala
    }

  /** `name` as an integer, or `default` when absent. */
  def int(name: String, default: Int): Result[Int] =
    read(name, default, "an integer")(_.trim.toInt)

  /** `name` as `true` or `false`, or `default` when absent. */
  def boolean(name: String, default: Boolean): Result[Boolean] =
    read(name, default, "true or false")(_.trim.toLowerCase(Locale.ROOT).toBooleanOption.get)

  private def read[A](name: String, default: A, what: String)(parse: String => A): Result[A] =
    optionalString(name) match
      case None => Right(default)
      case Some(value) =>
        Try(parse(value)).toEither.left.map(_ => ConfigurationError(s"${key(name)} must be $what", List(key(name))))

  override def toString: String = s"CheckpointerSettings($kind, keys = ${extras.keys.toVector.sorted.mkString(", ")})"

object CheckpointerSettings:

  /** The configuration path of the store settings. */
  val Path: String = "llm4s.agent.checkpointer"

  /** The settings for `kind` (lower-cased) from its block `extras`. */
  def apply(kind: String, extras: Map[String, String] = Map.empty): CheckpointerSettings =
    new CheckpointerSettings(kind.trim.toLowerCase(Locale.ROOT), extras)
