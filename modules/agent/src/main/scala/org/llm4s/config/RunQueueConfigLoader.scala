// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import com.typesafe.config.Config
import org.llm4s.agent.service.{ LeasePolicy, RunQueueSettings }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.types.Result
import pureconfig.ConfigSource

import scala.jdk.DurationConverters.*
import scala.util.Try

/**
 * Loads the run queue's settings (design 4.55) from `llm4s.agent.queue`: `maxAttempts`, `ticketLifetime` and the
 * `lease` block (`ttl`, `renewEvery`), with the defaults of `llm4s-agent`'s `reference.conf`. The store the queue lives
 * in is chosen with the checkpoint store, not here.
 */
@Experimental
object RunQueueConfigLoader:

  /** The configuration path of the queue settings. */
  val Path: String = "llm4s.agent.queue"

  /** The settings from system properties, `application.conf` and every `reference.conf`. */
  def load(): Result[RunQueueSettings] = load(ConfigSource.default)

  /** The settings from `source`'s `llm4s.agent.queue`. */
  private[llm4s] def load(source: ConfigSource): Result[RunQueueSettings] =
    for
      c <- source
        .at(Path)
        .load[Config]
        .left
        .map(failures => ConfigurationError(s"Failed to read $Path: ${failures.prettyPrint()}", List(Path)))
      maxAttempts    <- field("maxAttempts")(c.getInt("maxAttempts"))
      ticketLifetime <- field("ticketLifetime")(c.getDuration("ticketLifetime").toScala)
      ttl            <- field("lease.ttl")(c.getDuration("lease.ttl").toScala)
      renewEvery     <- field("lease.renewEvery")(c.getDuration("lease.renewEvery").toScala)
      lease          <- LeasePolicy.of(ttl, renewEvery).left.map(invalid(s"$Path.lease"))
      settings       <- RunQueueSettings.of(maxAttempts, ticketLifetime, lease).left.map(invalid(Path))
    yield settings

  private def invalid(path: String)(error: LLMError): LLMError =
    ConfigurationError(s"Invalid $path: ${error.message}", List(path))

  /** `read`, a missing or mistyped key becoming a `ConfigurationError` naming it. */
  private def field[A](name: String)(read: => A): Result[A] =
    Try(read).toEither.left.map[LLMError](e =>
      ConfigurationError(s"Invalid $Path.$name: ${e.getMessage}", List(s"$Path.$name"))
    )
