package org.llm4s.examples.http.ziohttp

import org.llm4s.examples.http.ServerSettings
import org.llm4s.zio.LLMClientZ
import zio.*

/**
 * Runs the ZIO HTTP reference app:
 * `sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain [--host H] [--port N] [--fake]"`.
 *
 * The provider comes from `application.conf` through `Llm4sConfig` (`LLMClientZ.layer`; by default the samples'
 * `ollama-local` section), or, with `--fake`, a scripted client that needs no model.
 */
object ZioChatServerMain extends ZIOAppDefault {

  /** Serves until stopped; exits with `2` for arguments it cannot use. */
  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    getArgs.flatMap { args =>
      ServerSettings.parse(args.toList) match {
        case Left(message) => Console.printLine(message) *> exit(ExitCode(2))
        case Right(settings) =>
          for {
            client <-
              if (settings.fake) ZIO.succeed(LLMClientZ(ServerSettings.fakeClient))
              else LLMClientZ.layer.build.map(_.get[LLMClientZ])
            agent <- ZioChatServer.agent(client)
            port  <- ZioChatServer.start(agent, settings.host, settings.port)
            model = if (settings.fake) "the fake client (--fake)" else "the configured provider"
            _ <- Console.printLine(
              s"Serving $model on http://${settings.host}:$port: POST /chat, POST /chat/stream. Ctrl-C to stop."
            )
            _ <- ZIO.never
          } yield ()
      }
    }
}
