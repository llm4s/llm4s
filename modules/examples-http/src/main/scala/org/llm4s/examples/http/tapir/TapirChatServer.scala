package org.llm4s.examples.http.tapir

import cats.effect.{ IO, Resource }
import com.comcast.ip4s.{ Host, Port }
import org.http4s.server.Server
import org.llm4s.effect.cats.AgentIO
import org.llm4s.examples.http.http4s.{ ChatRoutes, Http4sChatServer }

import scala.concurrent.duration.FiniteDuration

/**
 * The Tapir reference app: [[ChatEndpoints]] interpreted by `tapir-http4s-server` ([[TapirChatRoutes]]) on the
 * http4s app's Ember server, over the same agent
 * ([[org.llm4s.examples.http.http4s.Http4sChatServer.agent Http4sChatServer.agent]]).
 */
object TapirChatServer {

  /**
   * An Ember server on `host`:`port` serving [[TapirChatRoutes]] over `agent`. Port `0` binds an ephemeral port; the
   * server's `address` has the one bound.
   */
  def resource(
    agent: AgentIO[IO],
    host: Host,
    port: Port,
    heartbeat: FiniteDuration = ChatRoutes.DefaultHeartbeat
  ): Resource[IO, Server] =
    Http4sChatServer.serve(new TapirChatRoutes(agent, heartbeat).routes.orNotFound, host, port)
}
