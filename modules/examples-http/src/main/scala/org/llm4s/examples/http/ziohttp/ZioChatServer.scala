package org.llm4s.examples.http.ziohttp

import org.llm4s.error.LLMError
import org.llm4s.examples.http.ChatAgent
import org.llm4s.zio.{ AgentZ, LLMClientZ }
import zio.*
import zio.http.Server

/** The ZIO HTTP reference app: [[ZioChatRoutes]] on a ZIO HTTP (Netty) server. */
object ZioChatServer {

  /** The agent of [[org.llm4s.examples.http.ChatAgent.configure ChatAgent.configure]] over `client`. */
  def agent(client: LLMClientZ): IO[LLMError, AgentZ] = client.agent("chat")(ChatAgent.configure)

  /**
   * A server on `host`:`port` serving [[ZioChatRoutes]] over `agent`, for as long as the scope is open; succeeds
   * with the port it bound, so port `0` binds an ephemeral one.
   */
  def start(
    agent: AgentZ,
    host: String,
    port: Int,
    heartbeat: Duration = ZioChatRoutes.DefaultHeartbeat
  ): ZIO[Scope, Throwable, Int] =
    Server
      .defaultWith(_.binding(host, port).gracefulShutdownTimeout(1.second))
      .build
      .flatMap(server => Server.install(new ZioChatRoutes(agent, heartbeat).routes).provideEnvironment(server))
}
