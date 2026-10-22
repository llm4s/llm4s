package org.llm4s.examples.http.http4s

import cats.effect.{ IO, Resource }
import com.comcast.ip4s.{ Host, Port }
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.llm4s.agent.AgentBuilder
import org.llm4s.effect.cats.{ AgentIO, LLMClientIO }
import org.llm4s.examples.http.ChatAgent

import scala.concurrent.duration.*

/** The http4s reference app: [[ChatRoutes]] on an Ember server. */
object Http4sChatServer {

  /** The agent every request runs: [[org.llm4s.examples.http.ChatAgent.configure ChatAgent.configure]]. */
  val configureAgent: AgentBuilder => AgentBuilder = ChatAgent.configure

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
    serve(new ChatRoutes(agent, heartbeat).routes.orNotFound, host, port)

  /** An Ember server on `host`:`port` serving `app`; the Tapir app is served this way too. */
  def serve(app: HttpApp[IO], host: Host, port: Port): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(app)
      .withShutdownTimeout(1.second)
      .build
}
