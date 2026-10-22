package org.llm4s.examples.http.tapir

import cats.effect.{ ExitCode, IO, IOApp }
import org.llm4s.examples.http.http4s.Http4sChatServerMain

/**
 * Runs the Tapir reference app:
 * `sbt "examplesHttp/runMain org.llm4s.examples.http.tapir.TapirChatServerMain [--host H] [--port N] [--fake]"`.
 *
 * The provider comes from `application.conf` through `Llm4sConfig` (by default the samples' `ollama-local` section),
 * or, with `--fake`, a scripted client that needs no model. The OpenAPI documentation is at `/docs`.
 */
object TapirChatServerMain extends IOApp {

  /** Serves until stopped; exits with `2` for arguments it cannot use. */
  def run(args: List[String]): IO[ExitCode] =
    Http4sChatServerMain.serve(
      args,
      agent => new TapirChatRoutes(agent).routes.orNotFound,
      s"POST /chat, POST /chat/stream, GET /docs (Swagger UI), GET /docs/${TapirChatRoutes.OpenApiFile}"
    )
}
