package org.llm4s.examples.http.http4s

import cats.effect.{ ExitCode, IO, IOApp }
import com.comcast.ip4s.{ Host, Port }
import org.llm4s.examples.http.ServerSettings

/**
 * Runs the http4s reference app: `sbt "examplesHttp/run [--host H] [--port N] [--fake]"`.
 *
 * The provider comes from `application.conf` through `Llm4sConfig` (by default the samples' `ollama-local` section),
 * or, with `--fake`, a scripted client that needs no model.
 */
object Http4sChatServerMain extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    ServerSettings.parse(args).flatMap(addressOf) match {
      case Left(message) => IO.println(message).as(ExitCode(2))
      case Right((settings, host, port)) =>
        settings.client
          .evalMap(Http4sChatServer.agent)
          .flatMap(agent => Http4sChatServer.resource(agent, host, port))
          .use { server =>
            val model = if (settings.fake) "the fake client (--fake)" else "the configured provider"
            IO.println(s"Serving $model on ${server.baseUri}: POST /chat, POST /chat/stream. Ctrl-C to stop.") *>
              IO.never
          }
          .as(ExitCode.Success)
    }

  private def addressOf(settings: ServerSettings): Either[String, (ServerSettings, Host, Port)] =
    for {
      host <- Host.fromString(settings.host).toRight(s"--host '${settings.host}' is not a host name or address")
      port <- Port.fromInt(settings.port).toRight(s"--port ${settings.port} is not a port")
    } yield (settings, host, port)
}
