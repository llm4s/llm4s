// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import com.typesafe.config.Config
import org.llm4s.agent.graph.{ Checkpointer, ClaimPolicy, GraphRuntime, WatchPolicy }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.types.Result
import pureconfig.ConfigSource

import java.time.Clock
import scala.jdk.DurationConverters.*
import scala.util.Try

/**
 * Loads a [[org.llm4s.agent.graph.GraphRuntime GraphRuntime]]'s policies from `llm4s.agent.runtime` at the
 * application's edge (design 4.54): how its runs hold their threads' claims (`ClaimPolicy`, `claims`) and how its subscriptions watch the store for commits made elsewhere
 * ([[org.llm4s.agent.graph.WatchPolicy WatchPolicy]], `watch`). `llm4s-agent`'s `reference.conf` holds the defaults,
 * which are the policies' own, and binds `claims.label` to `LLM4S_RUNTIME_LABEL`, so each instance of a service can
 * name itself in the logs (a pod name, say).
 *
 * {{{
 * llm4s.agent.runtime {
 *   claims { ttl = 30 seconds, renewEvery = 10 seconds, label = "worker-1" }
 *   watch  { enabled = true, pollInterval = 250 milliseconds }
 * }
 * }}}
 *
 * A value a policy refuses is a `ConfigurationError` naming the key.
 */
@Experimental
object RuntimeConfigLoader:

  /** The configuration path of the runtime settings. */
  val Path: String = "llm4s.agent.runtime"

  /** The claim policy from system properties, `application.conf` and every `reference.conf`. */
  def claimPolicy(): Result[ClaimPolicy] = claimPolicy(ConfigSource.default)

  /** The watch policy from system properties, `application.conf` and every `reference.conf`. */
  def watchPolicy(): Result[WatchPolicy] = watchPolicy(ConfigSource.default)

  /** A runtime over `checkpointer`, on the system clock, with the configured claim and watch policies. */
  def runtime(checkpointer: Checkpointer): Result[GraphRuntime] = runtime(ConfigSource.default, checkpointer)

  private[llm4s] def runtime(source: ConfigSource, checkpointer: Checkpointer): Result[GraphRuntime] =
    for
      claims <- claimPolicy(source)
      watch  <- watchPolicy(source)
    yield new GraphRuntime(checkpointer, Clock.systemUTC(), claims, watch)

  private[llm4s] def claimPolicy(source: ConfigSource): Result[ClaimPolicy] =
    section(source, "claims").flatMap { c =>
      for
        ttl        <- field("claims.ttl")(c.getDuration("ttl").toScala)
        renewEvery <- field("claims.renewEvery")(c.getDuration("renewEvery").toScala)
        label      <- field("claims.label")(Option.when(c.hasPath("label"))(c.getString("label")))
        policy     <- ClaimPolicy.of(ttl, renewEvery, label).left.map(invalid("claims"))
      yield policy
    }

  private[llm4s] def watchPolicy(source: ConfigSource): Result[WatchPolicy] =
    section(source, "watch").flatMap { c =>
      for
        enabled      <- field("watch.enabled")(c.getBoolean("enabled"))
        pollInterval <- field("watch.pollInterval")(c.getDuration("pollInterval").toScala)
        policy       <- WatchPolicy.of(enabled, pollInterval).left.map(invalid("watch"))
      yield policy
    }

  private def section(source: ConfigSource, name: String): Result[Config] =
    source
      .at(s"$Path.$name")
      .load[Config]
      .left
      .map(failures =>
        ConfigurationError(s"Failed to read $Path.$name: ${failures.prettyPrint()}", List(s"$Path.$name"))
      )

  private def invalid(name: String)(error: LLMError): LLMError =
    ConfigurationError(s"Invalid $Path.$name: ${error.message}", List(s"$Path.$name"))

  /** `read`, a missing or mistyped key becoming a `ConfigurationError` naming it. */
  private def field[A](name: String)(read: => A): Result[A] =
    Try(read).toEither.left.map[LLMError](e =>
      ConfigurationError(s"Invalid $Path.$name: ${e.getMessage}", List(s"$Path.$name"))
    )
