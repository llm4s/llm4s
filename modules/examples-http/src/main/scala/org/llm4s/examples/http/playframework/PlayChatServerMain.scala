package org.llm4s.examples.http.playframework

import org.llm4s.examples.http.ServerSettings

/**
 * Runs the Play reference app:
 * `sbt "examplesHttp/runMain org.llm4s.examples.http.playframework.PlayChatServerMain [--host H] [--port N] [--fake]"`.
 *
 * The provider comes from the `llm4s` block of Play's `application.conf` (by default the samples' `ollama-local`
 * section), or, with `--fake`, a scripted client that needs no model.
 */
object PlayChatServerMain {

  /** Serves until stopped; exits with `2` for arguments it cannot use. */
  def main(args: Array[String]): Unit =
    ServerSettings.parse(args.toList) match {
      case Left(message) =>
        println(message)
        sys.exit(2)
      case Right(settings) =>
        val server = PlayChatServer.start(PlayChatServer.builder(settings.fake).build(), settings.host, settings.port)
        sys.addShutdownHook(server.stop())
        val model = if (settings.fake) "the fake client (--fake)" else "the configured provider"
        val port  = server.httpPort.getOrElse(settings.port)
        println(s"Serving $model on http://${settings.host}:$port: POST /chat, POST /chat/stream. Ctrl-C to stop.")
    }
}
