// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.deploy

import org.llm4s.agent.AgentId
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import com.typesafe.config.Config
import pureconfig.ConfigSource

import java.util.Locale
import scala.concurrent.duration.*
import scala.util.Try

/** Which model the example agent calls: the built-in demo model, or the application's default provider. */
enum AgentModel:
  case Demo, Provider

/**
 * How the example agent service runs (design 4.60), read from `llm4s.deploy-service`; the defaults are in this
 * module's `reference.conf`:
 *
 * {{{
 * llm4s.deploy-service {
 *   agent   { id = "assistant", model = "demo", toolDelay = 2 seconds }  # LLM4S_SERVICE_MODEL, LLM4S_SERVICE_TOOL_DELAY
 *   events  { wait = 15 seconds, pageSize = 100 }
 *   metrics { enabled = true }                                             # LLM4S_SERVICE_METRICS
 * }
 * }}}
 *
 * The store, the queue, the worker and the claims are `llm4s-agent`'s own blocks, `llm4s.agent.*`.
 *
 * @param agentId        the id queue entries name
 * @param model          the demo model, or the default provider
 * @param toolDelay      how long the demo model's tool call takes; zero answers at once, with no tool call
 * @param eventsWait     how long one read of a followed run waits for a commit before a keep-alive is sent
 * @param pageSize       the most events one read of a followed run returns
 * @param metricsEnabled whether `GET /metrics` serves the runtime's metrics
 */
final case class AgentServiceConfig(
  agentId: AgentId,
  model: AgentModel,
  toolDelay: FiniteDuration,
  eventsWait: FiniteDuration,
  pageSize: Int,
  metricsEnabled: Boolean
)

object AgentServiceConfig:

  /** The path of the service's block. */
  val Path: String = "llm4s.deploy-service"

  /** The defaults of `reference.conf`, for tests that build a service in code. */
  val default: AgentServiceConfig =
    AgentServiceConfig(AgentId.of("assistant").toOption.get, AgentModel.Demo, 2.seconds, 15.seconds, 100, true)

  /** The configuration of this process: system properties, `application.conf`, then `reference.conf`. */
  def load(): Result[AgentServiceConfig] = load(ConfigSource.default)

  /** Load from `source`, which must contain an `llm4s.deploy-service` block. */
  def load(source: ConfigSource): Result[AgentServiceConfig] =
    source.at(Path).load[Config].left.map(f => invalid(Path, f.prettyPrint())).flatMap { c =>
      def field[A](name: String)(read: => A): Result[A] =
        Try(read).toEither.left.map(e => invalid(s"$Path.$name", e.getMessage))
      def duration(name: String): Result[FiniteDuration] =
        field(name)(c.getDuration(name).toNanos.nanos).flatMap { d =>
          Either.cond(d >= Duration.Zero, d, invalid(s"$Path.$name", s"must not be negative, was $d"))
        }
      for
        rawId    <- field("agent.id")(c.getString("agent.id"))
        agentId  <- AgentId.of(rawId).left.map(e => invalid(s"$Path.agent.id", e.message))
        rawModel <- field("agent.model")(c.getString("agent.model"))
        model    <- modelOf(rawModel)
        delay    <- duration("agent.toolDelay")
        wait     <- duration("events.wait")
        _        <- Either.cond(wait > Duration.Zero, (), invalid(s"$Path.events.wait", "must be positive"))
        pageSize <- field("events.pageSize")(c.getInt("events.pageSize"))
        _ <- Either.cond(
          pageSize >= 1 && pageSize <= 1000,
          (),
          invalid(s"$Path.events.pageSize", s"must be 1 to 1000, was $pageSize")
        )
        metrics <- field("metrics.enabled")(c.getBoolean("metrics.enabled"))
      yield AgentServiceConfig(agentId, model, delay, wait, pageSize, metrics)
    }

  private def modelOf(raw: String): Result[AgentModel] =
    raw.trim.toLowerCase(Locale.ROOT) match
      case "demo"     => Right(AgentModel.Demo)
      case "provider" => Right(AgentModel.Provider)
      case other      => Left(invalid(s"$Path.agent.model", s"must be demo or provider, was '$other'"))

  private def invalid(path: String, why: String): ConfigurationError =
    ConfigurationError(s"Invalid $path: $why", List(path))
