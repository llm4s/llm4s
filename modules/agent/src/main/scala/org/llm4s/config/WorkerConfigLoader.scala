// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import com.typesafe.config.Config
import org.llm4s.agent.graph.{ RetentionPolicy, SweepPolicy }
import org.llm4s.agent.service.WorkerSettings
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.types.Result
import pureconfig.ConfigSource

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*
import scala.util.Try

/**
 * Loads a run queue worker's settings (Stage 5 W2, design 4.56) from `llm4s.agent.worker`: `concurrency`,
 * `pollInterval`, `unrunnableAfter`, the `drain` block (`grace`, `exitTimeout`), the `sweep` block (`enabled`, `every`,
 * `batchSize`, an optional `tombstoneAge` and an optional `retention` block of `maxAge`, `maxCheckpoints` and
 * `maxEvents`), an optional `label` and the `metrics` block (`queueDepthEvery`, design 4.59), with the defaults of `llm4s-agent`'s `reference.conf`. The queue's own settings
 * - the lease, `maxAttempts`, the ticket lifetime - are [[RunQueueConfigLoader]]'s; the store is
 * [[CheckpointerConfigLoader]]'s.
 */
@Experimental
object WorkerConfigLoader:

  /** The configuration path of the worker settings. */
  val Path: String = "llm4s.agent.worker"

  /** The settings from system properties, `application.conf` and every `reference.conf`. */
  def load(): Result[WorkerSettings] = load(ConfigSource.default)

  /** The settings from `source`'s `llm4s.agent.worker`. */
  private[llm4s] def load(source: ConfigSource): Result[WorkerSettings] =
    for
      c <- source
        .at(Path)
        .load[Config]
        .left
        .map(failures => ConfigurationError(s"Failed to read $Path: ${failures.prettyPrint()}", List(Path)))
      concurrency     <- field("concurrency")(c.getInt("concurrency"))
      pollInterval    <- duration(c, "pollInterval")
      unrunnableAfter <- duration(c, "unrunnableAfter")
      grace           <- duration(c, "drain.grace")
      exitTimeout     <- duration(c, "drain.exitTimeout")
      enabled         <- field("sweep.enabled")(c.getBoolean("sweep.enabled"))
      every           <- duration(c, "sweep.every")
      batchSize       <- field("sweep.batchSize")(c.getInt("sweep.batchSize"))
      tombstoneAge    <- optional(c, "sweep.tombstoneAge")(duration(c, _))
      maxAge          <- optional(c, "sweep.retention.maxAge")(duration(c, _))
      maxCheckpoints  <- optional(c, "sweep.retention.maxCheckpoints")(p => field(p)(c.getInt(p)))
      maxEvents       <- optional(c, "sweep.retention.maxEvents")(p => field(p)(c.getInt(p)))
      label           <- optional(c, "label")(p => field(p)(c.getString(p)))
      depthEvery      <- duration(c, "metrics.queueDepthEvery")
      retention <- RetentionPolicy.of(maxAge, maxCheckpoints, maxEvents).left.map(invalid(s"$Path.sweep.retention"))
      sweep     <- SweepPolicy.of(retention, tombstoneAge, batchSize).left.map(invalid(s"$Path.sweep"))
      settings <- WorkerSettings
        .of(
          concurrency,
          pollInterval,
          unrunnableAfter,
          grace,
          exitTimeout,
          Option.when(enabled)(every),
          sweep,
          label,
          depthEvery
        )
        .left
        .map(invalid(Path))
    yield settings

  private def invalid(path: String)(error: LLMError): LLMError =
    ConfigurationError(s"Invalid $path: ${error.message}", List(path))

  private def duration(c: Config, name: String): Result[FiniteDuration] = field(name)(c.getDuration(name).toScala)

  private def optional[A](c: Config, name: String)(read: String => Result[A]): Result[Option[A]] =
    if c.hasPath(name) then read(name).map(Some(_)) else Right(None)

  /** `read`, a missing or mistyped key becoming a `ConfigurationError` naming it. */
  private def field[A](name: String)(read: => A): Result[A] =
    Try(read).toEither.left.map[LLMError](e =>
      ConfigurationError(s"Invalid $Path.$name: ${e.getMessage}", List(s"$Path.$name"))
    )
