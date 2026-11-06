package org.llm4s.examples.http.playframework

import play.api.{ Application, Mode }
import play.api.inject.guice.GuiceApplicationBuilder
import play.core.server.{ PekkoHttpServer, Server, ServerConfig }

/**
 * The Play reference app: the application `application.conf` configures ([[ChatModule]], [[ChatRouter]]), served by
 * Play's Pekko HTTP server. Started from `main` rather than by the Play sbt plugin, so the module needs no plugin.
 */
object PlayChatServer {

  /** The application in production mode; `fake` serves the scripted client instead of the configured provider. */
  def builder(fake: Boolean): GuiceApplicationBuilder =
    GuiceApplicationBuilder().in(Mode.Prod).configure(s"${ChatModule.ConfigPath}.fake" -> fake)

  /**
   * `app` served on `host`:`port`. Port `0` binds an ephemeral port; the server's `httpPort` has the one bound.
   * `stop()` stops the server and the application, which runs the stop hook that closes the `LLMClient`.
   */
  def start(app: Application, host: String, port: Int): Server =
    PekkoHttpServer.fromApplication(app, ServerConfig(port = Some(port), address = host, mode = app.mode))
}
