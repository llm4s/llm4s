package org.llm4s.examples.http.http4s

import cats.effect.{ IO, Resource }
import com.comcast.ip4s.{ Host, Port }
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.llm4s.agent.AgentBuilder
import org.llm4s.effect.cats.{ AgentIO, LLMClientIO }

import scala.concurrent.duration.*

/** The http4s reference app: [[ChatRoutes]] on an Ember server. */
object Http4sChatServer {

  /** The agent every request runs: streaming on, so `/chat/stream` gets the answer as it is generated. */
  val configureAgent: AgentBuilder => AgentBuilder =
    _.withStreaming().withSystemPrompt("You are a helpful assistant. Answer concisely.")

  /** The agent of [[configureAgent]] over `client`. */
  def agent(client: LLMClientIO[IO]): IO[AgentIO[IO]] = client.agent("chat")(configureAgent)

  /**
   * An Ember server on `host`:`port` serving [[ChatRoutes]] over `agent`. Port `0` binds an ephemeral port; the
   * server's `address` has the one bound.
   */
  def resource(
    agent: AgentIO[IO],
    host: Host,
    port: Port,
    heartbeat: FiniteDuration = ChatRoutes.DefaultHeartbeat
  ): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(new ChatRoutes(agent, heartbeat).routes.orNotFound)
      .withShutdownTimeout(1.second)
      .build
}
