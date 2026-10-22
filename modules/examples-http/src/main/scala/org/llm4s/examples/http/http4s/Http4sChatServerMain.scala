package org.llm4s.examples.http.http4s

import cats.effect.{ ExitCode, IO, IOApp }
import com.comcast.ip4s.{ Host, Port }
import org.http4s.HttpApp
import org.llm4s.effect.cats.AgentIO
import org.llm4s.examples.http.ServerSettings

/**
 * Runs the http4s reference app: `sbt "examplesHttp/run [--host H] [--port N] [--fake]"`.
 *
 * The provider comes from `application.conf` through `Llm4sConfig` (by default the samples' `ollama-local` section),
 * or, with `--fake`, a scripted client that needs no model.
 */
object Http4sChatServerMain extends IOApp {

  /** Serves until stopped; exits with `2` for arguments it cannot use. */
  def run(args: List[String]): IO[ExitCode] =
    serve(args, agent => new ChatRoutes(agent).routes.orNotFound, "POST /chat, POST /chat/stream")

  /**
   * Serves `app` over the agent `args` select on Ember until stopped, printing `endpoints` once it is up; exits with
   * `2` for arguments it cannot use. Shared with the Tapir app, which differs only in its `HttpApp`.
   */
  private[http] def serve(args: List[String], app: AgentIO[IO] => HttpApp[IO], endpoints: String): IO[ExitCode] =
    ServerSettings.parse(args).flatMap(addressOf) match {
      case Left(message) => IO.println(message).as(ExitCode(2))
      case Right((settings, host, port)) =>
        settings.client
          .evalMap(Http4sChatServer.agent)
          .flatMap(agent => Http4sChatServer.serve(app(agent), host, port))
          .use { server =>
            val model = if (settings.fake) "the fake client (--fake)" else "the configured provider"
            IO.println(s"Serving $model on ${server.baseUri}: $endpoints. Ctrl-C to stop.") *> IO.never
          }
          .as(ExitCode.Success)
    }

  private def addressOf(settings: ServerSettings): Either[String, (ServerSettings, Host, Port)] =
    for {
      host <- Host.fromString(settings.host).toRight(s"--host '${settings.host}' is not a host name or address")
      port <- Port.fromInt(settings.port).toRight(s"--port ${settings.port} is not a port")
    } yield (settings, host, port)
}
